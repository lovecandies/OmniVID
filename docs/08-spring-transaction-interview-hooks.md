# OmniVid Spring 与事务面试钩子作战手册

## 1. 面试总叙事

OmniVid 的 Spring 设计重点不是“会写 Controller”，而是把长视频上传、异步解析、Agent 问答拆成清晰的分层边界：

1. Controller 负责 HTTP 协议、参数绑定、文件上传、Range 播放、SSE。
2. Service 负责业务编排、事务边界、异步任务投递和异常语义。
3. Repository 负责 SQL 和数据映射，当前使用 Spring `JdbcClient`。
4. Redis/local 实现通过 `@ConditionalOnProperty` 切换，展示 Spring Bean 条件装配能力。
5. `@RestControllerAdvice` 统一把业务异常转成前端可读 JSON。

一句话项目话术：

```text
OmniVid 后端用 Spring Boot 做分层：Controller 承接上传、视频播放、字幕、总结、SSE 和 Agent 问答接口；Service 编排 MD5 去重、任务状态机和异步 DAG；Repository 用 JdbcClient 操作 MySQL；Redis、本地锁、本地缓存通过 ConditionalOnProperty 切换实现。事务上，我把视频登记和任务创建放在 Service 层边界，避免出现只有视频没有任务的半成功状态。
```

回答模板：

```text
业务入口 -> Spring 分层职责 -> 事务/异常/Bean 装配边界 -> 可验证结果 -> 八股关键词
```

## 2. 当前已实现 Spring 落点

| 场景 | 当前代码落点 | 当前实现 | 可讲八股 |
| --- | --- | --- | --- |
| REST API | `VideoController`, `AgentController`, `ProcessingJobController` | `@RestController`, `@RequestMapping`, `@GetMapping`, `@PostMapping` | Spring MVC、参数绑定、REST 设计 |
| 文件上传 | `VideoController.uploadFile` | `MultipartFile`, `multipart/form-data` | Multipart 解析、流式处理、文件大小限制 |
| 视频播放 | `VideoController.media` | `ResponseEntity`, `Range`, `206 Partial Content` | HTTP Range、响应头、流式返回 |
| SSE 进度 | `VideoController.progressStream` | `produces = TEXT_EVENT_STREAM_VALUE` | SSE、HTTP 长连接、超时 |
| 业务编排 | `VideoService`, `AgentService` | `@Service`, 构造器注入 | IoC、DI、单一职责 |
| 数据访问 | 多个 `*Repository` | `@Repository`, `JdbcClient` | DAO、SQL 映射、异常翻译 |
| 事务边界 | `VideoService.completeUpload` | `@Transactional` | 代理机制、事务失效、传播机制 |
| 统一异常 | `GlobalExceptionHandler` | `@RestControllerAdvice`, `@ExceptionHandler` | 全局异常处理、错误响应 |
| CORS | `WebConfig` | `WebMvcConfigurer.addCorsMappings` | 跨域、预检请求 |
| 环境切换 | Redis/local 组件 | `@ConditionalOnProperty` | Bean 条件装配、策略模式 |
| 线程池 Bean | `ProcessingExecutorConfig` | `@Configuration`, `@Bean` | Bean 生命周期、配置类 |

当前要诚实表达：

```text
项目当前没有实现登录鉴权 Filter/Interceptor，也没有真正落地 AOP 耗时日志；这些是后续扩展点。当前真实落地的是 Spring MVC、构造器依赖注入、Service 事务、JdbcClient Repository、全局异常、CORS 和条件装配。
```

## 3. Controller 层：HTTP 入口设计

业务痛点：

OmniVid 前端需要调用多个后端能力：上传本地视频、拉视频列表、查看详情、播放视频、获取字幕和总结、查询进度、订阅 SSE、向 Agent 追问。

当前设计：

