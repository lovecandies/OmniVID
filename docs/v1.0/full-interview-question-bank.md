# OmniVid 1.0 完整面试题库

更新时间：2026-06-10

## 使用方法

每道题都按这个结构回答：

```text
业务场景 -> 项目实现 -> 八股关键词 -> 可验证证据 -> 诚实边界
```

项目回答的核心技巧：不要把问题讲成抽象八股，要拉回 OmniVid 的同一条业务链路。

## 一、项目总览

### 1. 你这个项目是做什么的？

回答：

```text
OmniVid 是一个长视频 AI 知识解析工作台。用户上传视频后，系统会做 MD5 去重、创建异步解析任务、用 ffmpeg 抽音频、whisper 生成时间轴字幕、DeepSeek 生成结构化总结，并通过 RAG/向量检索/重排让 Agent 支持单视频和多视频知识库问答。回答会附带视频来源和时间戳引用，可以点击跳转播放器。
```

关键词：

- Spring Boot
- MySQL
- Redis
- ffmpeg
- ASR
- DeepSeek
- Qdrant
- RAG
- Agent trace

### 2. 这个项目和普通视频总结工具有什么区别？

回答：

```text
普通总结工具往往只输出一段摘要。OmniVid 1.0 保留了视频资产、任务状态、字幕时间轴、总结资产、聊天记录、向量索引和知识库关系。它不仅能总结，还能检索、追问、对比多个视频观点，并且回答必须带来源时间戳。
```

### 3. 你最想让面试官追问哪里？

回答：

```text
我最希望被追问三块：第一是 MySQL/Redis 如何保证上传去重和任务状态一致性；第二是 Java 线程池和 ffmpeg/ASR 子进程如何处理长耗时任务；第三是 Agent 如何通过字幕检索、向量召回、rerank 和引用约束降低幻觉。
```

## 二、MySQL

### 1. MySQL 在项目里负责什么？

回答：

```text
MySQL 是事实层，保存视频资产、解析任务、字幕片段、总结资产、聊天记录、LLM Provider、Embedding Provider、知识库和术语词库。Redis 可以缓存或过期，但 MySQL 的唯一索引、联合索引和事务约束负责最终一致性。
```

可验证证据：

- `schema-mysql.sql`
- `GET /api/mysql/explain`
- `GET /api/runtime/status`

### 2. 为什么 MD5 去重不用 Redis 就完了？

回答：

```text
Redis 锁只解决并发窗口内的重复提交，不能作为最终事实。服务重启、锁过期、Redis 短暂不可用时都可能出现并发插入风险。所以我把 Redis 放在性能层，把 MySQL `uk_video_md5` 唯一索引放在事实层。即使两个请求同时插入同一个 md5，MySQL 也会兜底。
```

关键词：

- `SET NX`
- 唯一索引
- 幂等
- 并发冲突
- 最终一致性

### 3. 字幕表为什么建 `video_id + start_ms` 联合索引？

回答：

```text
字幕查询有天然业务前缀：一定先限定某个 video_id，再按 start_ms 排序、定位或跳转。所以 `(video_id, start_ms)` 符合最左前缀原则。播放器点击跳转和 Agent 引用都依赖这个时间轴定位。1.0 还预留了 `(video_id,start_ms,end_ms,segment_index)` 覆盖索引用来减少回表。
```

关键词：

- B+Tree
- 最左前缀
- 联合索引顺序
- 覆盖索引
- 回表
- `EXPLAIN`

### 4. 任务状态怎么避免乱序更新？

回答：

```text
解析任务有上传、抽音频、ASR、总结、向量索引等阶段。多个线程或重试逻辑可能同时触碰同一任务，所以 `processing_job` 里有 `version` 字段，状态推进时可以按乐观锁思想更新。即使 1.0 不是分布式 worker，version 字段也给后续 MQ 化留下边界。
```

关键词：

- 乐观锁
- CAS 思想
- 行锁
- 事务边界
- 状态机

### 5. 知识库表怎么设计？

回答：

```text
知识库是 `knowledge_base`，视频和知识库是多对多关系，用 `knowledge_base_video` 关联。关联表上有 `uk_knowledge_base_video(knowledge_base_id, video_id)`，防止同一个视频重复加入同一个知识库；还有 `idx_kb_video_video(video_id, knowledge_base_id)`，方便从视频反查知识库关系。
```

