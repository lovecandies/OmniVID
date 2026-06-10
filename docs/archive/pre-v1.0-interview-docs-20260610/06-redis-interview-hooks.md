# OmniVid Redis 面试钩子作战手册

## 1. 面试总叙事

OmniVid 里的 Redis 不是为了“简历上写缓存”而引入，而是放在长视频解析链路的高频、短期、临时状态上：

1. 上传防重：同一个视频可能被多人或同一用户重复上传，Redis 先挡并发窗口。
2. 任务进度：视频解析是长耗时异步任务，前端刷新或 SSE 重连时要快速拿到最新进度。
3. Agent 限流：AI 问答成本高，必须限制短时间高频追问。
4. Agent 短期记忆：多轮追问需要保留最近问题，但不能把长期事实完全放进缓存。
5. 后续演进：总结缓存、Embedding 相似问题缓存、Redisson WatchDog、Lua 令牌桶都能自然接在当前链路上。

一句话项目话术：

```text
OmniVid 里 MySQL 承担最终事实和一致性，Redis 承担高频临时状态和性能层。我目前已经把 Redis 接到上传防重锁、任务进度缓存、Agent 问答限流、Agent 精确问题缓存和 Agent 短期记忆链路里，后续可以平滑演进到 Redisson 自动续期、Lua 令牌桶和 Embedding 相似问题缓存。
```

回答模板：

```text
业务痛点 -> Redis Key 设计 -> 原子性/TTL/一致性方案 -> MySQL 兜底 -> 可验证结果 -> 八股关键词
```

## 2. 当前已实现 Redis 落点

| 业务场景 | 当前类 | Redis Key | 数据结构 | TTL | 可讲八股 |
| --- | --- | --- | --- | --- | --- |
| 视频上传防重锁 | `RedisDedupeLockService` | `video:lock:{md5}` | String | 调用方传入，目前上传链路 30 秒 | `SET NX EX/PX`、分布式锁、误删锁、锁过期、幂等兜底 |
| 解析进度缓存 | `RedisProgressCacheService` | `omnivid:progress:{videoId}` | Hash | 30 分钟 | Hash、TTL、热点 Key、缓存穿透、SSE 重连补偿 |
| Agent 问答限流 | `RedisAgentRateLimiter` | `omnivid:agent:rate:{scope}:{window}` | String Counter | 12 秒 | `INCR`、固定窗口、过期策略、原子性、限流算法 |
| Agent 精确问题缓存 | `RedisAgentAnswerCache` | `omnivid:agent:semantic:{scope}:{questionHash}` | String JSON | 30 分钟 | 精确缓存、权限隔离、TTL、缓存污染 |
| Agent 短期记忆 | `RedisAgentShortTermMemory` | `omnivid:agent:memory:last-question:{videoId}` | String | 20 分钟 | 短期记忆、TTL、上下文窗口、记忆污染、MySQL 长期留痕 |
| Docker Redis | `infra/docker-compose.yml` | Redis 7.4 | AOF 开启 | 容器持久卷 | AOF、RDB、持久化、内存淘汰 |
| Docker Profile | `application-docker.yml` | `mode: redis` | Spring 配置 | - | Spring Boot 条件装配、环境隔离 |

当前要诚实表达：

```text
项目现在不是只写了 Redis 配置，而是已经在 docker profile 下真实走 Redis。已经落地的功能是 SETNX 防重锁、Hash 进度缓存、INCR 限流、Agent 精确问题缓存和 Agent 短期记忆。Redisson WatchDog、Lua 令牌桶、Embedding 相似问题缓存是我预留的演进方向，面试里会明确区分已实现和可扩展，不会把未实现说成已上线。
```

## 3. 上传防重锁

业务痛点：

长视频上传和 ASR 解析成本很高。如果两个请求同时上传同一个文件，只靠前端按钮防抖不可靠，也不能让两个请求都进入 ffmpeg 和 ASR。

当前设计：

- 上传阶段先计算文件 MD5。
- 使用 Redis `video:lock:{md5}` 做并发窗口防重。
- Value 使用随机 token，释放锁时校验 token，避免删掉别人的锁。
- MySQL `video_asset.md5` 唯一索引做最终一致性兜底。
- 如果 Redis 锁未拿到，会回查 MySQL 已有视频并返回已有任务。

