# OmniVid Java 并发与线程池面试钩子作战手册

## 1. 面试总叙事

OmniVid 的 Java 并发不是为了堆概念，而是为了解决长视频解析的三个真实问题：

1. HTTP 请求不能同步等待 ffmpeg、ASR、总结生成。
2. 多个异步节点推进同一个任务时，状态不能乱序或回退。
3. 前端需要在视频解析期间持续看到进度，刷新或断线也能恢复。

一句话项目话术：

```text
OmniVid 里我没有让上传接口同步阻塞到解析完成，而是把长视频处理拆成本地轻量 DAG：上传接口只完成文件存储、MD5 去重、任务入库，然后把抽音频、ASR、总结生成交给 ThreadPoolTaskExecutor 异步执行。任务状态通过 MySQL version 乐观锁推进，最新进度写入 Redis，前端通过 SSE 观察状态变化。
```

回答模板：

```text
业务痛点 -> 并发模型 -> 线程安全边界 -> 失败和拒绝处理 -> 可验证结果 -> 八股关键词
```

## 2. 当前已实现并发落点

| 业务场景 | 当前代码落点 | 当前实现 | 可讲八股 |
| --- | --- | --- | --- |
| 本地解析 DAG | `VideoService.completeStoredUpload` | `processingExecutor.execute(...)` 投递后台任务 | 线程池、异步任务、拒绝策略、任务队列 |
| 线程池配置 | `ProcessingExecutorConfig` | core=2, max=4, queue=20, prefix=`omnivid-dag-` | 核心线程、最大线程、阻塞队列、线程命名 |
| DAG 节点推进 | `processStoredVideo` | 抽音频 -> ASR -> 总结，顺序执行 | DAG 编排、异常处理、幂等边界 |
| 状态一致性 | `ProcessingJobRepository.advance/fail` | `WHERE id = :id AND version = :version` | CAS 思想、乐观锁、并发更新 |
| SSE 进度推送 | `VideoService.progressStream` | `Thread.startVirtualThread` 每 500ms 推送 | 虚拟线程、长连接、阻塞等待 |
| 本地去重锁 | `LocalDedupeLockService` | `ConcurrentHashMap.newKeySet()` | 并发集合、本地锁、分布式边界 |
| 本地进度缓存 | `LocalProgressCacheService` | `ConcurrentHashMap<Long, ProgressSnapshot>` | 线程安全 Map、内存可见性 |
| 本地限流 | `LocalAgentRateLimiter` | `ConcurrentHashMap + AtomicInteger` | 原子类、固定窗口、compute 原子性 |

当前要诚实表达：

```text
项目现在已经有真实的本地异步 DAG 和线程池配置，但还没有把任务调度升级成 RocketMQ；也没有在 DAG 内使用 CompletableFuture 做复杂分支编排。当前链路是一条顺序 DAG，先用 ThreadPoolTaskExecutor 跑稳状态机、幂等和失败处理，后续再演进 MQ 或 CompletableFuture。
```

## 3. 本地轻量 DAG 任务队列

业务痛点：

长视频上传后要经历抽音频、ASR、字幕入库、总结生成等步骤。如果 HTTP 请求一直等到全部处理完成，用户会卡住，网关也可能超时。

当前设计：

- 上传接口先完成本地文件落盘和 MD5 计算。
- MySQL 创建 `video_asset` 和 `processing_job`。
- 如果不是重复视频，就提交后台任务。
- 后台任务在线程池里顺序执行：
  - `AUDIO_EXTRACTING`
  - `AUDIO_EXTRACTED`
  - `ASR_TRANSCRIBING`
  - `ASR_TRANSCRIBED`
  - `SUMMARY_GENERATED_AND_LOCAL_DAG_DONE`
- 每个阶段更新 MySQL 状态，并缓存 Redis 进度。

当前核心代码语义：

```text
processingExecutor.execute(() -> processStoredVideo(videoId, jobId, storedFile))
```