关键词：

- 多对多关系
- 关联表
- 唯一约束
- 反向索引
- 权限扩展

## 三、Redis

### 1. Redis 在项目里放了哪些东西？

回答：

```text
Redis 只放高频、短期、可重建的状态：上传防重锁 `video:lock:{md5}`、任务进度缓存 `omnivid:progress:{videoId}`、Agent 限流 `omnivid:agent:rate:*`、精确问题缓存 `omnivid:agent:semantic:*`、短期记忆 `omnivid:agent:memory:last-question:*`。
```

### 2. Redis 锁怎么设计？

回答：

```text
锁粒度是视频 MD5，命令等价于 `SET key value NX EX ttl`。value 放随机 token，释放时校验 token，避免误删其他请求的锁。锁只保护上传入库的短临界区，不包住长耗时 ASR；最终仍靠 MySQL 唯一索引兜底。
```

追问：

- 现在释放锁是否完全原子？
- 生产级怎么做？

答法：

```text
MVP 可以说当前是 token 校验释放，生产级会用 Lua 把 compare-and-delete 放在一个原子脚本，或者用 Redisson WatchDog。这里我会诚实说明 Redisson 是 2.0 演进，不把未实现说成已上线。
```

### 3. 进度为什么放 Redis？

回答：

```text
任务进度会被前端频繁读取，而且 SSE 可能断线重连。MySQL 保存最终任务状态，Redis 保存最近进度快照。页面刷新或 SSE 重连时，可以快速补偿当前进度，减少数据库压力。
```

### 4. Agent 限流怎么做？

回答：

```text
1.0 使用 Redis `INCR + TTL` 固定窗口限流，保护 LLM token 成本和后端资源。后续可以升级 Lua 令牌桶或滑动窗口。固定窗口简单直接，适合 MVP，但边界时刻会有突刺，这是我会主动说明的 tradeoff。
```

## 四、Java 并发与 JVM

### 1. 为什么要异步？

回答：

```text
视频处理天然长耗时，ffmpeg 抽音频、whisper ASR、LLM 总结都不适合同步阻塞 HTTP 请求。OmniVid 上传接口只完成文件保存、MD5、任务创建，然后提交本地 DAG 到线程池；前端通过 SSE 和轮询观察进度。
```

### 2. 线程池参数怎么讲？

回答：

```text
1.0 的 `omnividProcessingExecutor` 是 core=2、max=4、queue=20、threadNamePrefix=omnivid-dag-。这是面向本机 demo 和长耗时 IO/CPU 混合任务的保守配置。队列不设无限，避免任务堆积导致内存不可控；拒绝策略让失败暴露，再通过任务状态和失败列表诊断。
```

### 3. 大文件上传怎么避免 OOM？

回答：

```text
不把视频整体读到内存里。上传时流式写入本地文件，同时计算 MD5。字幕和总结是结构化文本，进入数据库；视频文件本体留在磁盘。后续如果上对象存储，也可以保持同样的流式思路。
```

### 4. 子进程调用有哪些坑？

回答：

```text
ffmpeg、whisper、OCR 都是外部进程。Java 调子进程要处理路径、超时、退出码、日志、标准输出和错误输出，否则可能卡死或吞掉错误。OmniVid 会把 ffmpeg/asr 日志保存在视频目录，诊断接口能读日志尾部。
```

### 5. CPU 飙高怎么排查？

回答：

```text
先看诊断台线程池：activeCount、queueSize、completedTaskCount；再看 JVM 堆和非堆；然后结合 ffmpeg/whisper 子进程日志。如果是 Java 线程问题用 jstack，看是否大量任务卡在同一阶段；如果是内存问题看 GC 日志和 jmap；如果是 ASR 子进程问题看 asr.log。
```

## 五、Spring 与事务

### 1. Spring Boot 在项目里承担什么？

回答：

```text
Spring Boot 负责 Controller/Service/Repository 分层、配置 profile、条件装配、统一异常、线程池 Bean、Redis/MySQL/Qdrant/LLM Provider 集成。项目不是单个脚本，而是标准 Java 后端分层。
```

### 2. Docker profile 和默认 profile 有什么区别？

回答：