当前代码形态：

```text
SET video:lock:{md5} {token} NX EX 30
```

面试官可能追问：

- `SETNX` 和 `SET key value NX PX ttl` 有什么区别？
- 为什么锁要设置过期时间？
- 为什么 value 要放唯一 token？
- 当前释放锁是不是完全安全？
- 锁过期了但任务还没结束怎么办？
- Redis 锁已经有了，为什么 MySQL 还要唯一索引？
- 多实例部署时本地锁为什么不够？

标准回答：

```text
同一个视频重复解析成本很高，所以我用内容 MD5 作为锁粒度。Redis 锁用于挡住短时间并发重复提交，Key 是 video:lock:{md5}，Value 是随机 token，并设置 30 秒 TTL，避免服务崩溃后死锁。释放时校验 token，降低误删别人锁的风险。最终一致性不依赖 Redis，而是靠 MySQL md5 唯一索引兜底。这里可以展开 SET NX、锁过期、唯一 token、误删锁、幂等和唯一索引冲突处理。
```

进阶回答：

```text
当前 MVP 版本释放锁是 get 后判断 token 再 delete，这在极端并发下不是严格原子。生产级可以用 Lua 把比较 token 和删除放在一个脚本里，或者直接切到 Redisson，用 WatchDog 解决长任务锁续期问题。但我这里锁只保护上传入库的短临界区，长耗时 ASR 不放在锁里，最终还有 MySQL 唯一索引兜底，所以 MVP 风险是可控的。
```

八股关键词：

- `SET NX EX/PX`
- 原子命令
- 唯一 token
- 误删锁
- TTL
- WatchDog
- Lua 原子释放
- 幂等
- MySQL 唯一索引兜底

简历钩子：

```text
基于 Redis `SET NX` 和视频 MD5 设计上传防重复提交链路，通过 token 校验释放锁，并结合 MySQL 唯一索引保证视频资产创建的最终幂等。
```

## 4. Agent 短期记忆

业务痛点：

Agent 多轮追问需要知道用户上一轮问了什么，但聊天记录又不能完全依赖缓存，因为缓存会过期、丢失，也不适合做审计事实。

当前设计：

- Redis Key：`omnivid:agent:memory:last-question:{videoId}`
- 数据结构：String
- Value：当前视频最近一条用户问题
- TTL：20 分钟
- MySQL `chat_message` 仍然保存完整问答历史，作为长期事实源。
- `GET /api/videos/{videoId}/agent/context` 会返回 `memorySource` 和 `shortTermQuestion`，前端展示短期记忆来源。
- 清空当前视频 Agent 历史时，会同时删除 Redis 短期记忆。

面试官可能追问：

- 为什么短期记忆放 Redis？
- Redis 记忆丢了怎么办？
- 为什么不能把所有聊天记录都放 Redis？
- 记忆污染怎么避免？

标准回答：

```text
我把“最近一条用户问题”作为短期记忆放 Redis，Key 按 videoId 隔离，并设置 20 分钟 TTL。它只负责提升多轮追问体验，不承担最终事实；完整问答仍然落 MySQL 的 chat_message。Redis miss 时可以从 MySQL 最近窗口兜底，清空历史时同时清理 Redis 记忆，避免旧上下文污染新会话。这里可以展开短期记忆、长期记忆、TTL、缓存一致性和记忆污染。
```

八股关键词：

- Short-term memory
- TTL
- Cache aside
- MySQL fact source
- Context window
- Memory pollution

简历钩子：

```text
基于 Redis 实现 Agent 当前视频短期记忆缓存，保存最近用户问题并设置 TTL，同时以 MySQL `chat_message` 作为长期事实源，支撑多轮上下文与清空历史的一致性。
```

## 5. 任务进度缓存

业务痛点：

视频解析包含上传确认、抽音频、ASR、总结生成等步骤，可能持续几十秒到数分钟。前端需要实时看到进度；用户刷新页面或 SSE 断线重连后，也要恢复到最新状态。

当前设计：

- MySQL `processing_job` 保存最终状态。
- Redis `omnivid:progress:{videoId}` 保存最新进度快照。
- Redis 使用 Hash 存 `jobId/currentStep/status/progress`。
- 每次任务状态推进后同时写入 Redis。
- 前端轮询或 SSE 重连时先读 Redis，miss 后回退查 MySQL 最新 job。
- TTL 为 30 分钟，避免已结束任务进度长期占内存。