面试官可能追问：

- 为什么不能同步处理？
- 线程池参数怎么定？
- 队列为什么不能无界？
- 任务被拒绝怎么办？
- 异步任务异常怎么处理？
- 为什么当前没有用 MQ？
- 这算 DAG 吗，为什么不是简单顺序任务？

标准回答：

```text
视频解析是长耗时链路，上传接口只负责把视频资产和任务状态落库，然后把后续处理投递到 ThreadPoolTaskExecutor。这样用户能快速拿到 videoId/jobId，前端通过进度接口或 SSE 观察后台状态。当前 DAG 是顺序 DAG，先抽音频再 ASR，再生成总结，每个节点都会更新 processing_job 和 Redis 进度。这里可以展开线程池参数、队列容量、拒绝策略、异步异常和任务状态机。
```

为什么叫轻量 DAG：

```text
当前版本不是复杂工作流引擎，而是一个轻量 DAG：节点之间有明确依赖，ASR 必须依赖音频抽取结果，总结必须依赖字幕结果。MVP 用代码顺序表达依赖，优点是简单、可调试；后续如果有封面 OCR、语音分离、章节切分等并行节点，可以抽象成真正的 DAG 调度器。
```

简历钩子：

```text
使用 Spring `ThreadPoolTaskExecutor` 实现长视频解析轻量 DAG，将 ffmpeg 抽音频、whisper.cpp ASR 和总结生成从上传请求中解耦，并通过任务状态机向前端实时暴露处理进度。
```

## 4. 线程池参数

当前配置：

```text
corePoolSize = 2
maxPoolSize = 4
queueCapacity = 20
threadNamePrefix = omnivid-dag-
```

参数解释：

| 参数 | 当前值 | 面试话术 |
| --- | --- | --- |
| corePoolSize | 2 | 保持少量常驻解析线程，避免单机同时跑太多重任务 |
| maxPoolSize | 4 | 高峰时允许短暂扩容，但控制 CPU/磁盘/模型资源压力 |
| queueCapacity | 20 | 使用有界队列，避免大量长视频任务堆积导致 OOM |
| threadNamePrefix | `omnivid-dag-` | 方便日志、`jstack`、线程转储定位后台任务 |

面试官可能追问：

- CPU 密集型和 IO 密集型线程池怎么估算？
- 为什么 core 不是越大越好？
- 队列满了会怎样？
- 默认拒绝策略是什么？
- 线程池如何监控？
- 如果 ASR 卡住怎么办？

标准回答：

```text
OmniVid 的后台任务既有 ffmpeg 子进程，也有 ASR 模型调用，属于 CPU、磁盘 IO、外部进程混合型任务。单机 MVP 不能盲目把线程开很大，否则多个长视频同时解析会争抢 CPU、磁盘和内存。我当前配置 core=2、max=4、queue=20，是为了让本地演示稳定，同时保留线程池参数这个面试钩子。真正上线会结合机器核数、视频平均时长、ASR 耗时、队列等待时间和 CPU 使用率压测调整。
```

关于有界队列：

```text
视频解析任务很重，如果用无界队列，突发上传会把大量任务堆在内存里，严重时 OOM。有界队列能让系统在超过承载能力时及时暴露压力。后续可以在拒绝时把任务标记为 WAITING 或 FAILED，并提示用户稍后重试。
```

当前风险和改进：

```text
当前 ThreadPoolTaskExecutor 没有自定义拒绝策略，队列满时默认会抛拒绝异常。MVP 上传量小可以接受，但生产级应该捕获 TaskRejectedException，更新 processing_job 为 WAITING/FAILED，或者把任务交给 RocketMQ 做削峰。
```

八股关键词：

- `ThreadPoolExecutor`
- corePoolSize
- maximumPoolSize
- workQueue
- bounded queue
- rejection handler
- AbortPolicy
- CallerRunsPolicy
- 线程命名
- `jstack`