```text
默认 profile 使用 H2 和本地内存实现，方便快速启动；docker profile 切到 MySQL、Redis 和 Qdrant。这样既能本地开发，也能在面试演示时展示真实中间件。运行时可通过 `/api/runtime/status` 证明当前连接的是 MySQL/Redis/Qdrant。
```

### 3. `@Transactional` 失效怎么结合项目讲？

回答：

```text
可以围绕视频入库和任务创建讲。比如同一个 Service 内部自调用不会经过代理，checked exception 默认不回滚，异步线程里的事务上下文不会自动继承。OmniVid 的边界是：上传请求内完成资产和任务创建，异步 DAG 节点再单独推进状态，不把长耗时子进程包进一个大事务。
```

### 4. 全局异常有什么价值？

回答：

```text
URL 导入、B 站 412、yt-dlp 缺失、ffmpeg 失败、ASR 失败、任务重试冲突都要给前端结构化错误。这样用户看到的是 message/suggestion/detail，而不是一段后端堆栈。
```

## 六、MyBatis/JDBC/SQL

### 1. 为什么没有重 ORM？

回答：

```text
1.0 使用 JdbcClient/Repository 风格，SQL 更显式，适合面试展示索引、事务和 EXPLAIN。比如字幕时间轴、任务状态、知识库关联表都能直接看到 SQL，不被 ORM 隐藏。
```

### 2. 字幕批量入库怎么优化？

回答：

```text
1.0 当前以清晰可靠为主，逐条插入也能支撑 demo。生产化可以改成 batch update，并按视频分批提交，避免单事务过大。面试时我会说明当前实现和后续优化边界。
```

### 3. SQL 注入怎么防？

回答：

```text
Repository 使用参数绑定，不拼接用户输入。字幕搜索的 keyword 作为参数传入 `LIKE :keyword`。URL 导入和 Agent 问题不会拼 SQL。
```

## 七、MQ 与异步架构

### 1. 为什么 1.0 没接 RocketMQ？

回答：

```text
1.0 的目标是单机可运行、可演示、可面试追问。先用本地 DAG 把任务状态、幂等、失败恢复、SSE 推送讲清楚。RocketMQ 是 2.0 演进点，不为了堆技术名词强行引入。
```

### 2. 如果升级 MQ，怎么拆？

回答：

```text
上传完成发 VideoUploaded；音频抽取完成发 AudioExtracted；ASR 完成发 TranscriptReady；总结完成发 SummaryReady；向量索引完成发 VectorIndexed。每个消费者都要按 videoId/jobId 做幂等，失败进入重试或死信队列。
```

追问关键词：

- 可靠消息
- 重复消费
- 顺序消费
- 死信队列
- 延迟消息
- 幂等消费

## 八、网络与操作系统

### 1. SSE 和 WebSocket 怎么选？

回答：

```text
任务进度是服务端单向推送，SSE 足够简单，浏览器原生支持断线重连。WebSocket 更适合双向实时交互，比如多人协同编辑。OmniVid 1.0 用 SSE 更轻。
```

### 2. 视频播放为什么要 Range？

回答：

```text
浏览器播放器不会总是从头读完整文件，会按 Range 请求拖动或缓冲区间。后端媒体接口需要支持 Range，才能让本地上传视频像普通视频资源一样播放和拖动。
```

### 3. TCP 粘包是不是上传问题？

回答：

```text
不是。HTTP Multipart 上传在应用层已经有协议边界，面试里不要把 TCP 粘包硬套到 HTTP 文件上传。真正要讲的是大文件流式处理、超时、分片上传、断点续传和服务端限流。
```

## 九、AI Agent / RAG / 向量数据库

### 1. Agent 为什么不能直接问 LLM？

回答：

```text
直接问 LLM 会丢失视频事实来源，容易幻觉。OmniVid 的 Agent 先检索 ASR 字幕和向量索引，经过 rerank 选出证据，再让 LLM 基于 citations 回答。回答里带 videoId/startMs/endMs，前端可点击跳转。
```

### 2. RAG 在项目里怎么落地？

回答：

```text
字幕片段就是文档 chunk。每个片段有 videoId、startMs、endMs、content。Agent 问题进入后先做关键词检索，再做向量召回，之后 rerank，最后 CitationBuilder 生成引用。LLM 只拿到这些证据回答。
```

### 3. Qdrant 负责什么？

回答：

