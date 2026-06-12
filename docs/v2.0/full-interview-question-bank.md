# OmniVid 2.0 完整面试题库

回答时始终使用：

```text
业务场景 -> 方案 -> 取舍 -> 风险 -> 验证
```

## MySQL

### 为什么 Redis 防重后还需要 MySQL 唯一索引？

Redis 可能过期、故障或发生锁误释放，只能作为性能层。`video_asset.md5` 唯一索引是最终幂等兜底，并发插入冲突后回查已有视频。

追问：唯一索引冲突发生在哪个阶段？事务如何回滚？为什么不用普通索引？

### 为什么任务状态更新需要乐观锁？

长任务可能被重试、重复消费或旧 Worker 晚到。更新时带版本和预期状态，只有当前版本能推进，避免旧结果覆盖新状态。

追问：乐观锁适合什么冲突频率？高冲突时如何改进？

### Outbox 如何解决消息与数据库一致性？

视频、任务和事件在一个本地事务写入。事务成功后 Publisher 扫描事件投递；Broker 不可用时事件仍在 MySQL，后续继续重试。

追问：Outbox 表会不会膨胀？如何分页扫描？如何归档？

### 字幕时间轴如何建索引？

按视频过滤、按时间或片段序号排序，使用视频 ID 与时间/序号联合索引，避免全表扫描；通过 EXPLAIN 验证访问类型和回表情况。

追问：最左前缀、覆盖索引、索引下推、深分页。

## Redis

### SETNX 锁有什么风险？

业务执行超过 TTL 会造成锁提前释放；进程暂停可能导致并发进入。最终仍需 MySQL 唯一索引兜底。生产可用带 token 的释放脚本和续期机制。

### 缓存一致性怎么做？

字幕编辑先更新 MySQL，再清理视频及所属知识库 Agent 缓存；缓存可丢失，MySQL 是事实源。对强一致要求高的场景可引入消息驱动失效。

### 如何应对缓存穿透、击穿、雪崩？

穿透：空结果短 TTL；击穿：热点互斥或逻辑过期；雪崩：随机 TTL、限流和降级。OmniVid 还保留本地 fallback。

## Java/JVM

### 大文件上传怎么避免 OOM？

浏览器和后端分片传输，服务端按流写入临时文件，合并时顺序流式复制，不把完整视频读入堆。

### ffmpeg 子进程有哪些坑？

stdout/stderr 未消费可能阻塞，需记录日志、设置超时、检查退出码并在失败时销毁进程。JVM 内存和子进程内存是两套边界。

### 如何排查解析时 CPU 飙高或线程卡死？

先用 Runtime/线程池指标确认队列和活跃线程，再用 `jstack` 看阻塞与死锁，结合进程列表判断 ffmpeg/whisper，必要时分析 GC 日志和 `jmap`。

## Spring

### `@Transactional` 为什么会失效？

常见原因是同类自调用、非 public 方法、异常被吞、抛出非默认回滚异常、对象非 Spring Bean。Outbox 事务必须从代理边界进入。

### Filter、Interceptor、AOP 怎么选？

Trace Header 属于 Servlet 请求生命周期，用 Filter；接口权限和业务前后处理可用 Interceptor；跨业务方法的日志、审计或事务关注点可用 AOP。

## RocketMQ

### 如何保证不重复执行解析？

消息只携带 eventId，Consumer 回查 Outbox 并用状态 CAS 抢占；同一 job+eventType 有唯一约束；解析写入也有任务状态和业务唯一约束。

### Broker 挂了怎么办？

业务事务仍提交 Outbox PENDING，Publisher 按退避策略重试。Broker 恢复后投递继续，任务不丢。

### 为什么仍需要 DLQ？

永久失败或毒消息无限重试会浪费资源并阻塞排障。达到阈值后进入 DLQ，保留失败原因和 Trace，修复后人工重投。

### 为什么不用 RocketMQ 事务消息？

当前 MySQL Outbox 更容易在本地演示、审计和恢复，也能清晰展示数据库状态；代价是需要 Publisher 扫描和表归档。生产可根据吞吐评估事务消息或 CDC。

## RAG / Agent

### 关键词召回、向量召回和 rerank 分别解决什么？

关键词召回精确匹配术语；向量召回处理语义近似；rerank 对少量候选做更精细排序。三者分层兼顾召回率、精度和成本。

### 为什么 Qdrant 不是最终事实？

向量可以从 MySQL 字幕和当前 Embedding 模型重建。字幕才是业务事实，Qdrant 是检索索引。

### Embedding 模型变化怎么办？

不同模型维度和语义空间不兼容，切换 Provider 后必须重建 collection；系统检测维度不一致时删除旧 collection 并重建。

### 如何降低幻觉？

生成前做严格引用过滤；视频内容回答附带来源与时间戳；没有证据时明确声明，再区分为通用回答；低置信回答可以拒绝或降级。

### 如何验证 rerank 真的生效？

测试 Provider 后检查 Runtime provider/diagnostic，再在 Agent 执行链路查看 rerank 前候选、重排分数和最终引用变化。

## ASR / OCR / 数据回流

### 为什么不直接用 OCR 覆盖 ASR？

画面可能无字幕、字幕区域有噪声或 OCR 识别错误。ASR 提供连续时间轴，OCR 只作为高置信第二证据，保守覆盖避免精度倒退。

### 字幕编辑后为什么要重建总结和向量？

总结、向量和语义缓存都是字幕的派生数据。只改字幕会导致回答仍使用旧内容，因此必须做派生资产失效和重建。

### 完整快照和增量版本如何取舍？

2.0 使用完整快照，恢复简单可靠，适合当前字幕规模；大规模生产可改为增量事件或定期快照加变更日志。

## 网络/OS

### SSE 与 WebSocket 为什么选 SSE？

任务进度主要是服务端单向推送，SSE 基于 HTTP、浏览器自动重连、实现简单。需要双向实时协作时再用 WebSocket。

### HTTP Range 有什么作用？

播放器无需下载完整视频，可以请求指定字节范围，实现拖动和渐进播放。成功响应为 `206 Partial Content`。

### TCP 粘包与 HTTP 文件上传是什么关系？

粘包是 TCP 字节流层问题，HTTP 由协议层 Content-Length/chunked/multipart 解析边界，应用不应自行按 TCP 包拆 HTTP 请求。

## Docker / CI / 可观测

### 为什么用多阶段 Dockerfile？

构建阶段包含 Maven/Node 工具，运行阶段只保留 JRE 或 Nginx 和产物，减小镜像并降低攻击面。

### Trace 如何跨 HTTP 和 MQ？

Filter 生成或继承 `X-Trace-Id`，写入 MDC 和 Outbox payload；Publisher 写入消息属性，Consumer 恢复 MDC，DAG 日志沿用同一 Trace。

### 如何证明项目可复现？

新机器执行 Compose 配置校验和完整启动，Runtime 显示 MySQL、Redis、Qdrant、RocketMQ 连接；CI 同时验证后端测试、前端构建和镜像构建。

## 诚实边界问题

### 这是微服务吗？

不是。当前是模块化单体加外部基础设施，RocketMQ Consumer 仍在 API 进程。选择原因是降低演示和部署复杂度，后续可按解析吞吐拆 Worker。

### 是否已实现多租户？

没有。数据模型有用户预留，但当前固定 demo 用户。2.0 收束重点是解析可靠性和 AI 回流，多租户放到 2.1。

### 是否全部使用真实 Embedding/Rerank？

Provider 管理和远程调用链路已经完成；未配置或远程不可用时自动降级到 `local-hash` 和 `local-rerank`，运行态会明确展示。