简历钩子：

```text
针对长视频解析任务配置有界线程池，通过核心线程数、最大线程数、队列容量和线程命名控制后台任务并发度，避免无界任务堆积导致内存风险。
```

## 5. 异步异常处理

业务痛点：

后台任务异常不能只在日志里消失。用户必须在页面看到失败状态，否则会以为系统卡住。

当前设计：

- `processStoredVideo` 捕获三类异常：
  - `AudioExtractionException`
  - `AsrTranscriptionException`
  - `RuntimeException`
- 失败后更新 `processing_job`。
- 视频状态标记为 `FAILED`。
- Redis 进度缓存同步更新。
- 前端进度区可以看到失败阶段。

面试官可能追问：

- 异步线程异常会不会影响主线程？
- `execute` 和 `submit` 异常处理有什么区别？
- 如何避免异常被吞？
- 失败任务如何重试？
- 状态写库失败怎么办？

标准回答：

```text
上传接口投递后台任务后就返回了，所以异步线程里的异常不会自动传回 HTTP 主线程。我在 DAG 外层捕获音频抽取、ASR 和兜底 RuntimeException，把失败阶段和错误信息写回 processing_job，并标记视频失败。这样前端不是一直显示处理中，而是能明确看到 AUDIO_EXTRACT_FAILED 或 ASR_TRANSCRIBE_FAILED。这里可以展开 execute/submit 异常差异、异步异常兜底、失败重试和死信队列演进。
```

`execute` 和 `submit` 差异：

```text
execute 提交 Runnable，异常通常会进入线程的 UncaughtExceptionHandler 或日志；submit 会把异常封装进 Future，调用 get 时才抛出。当前代码在 Runnable 内部自己 catch 并落库，避免异常只停留在线程池日志里。
```

重试演进：

```text
当前 MVP 有失败状态，没有自动重试。后续可以按 retry_count 做有限重试，失败超过阈值进入 FAILED；如果升级 MQ，可以把失败消息投递到延迟队列或死信队列。
```

八股关键词：

- 异步异常
- `execute`
- `submit`
- `Future.get`
- 失败重试
- 死信队列
- 幂等
- 降级

简历钩子：

```text
为异步解析 DAG 设计异常兜底机制，将 ffmpeg 和 ASR 失败映射为可观测任务状态，避免后台线程异常导致前端长时间停留在处理中。
```

## 6. 乐观锁与 CAS 思想

业务痛点：

后台 DAG、重试逻辑、人工重跑任务都可能操作同一个 `processing_job`。如果没有并发控制，可能出现状态乱序，例如任务已经 `DONE`，迟到的线程又写回 `ASR_TRANSCRIBING`。

当前设计：

- `processing_job` 有 `version` 字段。
- 更新时带上旧版本：

```sql
UPDATE processing_job
SET current_step = :step,
    progress = :progress,
    status = :status,
    version = version + 1
WHERE id = :id AND version = :version
```

面试官可能追问：

- 这和 CAS 有什么关系？
- 为什么不用 synchronized？
- 乐观锁失败怎么办？
- 乐观锁和悲观锁怎么选？
- ABA 问题怎么理解？

标准回答：

```text
任务状态推进用了数据库层面的乐观锁，思想和 CAS 类似：只有当前 version 仍然等于我读取时的 version，才允许更新；更新成功后 version + 1。如果其他线程先推进了状态，我这次 update 影响行数就是 0，说明状态已经变化，不能盲目覆盖。这里可以展开 CAS、乐观锁、ABA、行锁和事务隔离。
```

为什么不用 `synchronized`：

```text
synchronized 只能保护当前 JVM 里的临界区，多实例部署后无效；而任务状态最终落在 MySQL，所以状态推进的并发边界应该放在数据库更新条件里。Java 本地锁可以保护进程内对象，但不能作为跨实例任务状态的一致性保证。
```

乐观锁失败处理：