Key 示例：

```text
omnivid:progress:42
```

Hash 示例：

```text
jobId=1001
currentStep=ASR_RUNNING
status=RUNNING
progress=55
```

面试官可能追问：

- 为什么进度适合放 Redis？
- Redis 进度和 MySQL 状态不一致怎么办？
- 为什么用 Hash，不用 String JSON？
- 过期时间怎么设计？
- 热点 Key 怎么处理？
- SSE 断线重连后怎么补偿？
- Redis 宕机是否影响任务最终结果？

标准回答：

```text
任务进度属于高频读取、短期有效的数据，所以放 Redis 更合适。MySQL 仍然保存 processing_job 的最终状态，Redis 只保存最新快照。前端 SSE 断线重连后可以根据 videoId 读取 omnivid:progress:{videoId} 补一帧最新状态；如果 Redis miss，就回查 MySQL 最新任务状态。这里可以展开缓存旁路、TTL、Hash、热点 Key、最终一致性和降级策略。
```

为什么用 Hash：

```text
进度对象字段固定且很少，Hash 可以按字段读写，调试时也方便用 HGETALL 直接查看。String JSON 也能做，但每次都要整体序列化和反序列化。当前字段少、结构稳定，用 Hash 更直接。
```

一致性回答：

```text
进度展示不要求强一致，允许短暂滞后。状态推进时先更新 MySQL，再写 Redis；如果 Redis 写失败，任务本身不失败，前端可以回查 MySQL。这样 Redis 是性能层，不是事实层。
```

八股关键词：

- Redis Hash
- TTL
- Cache Aside
- 最终一致性
- 降级回源
- 热点 Key
- SSE 重连
- MySQL 事实层

简历钩子：

```text
基于 Redis Hash 缓存视频解析进度快照，并结合 SSE 实现前端实时进度展示；Redis miss 时回退 MySQL 状态机，保证进度展示可降级。
```

## 6. Agent 问答限流

业务痛点：

Agent 问答会触发字幕检索、上下文拼接和模型推理，成本比普通 CRUD 高。用户短时间快速连点或脚本刷接口，会拖慢服务甚至打爆外部模型额度。

当前设计：

- Redis Key：`omnivid:agent:rate:{scope}:{window}`。
- 当前是固定窗口算法。
- 每 10 秒最多允许 5 次。
- 使用 Redis `INCR` 做计数。
- 第一次计数时设置 12 秒 TTL，给窗口边界留 2 秒缓冲。

Key 示例：

```text
omnivid:agent:rate:demo-user:178891234
```

面试官可能追问：

- `INCR` 是否原子？
- 固定窗口有什么问题？
- 为什么不直接用本地 `ConcurrentHashMap`？
- 过期时间为什么比窗口略长？
- 如何升级成滑动窗口或令牌桶？
- 为什么限流适合 Redis？

标准回答：

```text
Agent 问答是高成本接口，所以我在 Redis 做了按用户维度的固定窗口限流。Key 包含用户 scope 和时间窗口，使用 INCR 原子递增，第一次请求设置 TTL。超过阈值直接拒绝，避免重复消耗推理资源。本地限流只能保护单 JVM，多实例部署会失效，所以跨实例限流要放 Redis。这里可以展开 INCR 原子性、固定窗口缺陷、滑动窗口、令牌桶和 Lua 脚本。
```

固定窗口缺陷：

```text
固定窗口实现简单，但在窗口边界可能出现突刺。例如前一个窗口最后 1 秒打满 5 次，下一个窗口第 1 秒又打满 5 次，短时间内实际通过 10 次。生产环境可以升级为滑动窗口或令牌桶。
```

Lua 令牌桶演进话术：

```text
如果要做更平滑的限流，我会用 Redis Lua 实现令牌桶，把读取令牌数、按时间补充令牌、扣减令牌、设置 TTL 放在一个脚本里执行，保证整个判断和扣减过程原子。
```

八股关键词：

- `INCR`
- 固定窗口
- 滑动窗口
- 令牌桶
- Lua 原子性
- 多实例限流
- TTL
- 429 降级

简历钩子：

