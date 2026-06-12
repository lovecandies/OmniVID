# OmniVid 2.0 面试主叙事与技术钩子

## 30 秒项目介绍

> OmniVid 是一个 Java 后端主导的长视频 AI 知识工作台。我用 Spring Boot、MySQL、Redis、RocketMQ 和 Qdrant 跑通了分片上传、MD5 去重、可靠异步解析、ASR/OCR 字幕、字幕版本回流、结构化总结、多视频 RAG Agent 和 PPTX/DOCX/Markdown 导出。系统用 MySQL Outbox 保证任务不丢，用 Redis 承担高频临时状态，用 Qdrant 做字幕语义召回，并通过时间戳引用和字幕人工回流降低 Agent 幻觉。

## 3 分钟项目介绍

回答顺序：

1. **痛点**：长视频难上传、难检索、难沉淀，长任务容易超时且 AI 回答难验证。
2. **主链路**：浏览器分片上传后，MySQL 同时创建视频、任务和 Outbox 事件；RocketMQ Consumer 执行 ffmpeg、ASR、OCR、总结和向量索引。
3. **一致性**：MySQL 保存最终事实；Redis 只做锁、进度、限流、短期记忆和语义缓存；Qdrant 可重建。
4. **AI 可追溯**：Agent 先关键词/向量召回，再 rerank，回答必须附带视频和时间戳。
5. **人在回路**：字幕支持编辑、版本和恢复，变化后自动回流总结、向量和缓存。
6. **工程证据**：Docker 一键复现、JSON Trace 串联 HTTP 到 MQ、CI 自动测试与构建。

## 通用回答模板

每个问题都按以下结构回答：

```text
业务痛点 -> 技术方案 -> 关键取舍 -> 八股关键词 -> 验证结果
```

例：

> 视频解析可能持续几十分钟，HTTP 同步等待不可靠，所以我用 MySQL Outbox 与 RocketMQ 调度。任务和事件在同一事务创建，Publisher 异步投递，Consumer 用数据库 CAS 抢占事件，解决消息丢失和重复消费。这里可以展开本地事务、最终一致性、幂等消费、重试与 DLQ。黑盒验证时我主动停止 Broker，事件仍留在 MySQL，Broker 恢复后自动完成。

## 技术栈埋钩子

### MySQL

业务入口：

- `video_asset.md5` 唯一索引兜底秒传。
- `processing_job.version` 乐观锁控制状态推进。
- `processing_event` 实现 Outbox。
- 字幕联合索引支撑时间轴定位。
- 字幕版本快照支持回滚。

话术：

> Redis 锁只能减少并发冲突，最终幂等仍由 MySQL MD5 唯一索引兜底；解析任务通过状态机和版本字段防止旧 Worker 覆盖新状态。

可追问：B+Tree、最左前缀、覆盖索引、回表、隔离级别、幻读、唯一索引冲突、乐观锁、Outbox。

### Redis

业务入口：

- MD5 防重锁。
- SSE 任务进度缓存。
- Agent 限流、短期记忆和语义缓存。
- 字幕回流后的缓存失效。

话术：

> Redis 是性能层而不是事实层。它不可用时系统可降级到本地实现，关键任务、字幕和聊天历史仍落 MySQL。

可追问：SETNX、过期时间、WatchDog、缓存穿透/击穿/雪崩、双写一致性、Lua 原子性、热点 Key。

### Java 并发与 JVM

业务入口：

- H2/测试模式保留本地异步 DAG。
- Docker 模式由 MQ Consumer 触发同一解析 DAG。
- 分片和视频使用流式 IO。
- ffmpeg、whisper、yt-dlp 使用系统子进程。

话术：

> 长任务不会占用 HTTP 请求线程；解析执行与请求解耦。对大文件只做流式读写，避免整文件进入堆内存。

可追问：线程池参数、阻塞队列、拒绝策略、CompletableFuture、volatile、CAS、AQS、OOM、直接内存、jstack/jmap。

