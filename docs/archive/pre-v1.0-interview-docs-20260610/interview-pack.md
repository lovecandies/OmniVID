# OmniVid 1.0 面试包

## 30 秒项目介绍

OmniVid 是我做的一个 Java 后端主导的长视频 AI 解析系统。用户上传一个长视频后，后端会做 MD5 去重、创建异步解析任务、调用 ffmpeg 抽音频、用 whisper 做 ASR、生成时间轴字幕和结构化总结；然后 Agent 可以基于字幕、向量检索和 rerank 回答问题，并返回可点击的视频时间戳引用。MySQL 负责最终事实和状态一致性，Redis 负责防重、进度缓存、限流和短期记忆，Qdrant 负责字幕向量检索，DeepSeek 负责 LLM 生成。

## 简历写法

- 基于 Spring Boot 构建长视频语义解析工作台，完成本地视频上传、MD5 去重、异步 DAG 解析、ffmpeg 抽音频、whisper ASR、结构化总结和时间轴字幕跳转。
- 基于 MySQL 设计视频资产、解析任务、字幕片段、总结资产、聊天记录和知识库模型，通过唯一索引、联合索引和乐观锁保证幂等与状态一致性。
- 基于 Redis 实现上传防重复提交、任务进度缓存、Agent 限流、语义缓存和短期记忆，降低重复解析与重复推理成本。
- 使用 Java 线程池实现轻量 DAG 解析流水线，对 ffmpeg、ASR、总结生成等长耗时节点做状态流转、失败记录、补偿重试和运行时诊断。
- 接入 DeepSeek Chat、Qdrant 和本地 rerank，构建带工具调用轨迹的 Agent 问答链路，支持单视频问答、多视频知识库聚合问答、可点击时间戳引用和无证据通用回答兜底。
- 建设 Runtime、MySQL、Redis、ASR、RAG、Vector、Recovery 诊断面板，将项目运行状态转化为可演示、可排查、可面试追问的工程证据。

## 面试总模板

所有问题按这个结构回答：

```text
业务痛点 -> 技术方案 -> 八股关键词 -> 可验证结果
```

示例：

```text
同一个视频可能被多个用户重复上传，重复 ASR 和 LLM 成本很高。所以我用视频内容 MD5 做指纹，Redis SET NX 做并发窗口防重，MySQL md5 唯一索引做最终兜底。这里可以展开分布式锁、TTL、误删锁、唯一索引、事务冲突和幂等。验收时重复上传同一视频不会重复创建资产。
```

## MySQL 钩子

| 面试问题 | OmniVid 回答入口 |
| --- | --- |
| 唯一索引有什么用？ | `video_asset.uk_video_md5` 防止同一 MD5 重复入库 |
| 为什么 Redis 锁后还要 MySQL 唯一索引？ | Redis 是性能层，MySQL 是最终一致性兜底 |
| 联合索引怎么设计？ | 字幕查询一定先限定 `video_id`，再按 `start_ms` 排序或定位 |
| 覆盖索引和回表怎么讲？ | `idx_transcript_video_time_cover(video_id,start_ms,end_ms,segment_index)` 用于时间轴定位预留 |
| 乐观锁解决什么问题？ | `processing_job.version` 防止多个线程把任务状态推进乱序 |
| 深分页怎么优化？ | 视频库当前查最近 30 条，后续从 offset 改为游标分页 |

一句话：

```text
OmniVid 里的 MySQL 负责视频资产、任务状态、字幕、总结、聊天记录和知识库的最终事实；Redis 可以失败或过期，但 MySQL 的唯一约束、索引和事务是兜底。
```

## Redis 钩子

| 面试问题 | OmniVid 回答入口 |
| --- | --- |
| `SETNX` 怎么用？ | `video:lock:{md5}` 防重复上传 |
| 锁为什么要 TTL？ | 防止服务崩溃后死锁 |
| 锁 value 为什么要 token？ | 避免释放时删掉别人的锁 |
| 进度为什么放 Redis？ | SSE 断线重连或页面刷新能快速补偿 |
| 限流怎么做？ | `INCR + TTL` 固定窗口，后续可演进 Lua 令牌桶 |
| 缓存一致性怎么讲？ | Redis 只是临时状态，最终数据仍以 MySQL 为准 |

一句话：

```text
Redis 在 OmniVid 里只放高频短期状态：防重锁、进度缓存、限流、精确问题缓存和短期记忆。它提升性能，但不承载最终事实。
```

## Java 并发/JVM 钩子