```text
当前代码每个阶段更新前都会重新读取最新 job，降低版本冲突概率。生产级如果 update 返回 0，需要重新读取状态并判断是否还能合法推进；如果已经完成，就直接跳过；如果状态冲突，就记录异常或进入重试。
```

八股关键词：

- CAS
- 乐观锁
- version
- ABA
- `synchronized`
- `ReentrantLock`
- AQS
- 行锁
- 事务隔离

简历钩子：

```text
基于 `processing_job.version` 实现任务状态乐观锁推进，避免异步 DAG 节点或重试逻辑并发写入导致状态回退和进度乱序。
```

## 7. 并发上传与本地锁边界

业务痛点：

多个用户可能同时上传同一个视频。如果同时进入入库和解析，会重复消耗磁盘、CPU 和 ASR 资源。

当前设计：

- docker profile 下使用 Redis 锁。
- local profile 下使用 `ConcurrentHashMap.newKeySet()` 做本地锁。
- MySQL `uk_video_md5` 唯一索引最终兜底。

本地锁实现语义：

```text
locks.add("video:lock:" + md5)
```

面试官可能追问：

- `ConcurrentHashMap` 为什么线程安全？
- `newKeySet()` 适合做什么？
- 本地锁和分布式锁区别？
- `synchronized`、`ReentrantLock`、Redis 锁怎么选？
- 多实例部署后会怎样？

标准回答：

```text
本地模式下我用 ConcurrentHashMap.newKeySet() 做轻量去重锁，适合单 JVM 开发和测试；docker profile 下切到 Redis 锁，才能跨实例防重。但无论本地锁还是 Redis 锁，都只是性能层，最终仍然靠 MySQL md5 唯一索引兜底，保证不会重复创建视频资产。
```

锁选择话术：

```text
如果只保护单 JVM 内共享对象，synchronized 或 ReentrantLock 就够了；如果要支持 tryLock、公平锁、可中断锁，可以用 ReentrantLock；如果要跨服务实例，就必须用 Redis、数据库或 MQ 这类外部协调机制。OmniVid 的视频去重是跨请求、未来跨实例的问题，所以不能只靠 JVM 锁。
```

八股关键词：

- `ConcurrentHashMap`
- `newKeySet`
- `synchronized`
- `ReentrantLock`
- AQS
- 分布式锁
- 原子性
- 可见性
- MySQL 唯一索引兜底

简历钩子：

```text
设计本地锁、Redis 分布式锁和 MySQL 唯一索引三层去重边界，区分单 JVM 并发控制、跨实例互斥和最终一致性兜底。
```

## 8. SSE 与虚拟线程

业务痛点：

前端需要持续看到解析进度。这个场景是服务端到浏览器的单向推送，不需要 WebSocket 的双向通信复杂度。

当前设计：

- 使用 `SseEmitter` 返回进度流。
- 每个连接启动一个虚拟线程。
- 每 500ms 读取一次进度。
- 状态不再是 `RUNNING` 时完成连接。
- emitter 超时时间 2 分钟。

面试官可能追问：

- 为什么用 SSE，不用 WebSocket？
- 虚拟线程适合什么场景？
- 虚拟线程和平台线程区别？
- SSE 连接多了会不会压垮服务？
- 轮询 Redis 会不会有压力？
- 断线重连怎么恢复？

标准回答：

```text
任务进度是服务端到前端的单向事件，不需要客户端高频双向通信，所以 SSE 比 WebSocket 更轻。当前每个 SSE 连接用虚拟线程承载，虚拟线程适合大量阻塞等待场景，例如 sleep、网络等待、长连接推送。每 500ms 读取一次进度，任务完成后主动 complete。断线后前端用 videoId 重连，后端从 Redis 进度缓存或 MySQL 状态机恢复最新进度。
```

虚拟线程边界：