- `/api/videos/upload/file`：上传本地视频文件。
- `/api/videos`：视频列表。
- `/api/videos/{videoId}`：视频详情。
- `/api/videos/{videoId}/media`：视频播放，支持 Range。
- `/api/videos/{videoId}/transcripts`：字幕。
- `/api/videos/{videoId}/summaries`：总结。
- `/api/videos/{videoId}/progress`：进度快照。
- `/api/videos/{videoId}/progress/stream`：SSE 进度。
- `/api/videos/{videoId}/agent/ask`：当前视频 Agent 问答。

面试官可能追问：

- Controller 为什么不直接写业务逻辑？
- `@RequestBody` 和 `@RequestPart` 区别？
- 文件上传为什么用 `MultipartFile`？
- 视频播放为什么要支持 Range？
- SSE 接口和普通 JSON 接口有什么区别？
- RESTful API 如何设计？

标准回答：

```text
Controller 层只处理 HTTP 协议相关的事情，比如路径参数、请求体、Multipart 文件、Range 请求和 SSE 响应。真正的业务编排放在 Service 层。比如上传接口在 Controller 里只接收 MultipartFile，然后交给 LocalVideoStorageService 存储，再调用 VideoService 创建任务。这样 HTTP 细节和业务流程解耦，后续换 URL 解析入口或对象存储，也不会把 Controller 写成大杂烩。
```

`@RequestBody` vs `@RequestPart`：

```text
@RequestBody 适合 JSON 请求体，比如兼容接口 `/upload/complete` 接收 md5、文件名、时长等元数据。@RequestPart 适合 multipart/form-data 里的某个 part，比如真实文件上传里的 file。
```

Range 播放话术：

```text
浏览器播放视频会发 Range 请求，只要某一段字节。后端支持 206 Partial Content，返回 Content-Range 和 Accept-Ranges，播放器才能拖动进度条和按需加载。
```

八股关键词：

- Spring MVC
- `@RestController`
- `@RequestMapping`
- `@RequestBody`
- `@RequestPart`
- `MultipartFile`
- `ResponseEntity`
- HTTP Range
- SSE

简历钩子：

```text
基于 Spring MVC 设计视频上传、Range 播放、字幕查询、SSE 进度和 Agent 问答 REST API，将 HTTP 协议细节与后端业务编排解耦。
```

## 4. Service 层：业务编排边界

业务痛点：

长视频解析不是单表 CRUD，而是多个动作的编排：文件存储、MD5 去重、视频资产入库、任务创建、后台 DAG、进度缓存、ASR 和总结。

当前设计：

- `VideoService` 编排视频相关主链路。
- `AgentService` 编排字幕检索、限流、聊天记录。
- `LocalVideoStorageService` 负责文件存储和 MD5 计算。
- `FfmpegAudioExtractionService` 负责抽音频。
- `WhisperAsrService` 负责 ASR。
- Repository 不反向依赖 Service。

面试官可能追问：

- 为什么要分 Controller/Service/Repository？
- Service 里可以调用多个 Repository 吗？
- 事务应该放 Controller 还是 Service？
- Service 之间互相调用有什么风险？
- 构造器注入和字段注入怎么选？

标准回答：

```text
我把事务和业务编排放在 Service 层，因为它是用例边界。Controller 只关心 HTTP，Repository 只关心 SQL，Service 负责保证“上传登记 + 任务创建 + 异步投递”的业务语义。事务如果放 Controller，HTTP 细节会和数据库一致性混在一起；如果放 Repository，只能覆盖单表操作，不适合跨表业务。
```

构造器注入话术：

```text
项目里主要使用构造器注入，这样依赖是 final 语义的，创建 Bean 时就能发现缺依赖问题，也更方便测试。字段注入虽然写起来短，但不利于不可变依赖和单元测试。
```

八股关键词：

- IoC
- DI
- 构造器注入
- Bean 依赖
- Service 编排
- 分层架构
- 单一职责

简历钩子：