### Spring

业务入口：

- Controller/Service/Repository 分层。
- `@Transactional` 保证视频、任务、Outbox 同事务。
- `TraceFilter` 处理请求级 Trace。
- 全局异常返回结构化建议。
- Provider 使用策略式运行时切换。

可追问：事务传播、失效场景、代理、自调用、Filter/Interceptor/AOP、Bean 生命周期、依赖注入。

### RocketMQ

业务入口：

- Outbox Publisher。
- Consumer CAS 幂等。
- 发布失败退避。
- 消费重试、DLQ、人工重投。
- Trace ID 跨消息传递。

话术：

> 我没有把“发送消息”和数据库业务提交做成两个互不关联的动作，而是先把事件写进同一个 MySQL 事务，再由 Publisher 投递。这样 Broker 挂掉也不会丢任务。

可追问：至少一次、重复消费、顺序消费、事务消息、延迟消息、死信队列、最终一致性。

### 网络与操作系统

业务入口：

- SSE 推送任务进度。
- HTTP Range 播放视频。
- 分片上传和断点续传。
- ffmpeg/whisper/yt-dlp 子进程。
- 文件顺序 IO。

可追问：SSE 与 WebSocket、HTTP 长连接、Range、TCP 粘包、零拷贝、进程超时、stdout/stderr 阻塞。

### AI Agent / RAG

业务入口：

- 关键词召回 + Qdrant 向量召回。
- 本地或远程 rerank。
- 严格时间戳引用。
- 无视频证据时先声明，再通用回答。
- 字幕编辑回流。

话术：

> Agent 的重点不是“能回答”，而是“回答可以验证”。我让检索工具返回字幕片段、视频 ID 和时间戳，生成阶段只能基于引用解释视频内容。

可追问：Embedding、Cosine、召回率、TopK、rerank、上下文窗口、幻觉、语义缓存、评估指标。

### Docker / CI / 可观测

业务入口：

- API/Web 多阶段镜像。
- Compose 完整复现。
- Nginx 同源代理。
- GitHub Actions。
- JSON 日志与 `X-Trace-Id`。

话术：

> 我把可演示性当作功能的一部分，新机器可以通过 Compose 拉起完整链路，并用同一个 Trace ID 从 HTTP 请求追到 Outbox、MQ Consumer 和 DAG。

## 简历埋钩子

推荐写法：

- 基于 Spring Boot、MySQL Outbox 与 RocketMQ 构建长视频可靠异步解析链路，通过事件唯一约束、CAS 抢占、退避重试和 DLQ 重投保证任务不丢失且重复消费幂等。
- 设计分片上传、断点续传和 MD5 秒传流程，使用流式 IO、分片校验、Redis 防重与 MySQL 唯一索引支撑大文件可靠上传。
- 构建 Whisper ASR + OCR 保守融合、术语词库、字幕编辑和版本恢复闭环；字幕变化后自动回流结构化总结、Qdrant 向量索引和 Agent 语义缓存。
- 基于 MySQL、Redis、Qdrant、Embedding 与 Rerank 实现可追溯 RAG Agent，支持当前视频和多视频知识库问答，并返回可点击的视频时间戳引用。
- 实现 LLM/Embedding/Rerank Provider 的 AES-GCM 加密存储、mask 展示和密钥轮换，避免 API Key 明文落库与日志泄漏。
- 使用 DeepSeek 与 Apache POI 生成详细 Markdown、DOCX 和 PPTX，支持内容缓存和本地结构化降级。
- 建设 Docker Compose、GitHub Actions、JSON 日志与跨 HTTP/RocketMQ Trace，使完整系统可一键复现和快速定位故障。

不要写：

- “实现微服务架构”：当前是模块化单体。
- “实现多租户”：当前未完成登录隔离。
- “保证 ASR 100% 准确”：当前是保守融合和人工回流。
- “所有问答都由真实向量模型驱动”：未配置外部 Embedding 时使用本地 hash。