```text
基于 Redis `INCR + TTL` 实现 Agent 高频问答限流，限制高成本推理接口的突发流量，并预留 Lua 令牌桶升级路径。
```

## 7. Redis 和 MySQL 的边界

面试里最容易被追问的是：为什么有了 Redis，还要 MySQL？

核心边界：

| 数据类型 | 放 Redis | 放 MySQL |
| --- | --- | --- |
| 上传防重锁 | 是，挡并发窗口 | 是，唯一索引最终兜底 |
| 解析进度 | 是，读多写频繁，短期有效 | 是，任务状态最终事实 |
| Agent 限流计数 | 是，短期计数 | 否，通常不需要长期保存 |
| 字幕内容 | 否，当前直接 MySQL | 是，长期资产和引用来源 |
| 总结内容 | 可做缓存 | 是，长期结构化资产 |
| 聊天记录 | 可做短期记忆 | 是，长期历史与审计 |
| Agent 精确问题缓存 | 是，缓存重复问题答案 | 可存历史结果和引用 |

高质量回答：

```text
Redis 在 OmniVid 里是性能层和临时状态层，MySQL 是事实层。比如上传防重，Redis 锁能减少重复请求，但锁可能过期或丢失，最终仍然靠 MySQL 唯一索引保证不重复入库。再比如进度缓存，Redis 可以让前端更快拿到状态，但 Redis miss 或宕机时，仍然能从 MySQL processing_job 恢复。这个边界能避免把缓存当数据库用。
```

## 8. 缓存问题三件套

### 7.1 缓存穿透

问题：

查询一个不存在的视频进度或不存在的总结，每次都 miss，然后打到 MySQL。

OmniVid 应对：

```text
对进度这类短期状态，miss 后回查 MySQL；如果 MySQL 也没有，可以短 TTL 缓存空值，或者直接返回 404 并限制异常请求频率。因为 videoId 是后端生成的，也可以先做权限和合法性校验，减少随机 ID 穿透。
```

关键词：

- 空值缓存
- 布隆过滤器
- 参数校验
- 短 TTL

### 7.2 缓存击穿

问题：

某个热门视频总结缓存过期，大量用户同时请求，瞬间全部打到 MySQL 或 LLM。

OmniVid 应对：

```text
对于热门总结或热门 Agent 问题，可以用互斥锁重建缓存：只有一个请求回源生成，其他请求短暂等待或返回旧值。AI 总结成本高，更适合 stale-while-revalidate，先返回旧总结，再异步刷新。
```

关键词：

- 热点 Key
- 互斥锁
- 逻辑过期
- 旧值兜底

### 7.3 缓存雪崩

问题：

大量进度或总结缓存同一时间过期，导致下游被打满。

OmniVid 应对：

```text
缓存 TTL 加随机抖动，避免同一批 Key 同时过期。对非强一致内容设置多级降级：先返回 Redis，miss 查 MySQL，MySQL 压力高时返回旧快照或提示稍后重试。
```

关键词：

- TTL 随机化
- 分批过期
- 降级
- 熔断

## 9. Redisson WatchDog 演进点

当前项目没有引入 Redisson，使用的是 Spring `StringRedisTemplate`。

为什么当前没有强行上 Redisson：

```text
当前锁只保护上传入库这个短临界区，不包住 ffmpeg/ASR 这种长任务，所以 30 秒 TTL 基本够用。为了保持 MVP 简洁，我先用 StringRedisTemplate 实现 SET NX 锁，再用 MySQL 唯一索引兜底。
```

什么时候升级：

```text
如果后续把更长的任务调度抢占、跨实例 worker 任务领取也放到 Redis 锁里，就需要 Redisson WatchDog 自动续期，避免业务还没执行完锁先过期。或者用 MQ 消费模型减少长时间持锁。
```

面试话术：

```text
Redisson WatchDog 适合锁持有时间不确定的场景。它会在业务线程持锁期间自动续期，避免锁提前过期。但如果锁范围设计得太大，WatchDog 也只是缓解问题，不是替代幂等。OmniVid 的核心思路仍然是短锁保护并发窗口，长任务靠状态机和幂等重试。
```

## 10. AI 语义缓存演进点

当前项目的 Agent 已经能基于字幕和时间戳做问答，并已接入精确问题缓存。同一 scope 下重复提问同一句问题会返回 `cacheHit=true`；Embedding 相似问题缓存是下一步升级。