```text
基于 Spring Service 层编排视频上传、MD5 去重、任务创建、异步 DAG、进度缓存和 Agent 检索，明确 Controller、Service、Repository 的职责边界。
```

## 5. `@Transactional` 事务边界

业务痛点：

上传登记时不能出现只有 `video_asset` 没有 `processing_job`，也不能出现重复视频资产导致重复解析。

当前实现：

- `VideoService.completeUpload` 使用 `@Transactional`。
- 这个接口是兼容元数据登记接口。
- 真实文件上传 `completeStoredUpload` 当前没有 `@Transactional` 注解，但内部创建视频和任务的逻辑非常适合纳入同一个事务边界。
- MySQL 唯一索引仍然负责最终幂等兜底。

面试官可能追问：

- `@Transactional` 为什么放在 Service？
- `@Transactional` 什么时候会失效？
- 默认回滚哪些异常？
- 自调用为什么失效？
- 事务传播机制有哪些？
- 文件存储和数据库事务如何协调？
- 异步线程里的事务和主线程事务是什么关系？

标准回答：

```text
上传登记涉及 video_asset 和 processing_job 两张表，应该放在同一个 Service 层事务里。如果任务创建失败，视频资产也不能半成功。Spring 的 @Transactional 基于代理生效，所以要放在外部调用的 public Service 方法上；同类内部方法自调用不会经过代理，事务可能失效。默认情况下 RuntimeException 会回滚，受检异常需要配置 rollbackFor。
```

当前项目的诚实补充：

```text
当前 completeUpload 已经标了 @Transactional；真实文件上传 completeStoredUpload 还没有标注，这是一个可以继续补强的点。因为文件落盘不属于数据库事务，实际生产会先存临时文件，再在事务里写 video_asset 和 processing_job，事务提交后投递异步任务；如果数据库失败，清理临时文件。
```

事务失效速记：

| 场景 | 为什么失效 | OmniVid 话术 |
| --- | --- | --- |
| 同类内部调用 | 没经过 Spring 代理 | 事务边界放 public Service 入口 |
| 方法不是 public | 代理默认拦不到 | 业务入口方法保持 public |
| 异常被 catch 不抛 | Spring 感知不到失败 | 捕获后要转换成 RuntimeException 或手动标记回滚 |
| 抛受检异常 | 默认不回滚 | 配置 `rollbackFor` |
| 新线程执行 | 事务上下文不跨线程 | 后台 DAG 每步自己写状态，不依赖上传事务 |
| Bean 自己 new | 不受容器管理 | 组件交给 Spring 管理并注入 |

传播机制怎么讲：

```text
常用的是 REQUIRED，当前有事务就加入，没有就新建。上传登记适合 REQUIRED。REQUIRES_NEW 会挂起外层事务新开事务，适合审计日志这类不想跟主事务一起回滚的场景。NESTED 是嵌套事务，依赖保存点。
```

文件与 DB 事务：

```text
文件系统不能跟 MySQL 自动做同一个事务，所以要用业务补偿。OmniVid 当前先把文件存到本地并算 MD5，再写数据库。如果数据库写失败，需要清理临时文件；如果文件已经存在且 MD5 重复，直接复用已有视频记录。
```

八股关键词：

- `@Transactional`
- Spring AOP 代理
- 自调用失效
- rollbackFor
- REQUIRED
- REQUIRES_NEW
- 事务传播
- 文件和数据库一致性
- 异步线程事务

简历钩子：

```text
在视频上传登记链路中设计 Service 层事务边界，将视频资产创建和解析任务创建纳入同一业务用例，并结合 MySQL 唯一索引处理并发幂等。
```

## 6. Repository 与 JdbcClient

业务痛点：

OmniVid 需要明确控制 SQL：MD5 唯一查询、任务状态乐观锁、字幕时间轴联合索引、总结幂等插入。

当前设计：

