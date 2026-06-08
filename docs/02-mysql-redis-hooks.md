# 02. MySQL 与 Redis 面试钩子设计

## MySQL 设计目标

MySQL 不是简单存数据，而是负责：

- 业务事实的最终一致来源。
- 视频去重的最终兜底。
- 任务状态机的可靠推进。
- 字幕时间轴检索的索引支撑。
- Agent 引用来源的可追溯存证。

## 核心表设计

### users

用途：

- 轻量账号体系。
- 隔离个人视频库和知识库。

面试钩子：

- 唯一索引。
- 密码哈希。
- 登录限流。
- 用户上下文传递。

关键字段：

```sql
id, email, password_hash, nickname, created_at, updated_at
```

索引：

```sql
UNIQUE KEY uk_user_email (email)
```

### video_asset

用途：

- 存视频元数据。
- 根据 MD5 做内容级去重。

关键字段：

```sql
id, user_id, md5, original_name, storage_path, duration_ms,
status, created_at, updated_at, version
```

索引：

```sql
UNIQUE KEY uk_video_md5 (md5)
KEY idx_video_user_created (user_id, created_at)
```

面试钩子：

- 为什么 Redis 锁之外还要 MySQL 唯一索引？
  - Redis 是性能层，MySQL 是一致性兜底。
  - Redis 锁可能因为过期、网络抖动、服务重启失效。
  - 唯一索引能在并发写入时保证最终不会重复落库。

- 事务怎么设计？
  - 在一个事务内完成 `video_asset` 插入或查询已存在记录，并创建 `processing_job`。
  - 如果唯一索引冲突，捕获异常后回查已存在视频，返回秒传结果。

### processing_job

用途：

- 表达视频解析 DAG 的状态机。
- 支持失败重试和断点恢复。

关键字段：

```sql
id, video_id, current_step, status, progress,
retry_count, error_message, started_at, finished_at,
created_at, updated_at, version
```

索引：

```sql
KEY idx_job_video (video_id)
KEY idx_job_status_updated (status, updated_at)
```

面试钩子：

- 乐观锁：
  - 更新任务状态时带上 `version` 条件。
  - 避免多个 worker 同时推进同一个任务。

示例：

```sql
UPDATE processing_job
SET current_step = ?, status = ?, progress = ?, version = version + 1
WHERE id = ? AND version = ?;
```

- 行锁：
  - 需要强一致抢任务时可用 `SELECT ... FOR UPDATE`。
  - 面试中可对比乐观锁适合低冲突，行锁适合强互斥。

### transcript_segment

用途：

- 存字幕时间片。
- 支撑播放器点击跳转。
- 支撑 Agent 检索引用。

关键字段：

```sql
id, video_id, segment_index, start_ms, end_ms,
content, token_count, created_at
```

索引：

```sql
UNIQUE KEY uk_transcript_video_index (video_id, segment_index)
KEY idx_transcript_video_start (video_id, start_ms)
KEY idx_transcript_video_time_cover (video_id, start_ms, end_ms, segment_index)
```

面试钩子：

- B+Tree 最左前缀：
  - 查询某个视频某个时间点附近字幕，必须先等值命中 `video_id`，再对 `start_ms` 范围扫描。

- 覆盖索引：
  - 如果列表只需要 `video_id, start_ms, end_ms, segment_index`，可以避免回表。
  - 需要 `content` 时通常仍会回表，除非接受更大索引体积。

- EXPLAIN：
  - 目标是 `key = idx_transcript_video_start`。
  - `type` 至少应是 `range`。
  - `rows` 不应随全表线性增长。

### summary_asset

用途：

- 存结构化总结资产。

关键字段：

```sql
id, video_id, type, title, content_json, model_name,
prompt_version, created_at, updated_at
```

索引：

```sql
UNIQUE KEY uk_summary_video_type (video_id, type)
```

面试钩子：

- 幂等生成。
- 更新总结后的缓存删除。
- JSON 字段适合低频展示，不适合高频条件查询。

### chat_message 与 source_citation

用途：

- 存 AI Agent 问答历史。
- 存回答依据。