```text
虚拟线程不是让 CPU 计算变快，它适合大量阻塞型任务，降低平台线程占用。OmniVid 的 SSE 推送主要是等待和短查询，适合虚拟线程；但 ffmpeg/ASR 这种重 CPU 或外部进程任务仍然交给受控线程池，不能无限开虚拟线程去跑重任务。
```

八股关键词：

- `SseEmitter`
- HTTP 长连接
- WebSocket 对比
- virtual thread
- platform thread
- 阻塞等待
- 断线重连
- Redis 进度缓存

简历钩子：

```text
基于 SSE 和 Java 虚拟线程实现视频解析进度推送，支持前端实时观察 DAG 状态变化，并在断线后通过 Redis/MySQL 恢复最新进度。
```

## 9. ConcurrentHashMap 与 AtomicInteger

当前本地限流实现：

- `ConcurrentHashMap<String, WindowCounter>` 保存用户窗口。
- `compute` 按 scope 原子更新窗口对象。
- `AtomicInteger.incrementAndGet()` 做窗口内计数。

面试官可能追问：

- `ConcurrentHashMap` 为什么不能用 `HashMap` 替代？
- `compute` 是否线程安全？
- `AtomicInteger` 底层是什么？
- `volatile` 能不能替代 `AtomicInteger`？
- 固定窗口限流有什么缺点？

标准回答：

```text
本地限流存在并发读写，普通 HashMap 在多线程下可能数据不一致，所以使用 ConcurrentHashMap。窗口对象更新用 compute，能保证同一个 key 的计算过程具备原子性；窗口内计数用 AtomicInteger，底层是 CAS 思想，避免 count++ 这种复合操作的竞态。volatile 只能保证可见性和禁止部分重排序，不能保证自增这种复合操作原子。
```

`volatile` 与原子性：

```text
volatile 能保证一个线程写入后其他线程尽快可见，但 count++ 包含读、加、写三个步骤，不是原子操作。AtomicInteger 的 incrementAndGet 通过 CAS 循环保证并发自增不会丢失。
```

八股关键词：

- `ConcurrentHashMap`
- `compute`
- `AtomicInteger`
- CAS
- `volatile`
- happens-before
- 固定窗口
- 线程安全集合

简历钩子：

```text
在本地限流和进度缓存中使用 `ConcurrentHashMap` 与 `AtomicInteger` 保证多线程访问安全，并区分本地并发控制与 Redis 跨实例限流边界。
```

## 10. CompletableFuture 怎么讲

当前项目没有在主链路里使用 `CompletableFuture`，这是面试时要诚实说明的点。

为什么当前没用：

```text
当前 DAG 是严格顺序链路：抽音频 -> ASR -> 总结。每一步都要落库并更新进度，用普通线程池 Runnable 更直观，也更容易处理每个阶段的失败状态。为了 MVP 简洁，没有引入 CompletableFuture。
```

什么时候适合用：

```text
如果后续加入并行节点，比如同时做封面 OCR、视频关键帧抽取、音频 ASR、章节切分，就可以用 CompletableFuture 做并行编排：allOf 等待多个节点完成，再进入总结生成。
```

面试回答：

```text
我了解 CompletableFuture 更适合异步任务编排，尤其是多个独立任务并行执行再汇总。OmniVid 当前链路依赖关系很强，ASR 必须等音频抽取，摘要必须等字幕，所以先用 ThreadPoolTaskExecutor + 显式状态机。后续多模态能力增加后，可以把 OCR、关键帧、ASR 拆成 CompletableFuture 并行节点。
```

八股关键词：

- `CompletableFuture`
- `supplyAsync`
- `thenApply`
- `thenCompose`
- `allOf`
- 异步异常
- 线程池隔离

简历钩子：

```text
针对当前顺序依赖型解析链路采用线程池显式推进状态机，并设计 CompletableFuture 并行多模态节点的演进方案。
```

## 11. 本地 DAG 到 RocketMQ 的演进

当前项目：