- Repository 使用 Spring `JdbcClient`。
- SQL 明确写在 Repository 中。
- 每个 Repository 只负责一个业务聚合附近的数据访问：
  - `VideoRepository`
  - `ProcessingJobRepository`
  - `TranscriptRepository`
  - `SummaryRepository`
  - `ChatMessageRepository`

面试官可能追问：

- 为什么当前用 JdbcClient，不用 MyBatis？
- `@Repository` 有什么作用？
- SQL 写在 Service 里行不行？
- 如何防 SQL 注入？
- JdbcClient 和 JdbcTemplate 区别？

标准回答：

```text
当前项目 SQL 不复杂，但我需要清楚控制索引命中和乐观锁更新，所以用 Spring JdbcClient 直接写 SQL。Repository 层隔离数据访问细节，Service 不拼 SQL。JdbcClient 支持命名参数和流式 API，比传统 JdbcTemplate 更简洁。MyBatis 适合复杂动态 SQL 和 Mapper 生态，后续可以迁移，但当前为了 MVP 简洁先用 JdbcClient。
```

`@Repository` 话术：

```text
@Repository 表示数据访问组件，交给 Spring 扫描成 Bean；同时 Spring 可以对持久层异常做统一转换，把底层 SQL 异常转换为 DataAccessException 体系，方便上层处理。
```

SQL 注入话术：

```text
项目里使用 JdbcClient 参数绑定，比如 `WHERE id = :id` 再 `.param("id", id)`，不是字符串拼接用户输入。字幕搜索如果后续支持关键词，也要用参数绑定或全文索引，而不是拼接 SQL。
```

八股关键词：

- `@Repository`
- `JdbcClient`
- `JdbcTemplate`
- 参数绑定
- SQL 注入
- DataAccessException
- MyBatis 对比
- DAO 层

简历钩子：

```text
基于 Spring `JdbcClient` 构建 Repository 层，显式控制视频 MD5 查询、任务乐观锁更新和字幕时间轴索引查询，并通过参数绑定避免 SQL 注入。
```

## 7. 全局异常处理

业务痛点：

前端不应该收到一堆默认 HTML 错误页或 Java 堆栈。比如视频不存在、限流、上传空文件，都应该返回统一 JSON。

当前设计：

- 自定义 `ApiException`，携带 `HttpStatus`。
- `GlobalExceptionHandler` 使用 `@RestControllerAdvice`。
- `@ExceptionHandler(ApiException.class)` 返回指定状态码。
- `@ExceptionHandler(MethodArgumentNotValidException.class)` 返回参数校验错误。
- 错误体包含 `timestamp` 和 `message`。

示例错误：

```json
{
  "timestamp": "2026-06-07T00:00:00Z",
  "message": "Video not found"
}
```

面试官可能追问：

- `@ControllerAdvice` 和 `@RestControllerAdvice` 区别？
- 为什么不用每个 Controller 自己 try/catch？
- 参数校验异常怎么处理？
- 业务异常和系统异常怎么区分？
- 是否应该把异常堆栈返回前端？

标准回答：

```text
OmniVid 用 ApiException 表达可预期业务错误，比如视频不存在、上传空文件、Agent 限流。GlobalExceptionHandler 统一把这些异常转成 JSON，避免每个 Controller 重复 try/catch。@RestControllerAdvice 相当于 @ControllerAdvice + @ResponseBody，更适合 REST API。系统异常不会把堆栈暴露给前端，生产环境只返回通用错误并记录日志。
```

八股关键词：

- `@RestControllerAdvice`
- `@ExceptionHandler`
- `ResponseEntity`
- 参数校验
- 业务异常
- 系统异常
- 错误码设计

简历钩子：

```text
设计统一异常处理机制，通过自定义 `ApiException` 和 `@RestControllerAdvice` 将视频不存在、上传错误、Agent 限流等业务异常转换为稳定 JSON 响应。
```

## 8. 条件装配与策略切换

业务痛点：

开发阶段可以用本地内存锁和本地进度缓存，Docker 模式下要切到 Redis。业务代码不应该到处写 `if redis enabled`。