适合做语义缓存的场景：

- “这个视频讲了什么？”
- “总结一下核心观点。”
- “MySQL 和 Redis 在项目里怎么用？”
- “这段视频有哪些面试亮点？”

不适合缓存的场景：

- 用户带强个人上下文的问题。
- 权限范围不同的知识库问题。
- 要求最新实时状态的问题。
- 低置信度或引用不足的问题。

Key 设计示例：

```text
omnivid:agent:semantic:{knowledgeBaseId}:{questionHash}
```

Value 结构示例：

```json
{
  "answer": "回答正文",
  "citations": [
    {"videoId": 42, "startMs": 12000, "endMs": 36000}
  ],
  "model": "local-rag",
  "createdAt": "2026-06-07T10:00:00+08:00"
}
```

面试话术：

```text
语义缓存不能只用问题原文做精确匹配，因为“总结一下”和“这个视频讲了什么”语义相近但文本不同。当前 MVP 先用 questionHash 做精确缓存，后续升级为 Embedding 相似度缓存：先向量召回相似历史问题，再判断相似度阈值，命中后返回历史答案和原始 citation。为了防幻觉，缓存答案也必须带时间戳引用，并且按 knowledgeBaseId 做权限隔离。
```

八股关键词：

- 语义缓存
- Embedding
- 向量相似度
- Redis Vector
- 权限隔离
- 缓存污染
- 引用可追溯

简历钩子：

```text
接入 Agent 精确问题缓存，按当前视频或知识库 scope 隔离缓存 Key，并要求缓存答案保留视频时间戳引用，降低重复检索与推理成本。
```

## 11. Redis 持久化与内存淘汰

当前 Docker Redis：

```text
redis-server --appendonly yes
```

可以讲：

```text
我在本地 Docker 环境开启了 AOF，主要是为了开发演示时 Redis 重启后尽量保留状态。但业务设计上没有把 Redis 当最终事实库：锁、限流、进度都是可重建或可过期数据。真正需要长期保存的视频、字幕、总结、聊天记录仍然落 MySQL。
```

面试追问：

- RDB 和 AOF 区别？
- AOF 一定不丢数据吗？
- Redis 内存满了怎么办？
- 进度 Key 用什么淘汰策略？

回答要点：

```text
RDB 是快照，恢复快但可能丢最近一段数据；AOF 记录写命令，数据更完整但文件更大、恢复较慢。OmniVid 即使 Redis 数据丢失也不会破坏最终结果，因为任务状态在 MySQL。内存淘汰上，进度和限流 Key 都有 TTL，可以配合 volatile-lru 或 allkeys-lru；更关键的是不要把无 TTL 的临时 Key 写进去。
```

## 12. 黑盒验证路径

### 11.1 确认 Redis 容器

```powershell
docker exec omnivid-redis redis-cli PING
```

期望：

```text
PONG
```

### 11.2 查看任务进度缓存

上传一个视频后执行：

```powershell
docker exec omnivid-redis redis-cli KEYS "omnivid:progress:*"
```

拿到某个 Key 后：

```powershell
docker exec omnivid-redis redis-cli HGETALL omnivid:progress:{videoId}
```

期望能看到：

```text
jobId
currentStep
status
progress
```

### 11.3 查看 Agent 限流 Key

连续快速追问 Agent 后执行：

```powershell
docker exec omnivid-redis redis-cli KEYS "omnivid:agent:rate:*"
```

期望：

```text
能看到当前窗口的计数 Key，并且 TTL 会自动过期。
```

### 11.4 查看上传锁

上传过程中执行：

```powershell
docker exec omnivid-redis redis-cli KEYS "video:lock:*"
```

期望：

```text
短时间内可能看到 video:lock:{md5}，上传临界区结束后自动删除或 TTL 到期。
```

## 13. 高频面试问答速记

### Q1：Redis 锁为什么要加过期时间？

```text
防止服务拿到锁后宕机导致死锁。过期时间要覆盖临界区执行时间，长任务不应该一直靠 Redis 锁硬撑，而应该拆成状态机、幂等和重试。
```

### Q2：Redis 锁为什么要存 token？

```text
避免误删别人的锁。比如 A 拿到锁后超时，锁过期，B 重新拿到同一个 Key。A 执行完如果直接 delete，就会删掉 B 的锁。所以释放时要比较 token。
```