- 已实现本地线程池 DAG。
- 未实现 RocketMQ。
- MySQL 状态机和 Redis 进度缓存已经为 MQ 演进打好基础。

为什么第一阶段不用 MQ：

```text
MVP 阶段最重要的是先跑通上传、去重、抽音频、ASR、总结和进度展示的业务闭环。本地线程池能减少基础设施复杂度，更快验证状态机和幂等边界。等任务量上来，再把 dispatcher 替换成 RocketMQ 消费模型。
```

升级后怎么设计：

```text
上传接口创建 processing_job 后发送 `VideoProcessMessage(jobId, videoId)`。消费者按 jobId 维度执行节点，每个节点先检查当前状态和 version，保证重复消费不会破坏结果。失败后有限重试，超过阈值进入死信队列。进度仍然写 MySQL + Redis，前端接口不需要大改。
```

面试官可能追问：

- MQ 如何保证消息不丢？
- 重复消费怎么办？
- 顺序消费怎么保证？
- 消费失败怎么处理？
- 为什么有 MQ 还要状态机？

标准回答：

```text
MQ 解决的是削峰、解耦和异步投递，不替代业务幂等。即使用 RocketMQ，消费者也可能重复消费，所以每个节点仍然要查 processing_job 当前状态和 version，确保重复消息不会让状态回退。失败可以用重试队列、延迟消息或死信队列处理。
```

八股关键词：

- RocketMQ
- at-least-once
- 幂等消费
- 重复消费
- 顺序消息
- 死信队列
- 延迟消息
- 消息可靠性

简历钩子：

```text
以本地线程池 DAG 跑通视频解析状态机，并预留 RocketMQ 演进路径；通过 jobId 幂等键、version 乐观锁和失败重试机制保证异步消费一致性。
```

## 12. JVM 排查联动

并发问题经常会追到 JVM 排查。

OmniVid 场景：

- 线程池任务堆积：上传后长时间排队。
- ASR 或 ffmpeg 卡住：线程一直占用。
- 大字幕对象堆积：GC 压力上升。
- SSE 连接多：长连接数量上升。

排查话术：

```text
如果 OmniVid 解析变慢，我会先看线程池队列长度、活跃线程数和任务耗时，再用 jstack 看 omnivid-dag- 线程卡在哪一步。如果 CPU 飙高，看是 Java 线程还是 ffmpeg/whisper 子进程；如果内存上涨，用 jmap 或堆 dump 看字幕对象、缓存对象、上传缓冲是否异常增长。
```

常用命令：

```text
jstack
jmap
GC log
top / task manager
```

八股关键词：

- 线程转储
- 死锁
- 阻塞
- CPU 飙高
- OOM
- GC 日志
- 堆 dump

简历钩子：

```text
围绕长视频解析线程池建立可排查路径，通过线程命名、任务状态机和 JVM 工具定位任务堆积、子进程阻塞与内存异常问题。
```

## 13. 高频面试问答速记

### Q1：为什么上传接口不直接同步解析？

```text
ffmpeg 和 ASR 都是长耗时任务，同步等待会导致接口超时和用户体验差。上传接口只做文件存储、MD5 去重、任务入库，然后把后台解析交给线程池，前端通过 SSE 看进度。
```

### Q2：线程池队列为什么不能无界？

```text
长视频任务占用时间长、内存和磁盘资源重。如果无界队列接收突发上传，任务会在内存里越堆越多，最后可能 OOM。有界队列可以让系统及时暴露背压。
```

### Q3：当前线程池拒绝任务怎么办？

```text
当前 MVP 未自定义拒绝策略，默认拒绝会抛异常。生产级会捕获拒绝异常，把任务标记为 WAITING 或 FAILED，也可以升级 MQ 做削峰。
```

### Q4：异步任务异常怎么让前端知道？

```text
后台 Runnable 内部捕获异常，把失败阶段写入 processing_job，并同步 Redis 进度。前端 SSE 或详情接口读到 FAILED 状态后展示失败原因。
```