当前设计：

- `DedupeLockService`
  - `LocalDedupeLockService`
  - `RedisDedupeLockService`
- `ProgressCacheService`
  - `LocalProgressCacheService`
  - `RedisProgressCacheService`
- `AgentRateLimiter`
  - `LocalAgentRateLimiter`
  - `RedisAgentRateLimiter`
- 使用 `@ConditionalOnProperty` 根据配置选择 Bean。

配置示例：

```yaml
omnivid:
  dedupe-lock:
    mode: redis
  progress-cache:
    mode: redis
  agent-rate-limit:
    mode: redis
```

面试官可能追问：

- 多个实现类如何选择注入哪个？
- `@ConditionalOnProperty` 怎么工作？
- 这和策略模式有什么关系？
- 和 `@Profile` 有什么区别？
- Bean 冲突怎么处理？

标准回答：

```text
上传防重、进度缓存、Agent 限流都有本地实现和 Redis 实现。业务层只依赖接口，比如 DedupeLockService，不关心底层是 ConcurrentHashMap 还是 Redis。具体实现通过 @ConditionalOnProperty 按配置装配，这本质上是策略模式加 Spring 条件 Bean。相比把 if/else 写进 Service，这样更符合开闭原则，也方便本地开发和 Docker 环境切换。
```

`@Profile` 对比：

```text
@Profile 更适合按环境整体切换，比如 dev、test、prod。@ConditionalOnProperty 更细粒度，可以只切某个能力的实现，比如 dedupe-lock.mode=redis，而不是整个应用 profile。
```

八股关键词：

- `@ConditionalOnProperty`
- Bean 条件装配
- 接口注入
- 策略模式
- `@Profile`
- Bean 冲突
- IoC 容器

简历钩子：

```text
通过接口抽象和 `@ConditionalOnProperty` 实现本地/Redis 组件策略切换，使上传防重、进度缓存和 Agent 限流在开发模式与 Docker 模式下无侵入切换。
```

## 9. Filter / Interceptor / AOP 怎么讲

当前项目状态：

- 当前没有实现登录鉴权 Filter/Interceptor。
- 当前没有实现 Spring AOP 耗时日志。
- 但 OmniVid 很适合把这三个作为后续扩展点来讲。

三者区别：

| 技术 | 所在层级 | 适合场景 | OmniVid 可用法 |
| --- | --- | --- | --- |
| Filter | Servlet 容器层 | CORS、编码、请求体包装、最外层安全控制 | 通用请求日志、跨框架安全头 |
| Interceptor | Spring MVC 层 | 登录态、权限、用户上下文、Controller 前后处理 | 解析 userId，隔离个人视频库 |
| AOP | Spring Bean 方法层 | 业务方法耗时、审计、权限注解、事务也是 AOP 思路 | 统计 ASR、Agent、Repository 方法耗时 |

面试官可能追问：

- 登录鉴权放哪里？
- AOP 能拦截 Controller 吗？
- Filter 和 Interceptor 执行顺序？
- AOP 为什么同类内部调用可能失效？
- 事务和 AOP 什么关系？

标准回答：

```text
如果是跨框架的最外层请求处理，比如安全头、编码、请求日志，可以放 Filter；如果是 Spring MVC 路由相关的登录态和用户上下文，我更倾向放 Interceptor；如果是业务方法耗时统计、审计日志、权限注解，则适合 AOP。OmniVid 后续登录后，可以在 Interceptor 里解析 userId，Service 层按 userId 隔离视频库；ASR 和 Agent 耗时统计可以用 AOP。
```

事务与 AOP：

```text
Spring 声明式事务本质上也是基于 AOP 代理。方法调用经过代理时，代理在方法前开启事务，方法后提交或回滚。所以自调用不经过代理，@Transactional 可能失效。
```

八股关键词：