### Q3：比较 token 再删除为什么建议用 Lua？

```text
因为 get 和 delete 是两个命令，中间可能发生锁过期和新锁写入。Lua 能把比较和删除放在 Redis 单线程执行的一段脚本里，保证原子性。
```

### Q4：Redis 是单线程，为什么还快？

```text
Redis 主要操作在内存里，命令执行很快；单线程避免了多线程锁竞争；同时使用 IO 多路复用处理大量连接。新版本也有网络 IO 线程，但命令执行核心仍可按单线程模型理解。
```

### Q5：进度缓存和数据库不一致怎么办？

```text
进度展示允许短暂最终一致。MySQL 是事实层，Redis 是快照层。Redis miss 或异常时回查 MySQL；Redis 旧值最多影响展示，不影响任务最终状态。
```

### Q6：限流为什么不用本地内存？

```text
本地内存只能限制单个 JVM，服务多实例后每个实例都有自己的计数，会被绕过。Redis 是集中计数点，更适合跨实例限流。
```

### Q7：固定窗口限流有什么缺点？

```text
窗口边界会有流量突刺。生产可以升级为滑动窗口或令牌桶，使用 Redis ZSet 或 Lua 脚本实现更平滑的限流。
```

### Q8：缓存穿透、击穿、雪崩在项目里怎么讲？

```text
穿透对应不存在的视频或总结反复查询；击穿对应热门视频总结过期后大量请求同时回源；雪崩对应大量进度或总结 Key 同时过期。应对分别是空值缓存/参数校验、互斥重建/逻辑过期、TTL 随机化/降级。
```

### Q9：Redis 宕机会不会影响上传和解析？

```text
会影响防重性能和进度读取速度，但不应该影响最终正确性。上传去重有 MySQL 唯一索引兜底，任务状态在 MySQL，Redis 异常时可以降级到数据库查询。
```

### Q10：Redis Key 怎么设计才好？

```text
Key 要能体现业务域、对象 ID 和作用，避免过长但要可读。OmniVid 里例如 video:lock:{md5}、omnivid:progress:{videoId}、omnivid:agent:rate:{scope}:{window}。临时 Key 必须有 TTL。
```

## 14. 简历埋钩子写法

可直接使用：

```text
基于 Redis `SET NX` 实现视频 MD5 上传防重复提交，通过随机 token 校验释放锁，并结合 MySQL 唯一索引保证视频资产创建幂等。
```

```text
使用 Redis Hash 缓存长视频解析进度快照，并结合 SSE 推送实现前端实时进度展示；Redis miss 时回源 MySQL 状态机，保证进度查询可降级。
```

```text
基于 Redis `INCR + TTL` 实现 Agent 高频问答固定窗口限流，控制模型推理接口突发流量，并预留 Lua 令牌桶升级方案。
```

```text
围绕 Redis 缓存穿透、击穿、雪崩设计视频总结和 Agent 语义缓存演进方案，通过空值缓存、互斥重建、TTL 抖动和引用约束降低重复推理成本。
```

更强版本：

```text
在 OmniVid 长视频解析链路中，将 Redis 定位为高频临时状态层，承担上传防重锁、解析进度缓存、Agent 限流和 Agent 精确问题缓存；将 MySQL 定位为事实层，保证任务状态、字幕资产和问答引用的最终一致性。
```

## 15. 30 秒项目表达

```text
我在 OmniVid 里使用 Redis 解决三类真实问题：第一是上传防重，用 video:lock:{md5} 做 SET NX 锁，减少同一视频重复解析；第二是进度缓存，用 Hash 保存 currentStep/status/progress，让前端 SSE 断线后可以快速恢复；第三是 Agent 限流，用 INCR + TTL 限制高成本问答接口的突发流量。Redis 只做性能层，最终事实仍然落 MySQL，所以 Redis 异常不会破坏视频资产和任务状态的一致性。
```

## 16. 一句话防守边界

```text
当前已实现的是 Spring StringRedisTemplate 版本的 SETNX 防重锁、Hash 进度缓存、INCR 限流和 Agent 精确问题缓存；Redisson WatchDog、Lua 令牌桶、Redis Vector 相似问题缓存属于清晰的二阶段升级点。
```