面试钩子：

- 长期记忆存 MySQL。
- 引用来源可审计。
- AI 回答不是黑盒文本，能回溯到视频时间轴。

## MySQL 高频追问应对

### 1. 为什么用唯一索引做 MD5 去重？

业务痛点：

- 同一视频可能被多人或同一用户重复上传。
- 只靠前端判断不可靠。

技术方案：

- Redis `SETNX` 做快速防抖。
- MySQL `uk_video_md5` 做最终一致性兜底。

八股关键词：

- 唯一索引。
- 幂等。
- 并发插入。
- 事务冲突。

可验证结果：

- 并发上传同一文件，最终 `video_asset` 只有一条记录。

### 2. 字幕时间轴为什么用 `(video_id, start_ms)`？

业务痛点：

- 用户点击总结引用，需要快速定位视频第几秒。

技术方案：

- 同一视频内按时间范围查字幕。
- `video_id` 等值过滤，`start_ms` 范围扫描。

八股关键词：

- B+Tree。
- 最左前缀。
- 范围查询。
- 回表。
- 覆盖索引。

可验证结果：

- `EXPLAIN` 命中 `idx_transcript_video_start`。

### 3. 任务状态乱序怎么办？

业务痛点：

- 异步节点可能重复执行或延迟返回。

技术方案：

- 每次状态更新校验当前状态和 version。
- 不允许从 `SUMMARY_DONE` 回退到 `ASR_RUNNING`。

八股关键词：

- 乐观锁。
- CAS 思想。
- 幂等更新。
- 事务隔离。

可验证结果：

- 重复触发同一节点不会破坏最终状态。

## Redis 设计目标

Redis 负责高频、短期、临时、加速型数据：

- 上传防重复提交。
- 分布式锁。
- 任务进度缓存。
- SSE 进度推送。
- 接口限流。
- 热点结果缓存。
- AI 语义缓存。

## Redis Key 设计

```text
video:lock:{md5}
video:dedupe:{md5}
job:progress:{jobId}
job:event:{jobId}
rate:upload:{userId}
rate:chat:{userId}
summary:asset:{videoId}:{type}
agent:semantic-cache:{knowledgeBaseId}
```

## Redis 高频追问应对

### 1. SETNX 锁有什么问题？

业务痛点：

- 多用户同时上传同一视频，会重复解析。

技术方案：

- `SET key value NX PX ttl`。
- value 用唯一 requestId，释放锁时校验 value。
- Redisson 可用 WatchDog 自动续期。

八股关键词：

- SETNX。
- 原子性。
- 锁过期。
- 误删锁。
- WatchDog。

可验证结果：

- 并发上传只创建一个 processing_job。

### 2. 缓存一致性怎么做？

业务痛点：

- 用户重新生成总结后，不能继续看到旧总结。

技术方案：

- Cache Aside。
- 读：先 Redis，miss 后查 MySQL，再回填缓存。
- 写：先更新 MySQL，再删除 Redis 缓存。

八股关键词：

- Cache Aside。
- 双写一致性。
- 延迟双删。
- 最终一致。

可验证结果：

- 更新总结后再次访问能看到新内容。

### 3. 限流为什么用 Lua？

业务痛点：

- 高频问答会拖垮本地模型或外部 LLM。

技术方案：

- Redis Lua 实现令牌桶。
- 判断、扣减、设置过期在一个脚本内完成。

八股关键词：

- Lua 原子性。
- 令牌桶。
- 滑动窗口。
- 固定窗口。

可验证结果：

- 连续快速请求超过阈值返回 429。

### 4. 语义缓存怎么讲？

业务痛点：

- “总结一下”和“这个视频讲了什么”语义相似，没必要重复推理。

技术方案：

- 问题转 embedding。
- Redis Vector 或向量库查相似历史问题。
- 相似度超过阈值直接返回历史答案和引用。

八股关键词：

- Embedding。
- 向量相似度。
- Cosine Similarity。
- 缓存穿透。
- 缓存击穿。
- 缓存雪崩。

可验证结果：

- 相似问题第二次回答延迟明显降低，并标记 cache hit。