- Servlet Filter
- HandlerInterceptor
- Spring AOP
- 执行链路
- 登录态
- 用户上下文
- 事务代理
- 自调用失效

简历钩子：

```text
设计 OmniVid 登录鉴权扩展方案：使用 Interceptor 解析用户上下文隔离个人视频库，使用 AOP 统计 ASR 和 Agent 方法耗时，Filter 保留给跨框架请求处理。
```

## 10. CORS 与前后端联调

业务痛点：

前端 Vite 运行在 `5173`，后端 Spring Boot 运行在 `8080`。浏览器会触发跨域限制。

当前设计：

- `WebConfig` 实现 `WebMvcConfigurer`。
- 允许 `/api/**`。
- 允许来源：
  - `http://localhost:5173`
  - `http://127.0.0.1:5173`
- 允许常见 HTTP 方法。

面试官可能追问：

- CORS 是浏览器限制还是服务端限制？
- 简单请求和预检请求是什么？
- 为什么不能直接允许 `*`？
- Cookie 鉴权下 CORS 要注意什么？

标准回答：

```text
CORS 是浏览器的同源策略限制，服务端通过响应头告诉浏览器哪些来源可以访问。OmniVid 前端和后端端口不同，所以在 WebConfig 里放行 Vite 的 localhost 和 127.0.0.1。生产环境不应该随便允许 `*`，尤其带 Cookie 或 Authorization 时，要按真实域名白名单配置。
```

八股关键词：

- CORS
- Same-Origin Policy
- Preflight
- OPTIONS
- `Access-Control-Allow-Origin`
- Cookie
- Authorization

简历钩子：

```text
基于 Spring `WebMvcConfigurer` 配置前后端跨域白名单，支持 Vite 前端与 Spring Boot API 在本地开发环境稳定联调。
```

## 11. 参数校验

当前实现：

- Controller 方法上使用 `@Valid`。
- `GlobalExceptionHandler` 捕获 `MethodArgumentNotValidException`。
- 具体字段约束取决于请求 DTO。

面试官可能追问：

- `@Valid` 在哪里生效？
- 参数校验失败如何统一返回？
- Controller 校验和 Service 校验怎么分工？

标准回答：

```text
Controller 层用 @Valid 做请求格式和字段级校验，比如必填、长度、格式。校验失败由全局异常处理统一返回 400。Service 层负责业务规则校验，比如视频是否存在、用户是否有权限、任务状态是否允许推进。两层校验职责不同。
```

八股关键词：

- Bean Validation
- `@Valid`
- `MethodArgumentNotValidException`
- 400 Bad Request
- DTO

简历钩子：

```text
使用 Bean Validation 和全局异常处理统一请求参数校验结果，区分 Controller 字段校验与 Service 业务规则校验。
```

## 12. 高频面试问答速记

### Q1：Spring MVC 请求到业务方法的链路是什么？

```text
请求先经过 Filter，再进入 DispatcherServlet，匹配 HandlerMapping，执行 Interceptor preHandle，然后调用 Controller。Controller 调 Service，Service 调 Repository。返回后经过 Interceptor postHandle/afterCompletion，最后由消息转换器写回 JSON。
```

### Q2：为什么事务放 Service 层？

```text
事务应该包住一个完整业务用例。上传登记不是单表操作，而是视频资产和任务创建的一致性，所以放 Service 层。Controller 只处理 HTTP，Repository 只处理 SQL。
```

### Q3：`@Transactional` 什么情况下失效？

```text
常见是同类内部自调用、方法不是 public、异常被 catch 吞掉、抛受检异常但没配置 rollbackFor、对象不是 Spring 管理的 Bean、异步线程里执行导致事务上下文不跨线程。
```

### Q4：`@RestControllerAdvice` 有什么用？

```text
统一处理 Controller 抛出的异常，把业务异常转成稳定 JSON 和 HTTP 状态码，避免每个接口重复 try/catch，也避免把堆栈暴露给前端。
```