```text
Qdrant 是外部向量数据库，保存字幕片段向量和 payload。关系型事实仍在 MySQL；向量相似度检索交给 Qdrant。诊断接口能看到 collection、points count、status、dimensions。
```

### 4. DeepSeek 和 Embedding 的关系是什么？

回答：

```text
1.0 把 DeepSeek 只作为 Chat LLM。Embedding 是独立 provider，可以接 Qwen/OpenAI/BGE OpenAI-compatible 服务。未配置外部 Embedding 时用 local hash fallback，保证 demo 不因外部服务失败而断链。
```

### 5. 如何防幻觉？

回答：

```text
有证据时，回答必须绑定 citations；无证据时，系统先说明当前视频或知识库没有检索到相关内容，再给通用回答，且不能声称来自视频。Agent trace 里能看到 AnswerPolicyTool、ConfidenceGuard 和引用数量。
```

### 6. 多视频知识库怎么问答？

回答：

```text
知识库先通过 `knowledge_base_video` 选出一组视频，再聚合这些视频的字幕片段进入同一套检索和 rerank。引用里保留 videoId，所以前端能点击引用切换到对应视频并跳转时间戳。1.0 已验证两个视频对比问题可以返回两个 videoId 的引用。
```

## 十、ASR 精度与 OCR

### 1. 字幕识别不准怎么优化？

回答：

```text
1.0 做了四层：第一，whisper 初始 prompt 加技术热词；第二，SubtitleTextSanitizer 做简体化、乱码修复和技术词纠错；第三，术语词库支持动态规则；第四，OCR 识别画面字幕作为强证据，对低置信 ASR 片段做评估、对齐和保守写回。
```

### 2. 为什么不能过拟合某个视频？

回答：

```text
如果只针对 `38197201623-1-192.mp4` 硬写替换规则，会伤害其他视频。所以我把规则限制在通用技术词和明显编码问题上，例如 Codex、Claude Code、Qdrant、Embedding、AI Agent，以及 `妳 -> 你` 这种简体化残留。OCR 写回也只在高置信或低置信片段里保守应用。
```

## 十一、URL 导入

### 1. B 站链接为什么会 412？

回答：

```text
B 站 412 常见于平台反爬、登录态、WBI 签名或 Cookie 问题。1.0 不做反爬绕过，而是返回清晰建议：提供 cookies.txt 或选择 browser cookies，并确认浏览器已登录。URL 导入是 MVP，核心主线仍是本地上传和后端解析。
```

### 2. 为什么不继续做反爬？

回答：

```text
这是合规边界。项目目标是 Java 后端和 AI Agent 求职展示，不是平台对抗。生产化可以做合规授权、用户主动提供 Cookie 或企业数据源接入，但不做 CAPTCHA 绕过、指纹规避和账号自动化。
```

## 十二、诊断与可观测性

### 1. 诊断台有什么用？

回答：

```text
诊断台把面试证据可视化：Runtime 证明 MySQL/Redis/Qdrant/DeepSeek 连接状态；MySQL EXPLAIN 证明索引命中；Redis Inspect 展示 key 设计；JVM 面板展示线程池；ASR 面板展示模型、音频、日志、字幕质量；Vector 面板展示 Qdrant collection；Recovery 面板展示失败任务补偿。
```

### 2. 为什么强调黑盒验证？

回答：

```text
因为最终用户和面试官不一定看代码。OmniVid 每个核心能力都能用浏览器或 API 验证：上传后有视频、字幕和总结；Agent 回答有引用；Runtime 显示真实中间件；知识库问答能返回多视频来源。这比只说“我用了某技术”更可信。
```

## 十三、1.0 边界问题

### 1. 哪些没做？

回答：

```text
1.0 没做浏览器插件、强鲁棒平台 URL 导入、真实 PPTX 导出、外部 BGE reranker 生产化、多用户权限、云部署、RocketMQ 和知识图谱。这些都在 2.0 路线里。1.0 聚焦可运行闭环和求职面试主线。
```

### 2. 如何避免面试中被认为“堆技术”？

回答：

```text
我会把每个技术都绑定业务痛点：MySQL 解决最终一致性，Redis 解决高频临时状态，线程池解决长耗时任务，SSE 解决进度推送，Qdrant 解决语义召回，Agent trace 解决可追溯和防幻觉。没有业务痛点的技术，我放到 2.0 而不是硬塞进 1.0。
```