| 面试问题 | OmniVid 回答入口 |
| --- | --- |
| 线程池参数怎么定？ | `omnividProcessingExecutor` core=2, max=4, queue=20 |
| 拒绝策略怎么讲？ | 1.0 使用 AbortPolicy，让失败可见并进入任务状态 |
| 为什么不用 HTTP 同步等待？ | ffmpeg、ASR、LLM 都是长耗时任务，必须异步化 |
| 大文件如何避免 OOM？ | Multipart 流式保存，MD5 流式计算，不把视频整体读入堆 |
| 子进程调用有什么坑？ | ffmpeg/whisper 需要超时、日志、标准输出处理和退出码判断 |
| CPU 飙高怎么排查？ | JVM 线程池面板、`jstack`、GC 日志、子进程日志一起看 |

一句话：

```text
OmniVid 的异步解析不是为了炫线程池，而是因为视频处理天然长耗时；我把任务状态落 MySQL，把短期进度放 Redis，把执行放线程池，面试时能讲并发、JVM 和操作系统子进程。
```

## Spring/事务钩子

| 面试问题 | OmniVid 回答入口 |
| --- | --- |
| `@Transactional` 什么时候失效？ | 任务创建、状态推进、重复插入兜底可以讲代理失效、自调用、异常类型 |
| 全局异常怎么做？ | `GlobalExceptionHandler` 返回结构化错误 |
| Filter、Interceptor、AOP 区别？ | 1.0 未做登录，可作为 2.0 权限设计展开 |
| Bean 生命周期怎么讲？ | LLM Provider、Embedding Provider、VectorStore 都是策略式 Bean |
| 事务传播怎么讲？ | 上传入库和异步任务提交要区分事务边界 |

一句话：

```text
Spring 在这个项目里主要负责 API 分层、异常统一、配置隔离和策略装配；事务边界要围绕视频入库、任务创建和状态推进来讲。
```

## MQ/异步架构钩子

当前 1.0 使用本地轻量 DAG，不接 RocketMQ。

面试口径：

```text
1.0 选择本地 DAG 是为了单机 demo 能闭环，同时把线程池、任务状态、幂等和失败重试讲清楚。生产化后可以把 DAG 节点拆成 RocketMQ 消息：上传完成发 AudioExtracted，ASR 完成发 TranscriptReady，总结完成发 SummaryReady。那时要重点处理可靠消息、重复消费、顺序消费、死信队列和幂等消费。
```

## 网络/操作系统钩子

| 面试问题 | OmniVid 回答入口 |
| --- | --- |
| SSE 和 WebSocket 怎么选？ | 进度推送是服务端单向，SSE 足够且更轻 |
| HTTP Range 是什么？ | 视频播放接口支持播放器按范围读取文件 |
| TCP 粘包是不是上传问题？ | HTTP 上传不直接讲 TCP 粘包，真正问题是分片、断点续传和超时 |
| 零拷贝怎么讲？ | 1.0 可作为文件下载/视频流优化方向，不硬说已实现 |
| 标准输出阻塞怎么讲？ | ffmpeg/whisper 子进程需要日志消费和超时控制 |

## AI Agent/RAG 钩子

| 面试问题 | OmniVid 回答入口 |
| --- | --- |
| RAG 是什么？ | 字幕切片检索后把证据传给 LLM，回答带引用 |
| Embedding 怎么做？ | 1.0 支持外部 provider 配置，默认 local hash fallback，向量进 Qdrant |
| Rerank 有什么用？ | 宽召回后按关键词和时间证据重排，减少无关引用 |
| 如何防幻觉？ | 有证据时引用回答；无证据时先说明未检索到，再通用回答 |
| 多视频问答怎么做？ | 知识库聚合多个视频 ID，检索跨视频字幕并返回 videoId/time range |
| Agent trace 怎么讲？ | InputGuardrail、Memory、TranscriptRetrieve、VectorRetrieve、Rerank、Citation、LLM、Persist |

一句话：

```text
OmniVid 的 Agent 不只是把问题丢给大模型，而是先调用字幕检索和向量检索工具，生成可追溯引用，再让 LLM 解释证据；这样可以把 RAG、工具调用、引用约束和防幻觉讲成一个闭环。
```

## 高频追问短答

1. 为什么叫知识引擎而不是视频总结工具？
   因为 1.0 不只生成总结，还保留视频、字幕、向量、引用、问答历史和知识库关系，能跨视频检索和追问。

2. DeepSeek 是不是唯一依赖？
   1.0 只把 DeepSeek 作为 Chat LLM，Embedding 独立配置；未配置 Embedding 时走本地 hash fallback 保证演示可用。

3. URL 导入为什么还会失败？
   平台 412/403 属于反爬和登录态问题，1.0 做诊断提示，不做绕过；生产化应走合规授权或用户 Cookie。

4. ASR 不准怎么办？
   1.0 已有简体化、乱码修复、技术词纠错、ASR 诊断、OCR 对齐入口和术语词库；2.0 再做更强模型、热词注入和人工校正工作流。

5. 这个项目最能体现 Java 后端能力的地方？
   大文件上传、异步任务状态机、MySQL/Redis 一致性分层、线程池和子进程治理、SSE 推送、可观测诊断、RAG/Agent 工程化落地。