### Q5：Spring Bean 多实现怎么选择？

```text
可以用 @Primary、@Qualifier、@Profile 或 @ConditionalOnProperty。OmniVid 用 ConditionalOnProperty 在本地实现和 Redis 实现之间切换，业务层只依赖接口。
```

### Q6：Filter、Interceptor、AOP 怎么区分？

```text
Filter 在 Servlet 层，适合最外层通用处理；Interceptor 在 Spring MVC 层，适合登录态和用户上下文；AOP 在 Bean 方法层，适合业务方法耗时、审计和声明式事务。
```

### Q7：JdbcClient 和 MyBatis 怎么取舍？

```text
当前 SQL 可控且数量不大，用 JdbcClient 更轻量，方便显式控制索引和乐观锁 SQL。MyBatis 更适合复杂动态 SQL、Mapper 生态和团队规范，后续可以迁移。
```

### Q8：文件上传和数据库事务怎么保证一致？

```text
文件系统不参与 MySQL 事务，所以要靠业务补偿。先存临时文件并计算 MD5，再在事务里写数据库；数据库失败则清理临时文件，数据库成功后再投递异步任务。
```

### Q9：异步任务能复用上传接口事务吗？

```text
不能。事务上下文绑定当前线程，异步线程不会继承主线程事务。后台 DAG 每个阶段要自己落库更新状态，不能依赖上传事务。
```

### Q10：为什么 Controller 不直接返回文件字节数组？

```text
视频可能很大，直接读成 byte[] 容易 OOM。当前用 Resource/InputStreamResource 流式返回，并支持 Range 请求，浏览器可以按需加载和拖动播放。
```

## 13. 简历埋钩子写法

可直接使用：

```text
基于 Spring MVC 设计视频上传、Range 播放、字幕查询、SSE 进度和 Agent 问答接口，清晰拆分 Controller、Service、Repository 职责。
```

```text
在 Service 层设计视频上传登记事务边界，将视频资产创建和解析任务创建纳入同一业务用例，并结合 MySQL 唯一索引处理并发幂等。
```

```text
使用 `@RestControllerAdvice` 和自定义 `ApiException` 统一处理视频不存在、上传失败、Agent 限流等业务异常，向前端返回稳定 JSON 错误结构。
```

```text
通过 `@ConditionalOnProperty` 和接口抽象实现本地/Redis 策略切换，使上传防重、进度缓存、Agent 限流在不同运行模式下无侵入切换。
```

```text
基于 Spring `JdbcClient` 构建轻量 Repository 层，显式控制 MD5 去重、任务乐观锁、字幕时间轴检索等关键 SQL。
```

更强版本：

```text
围绕长视频上传与异步解析业务，构建 Spring Boot 分层后端：MVC 层承接文件上传、Range 播放和 SSE 推送，Service 层控制事务与异步 DAG 编排，Repository 层使用 JdbcClient 操作 MySQL，并通过全局异常处理和条件装配提升接口稳定性与环境切换能力。
```

## 14. 30 秒项目表达

```text
OmniVid 的 Spring 后端按 Controller、Service、Repository 分层。Controller 负责文件上传、视频 Range 播放、字幕总结接口、SSE 进度和 Agent 问答；Service 负责 MD5 去重、任务创建、事务边界和后台 DAG 编排；Repository 用 JdbcClient 明确控制 MySQL 查询和乐观锁更新。Redis 和本地实现通过 ConditionalOnProperty 切换，全局异常通过 RestControllerAdvice 统一返回 JSON。当前登录 Interceptor 和 AOP 耗时日志还没做，但已经有清晰扩展路径。
```

## 15. 一句话防守边界

```text
当前已实现的是 Spring MVC、Service 分层、JdbcClient Repository、`@Transactional` 兼容上传登记、全局异常、CORS 和条件装配；登录 Interceptor、AOP 耗时日志、MyBatis 迁移属于二阶段演进点。
```