### Q5：为什么 version 乐观锁能防止状态乱序？

```text
因为更新时要求数据库里的 version 必须等于当前线程读到的旧 version。其他线程先更新后，version 已变化，当前线程 update 影响行数为 0，就不会覆盖新状态。
```

### Q6：volatile 能不能解决任务状态并发更新？

```text
不能。volatile 只能保证内存可见性，不保证复合操作原子性，也不能跨 JVM。任务状态最终在数据库，所以要用数据库 version 乐观锁控制。
```

### Q7：SSE 为什么用虚拟线程？

```text
SSE 连接主要是等待和定时推送，属于阻塞型长连接。虚拟线程适合大量阻塞等待，可以减少平台线程占用。但重 CPU 的 ASR 不适合无限开虚拟线程跑。
```

### Q8：为什么不用 CompletableFuture？

```text
当前链路是严格顺序依赖，普通线程池加显式状态机更简单。后续如果加入 OCR、关键帧、ASR 等并行节点，可以用 CompletableFuture.allOf 做并行编排。
```

### Q9：本地锁和 Redis 锁怎么区分？

```text
本地锁只保护单 JVM，适合开发模式和进程内共享数据；Redis 锁能跨实例；MySQL 唯一索引负责最终兜底。三者边界不同，不能互相替代。
```

### Q10：线程池卡住了怎么排查？

```text
先看任务状态停在哪个 step，再看 omnivid-dag- 线程的 jstack，判断卡在 ffmpeg、ASR、数据库还是文件 IO。如果 CPU 高看热点线程，如果内存高看堆 dump 和 GC 日志。
```

## 14. 简历埋钩子写法

可直接使用：

```text
使用 Spring `ThreadPoolTaskExecutor` 实现长视频解析轻量 DAG，将 ffmpeg 抽音频、whisper.cpp ASR、字幕入库和总结生成从上传请求中解耦，避免 HTTP 长时间阻塞。
```

```text
针对后台解析任务配置有界线程池，通过核心线程数、最大线程数、队列容量和线程命名控制并发度，并预留拒绝策略与 RocketMQ 削峰演进方案。
```

```text
基于 `processing_job.version` 实现任务状态乐观锁推进，避免异步节点、重试逻辑或重复触发导致任务状态回退和进度乱序。
```

```text
基于 SSE 和 Java 虚拟线程实现视频解析进度实时推送，结合 Redis 进度缓存和 MySQL 状态机支持断线恢复与降级查询。
```

```text
在本地模式下使用 `ConcurrentHashMap`、`AtomicInteger` 实现去重锁、进度缓存和限流计数，并在 Docker 模式下切换 Redis 支持跨实例并发控制。
```

更强版本：

```text
围绕长视频解析高耗时场景，设计线程池异步 DAG、数据库乐观锁状态机、Redis 进度缓存和 SSE 实时推送链路，重点解决上传请求阻塞、后台任务并发、状态一致性和进度可观测问题。
```

## 15. 30 秒项目表达

```text
OmniVid 的并发设计核心是把长视频解析从 HTTP 请求里拆出去。上传接口只完成文件落盘、MD5 去重和任务创建，后台通过 ThreadPoolTaskExecutor 执行本地轻量 DAG。每个节点推进 processing_job，并用 version 做乐观锁，避免多个线程或重试逻辑覆盖状态。进度写入 Redis，前端用 SSE 接收实时变化；SSE 使用虚拟线程承载长连接。当前是单机 MVP，后续可以把任务投递升级到 RocketMQ，但状态机和幂等边界可以复用。
```

## 16. 一句话防守边界

```text
当前已实现的是 ThreadPoolTaskExecutor 顺序 DAG、version 乐观锁、ConcurrentHashMap 本地并发组件和虚拟线程 SSE；CompletableFuture 并行编排、RocketMQ 消费和自定义拒绝策略属于二阶段演进点。
```
