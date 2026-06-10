# OmniVid MySQL 面试钩子作战手册

## 1. 面试总叙事

OmniVid 里的 MySQL 不是单纯存数据，而是承担三件事：

1. 最终事实：视频资产、解析任务、字幕、总结、聊天记录最终都落 MySQL。
2. 一致性兜底：Redis 可以做锁和缓存，但视频 MD5 去重、任务状态流转最终靠 MySQL 唯一索引、事务和 version 兜底。
3. 查询效率：视频列表、字幕时间轴、任务查询都能对应索引设计和 `EXPLAIN`。

面试回答模板：

```text
业务痛点 -> 表结构/索引设计 -> 并发一致性方案 -> 可验证结果 -> 八股关键词
```

一句话项目话术：

```text
OmniVid 是长视频 AI 解析系统，MySQL 负责视频资产、任务状态机、字幕时间轴、总结资产和聊天记录的最终一致性。Redis 做性能层，MySQL 做事实层；所以我在视频 MD5 去重、任务乐观锁、字幕联合索引、总结唯一约束上都设计了可追问的八股钩子。
```

## 2. MySQL 应用全景

| 业务场景 | 表 | 关键字段/索引 | 代码落点 | 可讲八股 |
| --- | --- | --- | --- | --- |
| 用户唯一身份 | `users` | `uk_user_email(email)` | 当前预留表 | 唯一索引、登录注册幂等 |
| 视频资产入库 | `video_asset` | `uk_video_md5(md5)` | `VideoRepository.insert/findByMd5` | 唯一索引、幂等、并发上传 |
| 视频列表 | `video_asset` | `idx_video_user_created(user_id, created_at)` | `VideoRepository.list` | 联合索引、排序、深分页 |
| 视频状态 | `video_asset` | `status`, `version` | `markReady/markFailed` | 状态一致性、乐观锁扩展 |
| 异步任务状态机 | `processing_job` | `idx_job_video(video_id)`, `version` | `ProcessingJobRepository` | 乐观锁、CAS 思想、事务 |
| 任务监控/补偿 | `processing_job` | `idx_job_status_updated(status, updated_at)` | 后续任务扫描扩展 | 失败重试、定时补偿 |
| 字幕时间轴 | `transcript_segment` | `idx_transcript_video_start(video_id, start_ms)` | `TranscriptRepository.list/search` | 联合索引、B+Tree、最左前缀 |
| 字幕去重入库 | `transcript_segment` | `uk_transcript_video_index(video_id, segment_index)` | `insertBatch/existsByVideoId` | 批量写入、唯一约束、幂等 |
| 覆盖查询预留 | `transcript_segment` | `idx_transcript_video_time_cover(video_id, start_ms, end_ms, segment_index)` | 时间轴定位扩展 | 覆盖索引、回表 |
| 总结资产 | `summary_asset` | `uk_summary_video_type(video_id, type)` | `SummaryRepository` | 资产幂等、唯一索引 |
| Agent 追问记录 | `chat_message` | `video_id`, `created_at` | `ChatMessageRepository` | 聊天历史、冷热数据、索引扩展 |

## 3. 视频 MD5 去重

业务痛点：

同一个视频可能被多个用户重复上传。如果每次都重新 ASR 和总结，会浪费 CPU、磁盘、模型推理成本。

当前设计：

- 上传时先流式计算 MD5。
- Redis/Local lock 做第一层防重。
- MySQL `video_asset.md5` 上有唯一索引 `uk_video_md5` 做最终兜底。
- 查询重复视频使用 `SELECT * FROM video_asset WHERE md5 = :md5`。

面试官可能追问：

- 为什么 Redis 锁还要 MySQL 唯一索引？
- 并发上传同一个视频会不会插入两条？
- 唯一索引底层是什么结构？
- 唯一索引冲突如何处理？
- MD5 冲突怎么办？

回答话术：

```text
业务上同一个视频重复解析成本很高，所以我把 MD5 作为内容指纹。Redis 锁用于减少并发窗口内的重复请求，MySQL 的 uk_video_md5 是最终一致性兜底。即使 Redis 锁失效或服务重启，只要两个请求同时插入同一个 md5，MySQL 唯一索引也会阻止脏数据。这里可以展开唯一索引、B+Tree、幂等设计和并发冲突处理。
```

简历钩子：

```text
基于视频内容 MD5 设计上传去重链路，通过 Redis 锁降低并发重复提交，并使用 MySQL `uk_video_md5` 唯一索引做最终幂等兜底，避免重复创建视频资产和重复触发 ASR 任务。
```

## 4. 任务状态机与乐观锁

业务痛点：

视频解析是长耗时异步任务，阶段包括上传确认、抽音频、ASR、总结生成。不能让 HTTP 请求一直阻塞，也不能让任务状态乱序。

当前设计：

- `processing_job` 保存任务状态。
- 核心字段：`current_step`, `status`, `progress`, `retry_count`, `error_message`, `version`。
- 更新任务时使用：

```sql
UPDATE processing_job
SET current_step = :step,
    progress = :progress,
    status = :status,
    version = version + 1
WHERE id = :id AND version = :version
```

面试官可能追问：

- 为什么用乐观锁？
- `WHERE id = ? AND version = ?` 解决什么问题？
- 乐观锁失败怎么办？
- 事务隔离级别如何影响任务状态？
- 为什么不用悲观锁？

回答话术：

```text
解析任务是异步 DAG 推进的，每个节点只允许基于当前版本推进状态。所以我在 processing_job 上加了 version 字段，更新时带上旧 version，成功后 version + 1。这样如果多个线程或重试逻辑同时更新同一个任务，只有一个能成功，避免状态回退或乱序。这里可以展开乐观锁、CAS 思想、行锁、事务隔离级别和失败重试。
```

简历钩子：

```text
基于 MySQL `processing_job` 构建长视频解析任务状态机，使用 `version` 乐观锁控制任务阶段流转，保证抽音频、ASR、总结生成等异步节点的状态一致性。
```

## 5. 字幕时间轴检索

业务痛点：

用户点击总结或字幕时，播放器要秒级跳转到对应片段；Agent 回答也必须带来源时间戳。

当前设计：

- 字幕表：`transcript_segment`
- 时间字段：`start_ms`, `end_ms`
- 顺序字段：`segment_index`
- 关键索引：

```sql
KEY idx_transcript_video_start (video_id, start_ms)
KEY idx_transcript_video_time_cover (video_id, start_ms, end_ms, segment_index)
```

面试官可能追问：

- 为什么联合索引是 `(video_id, start_ms)`？
- 最左前缀原则怎么体现？
- 什么是回表？
- 什么是覆盖索引？
- 字幕关键词搜索为什么还不够好？
- 如果字幕千万级怎么办？

回答话术：

```text
字幕查询有明显的业务前缀：一定先限定 video_id，再按 start_ms 找时间点或排序。所以联合索引设计成 video_id + start_ms，符合最左前缀。时间轴定位只需要 start_ms、end_ms、segment_index 等字段时，可以走覆盖索引减少回表。关键词 LIKE 只是 MVP，后续会引入全文索引或向量检索。这里可以展开 B+Tree、联合索引顺序、覆盖索引、EXPLAIN 和回表。
```

简历钩子：

```text
针对字幕时间轴定位建立 `video_id + start_ms` 联合索引，并预留覆盖索引优化播放器点击跳转和 Agent 时间戳引用查询，通过 `EXPLAIN` 验证查询命中索引。
```

## 6. 视频列表与分页

业务痛点：

用户进入知识库需要快速看到最近上传的视频。后续视频数量变多后，列表不能全表扫描。

当前设计：

```sql
KEY idx_video_user_created (user_id, created_at)
```

代码查询：

```sql
SELECT * FROM video_asset
WHERE user_id = :userId
ORDER BY created_at DESC, id DESC
LIMIT 30
```

面试官可能追问：

- 为什么要建 `user_id, created_at` 联合索引？
- 深分页怎么优化？
- `ORDER BY created_at DESC, id DESC` 如何走索引？
- 需要不要把 `id` 加进索引？

回答话术：

```text
视频列表是典型的按用户隔离查询，所以 user_id 是高频过滤条件，created_at 是排序字段。MVP 先用 LIMIT 30 展示最近视频，后续如果出现深分页，会从 offset 分页改成游标分页，例如 where created_at < lastCreatedAt 或 id < lastId。这里可以展开联合索引、排序优化、深分页和覆盖索引。
```

简历钩子：

```text
为个人视频知识库列表设计 `user_id + created_at` 联合索引，支持按用户维度快速查询最近视频，并预留基于游标的深分页优化方案。
```

## 7. 总结资产幂等

业务痛点：

同一个视频会生成不同类型的资产：核心观点、面试钩子、博客、会议纪要。重复生成时不能插入多份同类型脏数据。

当前设计：

```sql
CONSTRAINT uk_summary_video_type UNIQUE (video_id, type)
```

代码逻辑：

- 插入前先 `existsByVideoId`。
- 唯一约束保证同一个视频同一种总结类型只有一份。

面试官可能追问：

- 为什么不是只靠代码判断？
- 唯一约束和业务幂等是什么关系？
- 如果要支持版本化总结怎么办？

回答话术：

```text
总结资产是按 video_id + type 唯一的，比如同一个视频只保留一份 CORE_POINTS 和一份 INTERVIEW_HOOKS。代码层先判断是否存在，MySQL 唯一约束再兜底。后续如果要支持多版本总结，可以把 prompt_version 或 model_name 纳入唯一键，或者新建 summary_version 表。
```

简历钩子：

```text
设计 `summary_asset` 资产表，使用 `video_id + type` 唯一约束保证总结生成幂等，并为后续多模型、多版本总结扩展预留字段。
```

## 8. Agent 聊天记录

业务痛点：

用户追问视频内容时，需要保存用户问题、AI 回答和时间戳引用，后续可用于会话恢复、审计和 Agent 记忆。

当前设计：

- 表：`chat_message`
- 字段：`video_id`, `role`, `content`, `citation`, `created_at`
- 写入点：`ChatMessageRepository.insert`
- 查询点：`ChatMessageRepository.listRecentByVideoId`
- 清空点：`ChatMessageRepository.deleteByVideoId`
- 上下文窗口：`AgentService.ask` 优先读取 Redis/local 短期记忆，miss 后用最近 6 条 MySQL 记录兜底。

面试官可能追问：

- 聊天记录会不会很大？
- 怎么做冷热分离？
- 多轮上下文如何取？
- 为什么不全部放 Redis？

回答话术：

```text
Redis 更适合短期会话和高频缓存，比如当前视频最近一条用户问题；但聊天记录属于长期事实数据，所以最终仍然落 MySQL。MVP 按 video_id 存用户问题、AI 回答和 citation，并在前端切换视频时查询最近记录恢复问答历史；当前视频问答前优先读 Redis/local 短期记忆，miss 后用 MySQL 最近窗口兜底。当前也支持按 videoId 清空历史，并同步清理短期记忆。这里可以展开冷热数据、索引设计、短期记忆、长期记忆、上下文裁剪和数据生命周期。
```

简历钩子：

```text
基于 MySQL `chat_message` 实现 Agent 问答留痕和最近历史恢复，并结合 Redis 短期记忆支撑轻量多轮上下文窗口，保存用户问题、AI 回复和视频时间戳引用，为审计追踪和长期记忆扩展打基础。
```

## 9. 常见 MySQL 八股追问总表

| 面试问题 | 项目落点 | 回答关键词 |
| --- | --- | --- |
| 你项目里 MySQL 解决了什么核心问题？ | 视频事实层、任务状态、字幕索引 | 最终一致性、索引、状态机 |
| Redis 已经做锁了，为什么还要唯一索引？ | `uk_video_md5` | Redis 性能层，MySQL 兜底 |
| 乐观锁怎么实现？ | `processing_job.version` | `WHERE id AND version`, version + 1 |
| 什么场景用悲观锁？ | 任务抢占、强一致扣减 | `SELECT ... FOR UPDATE`，行锁 |
| 联合索引怎么设计？ | `video_id + start_ms` | 等值字段在前，范围/排序字段在后 |
| 什么是覆盖索引？ | `idx_transcript_video_time_cover` | 查询字段都在索引里，减少回表 |
| 深分页怎么优化？ | 视频列表 | 游标分页，避免大 offset |
| 慢 SQL 怎么排查？ | 字幕查询/列表查询 | `EXPLAIN`, key, rows, Extra |
| 事务失效怎么排查？ | 上传入库 + 任务创建 | `@Transactional`, 自调用, 异常类型 |
| 如果插入一半失败怎么办？ | 视频入库、任务创建 | 事务边界、补偿、状态机 |
| 大字段怎么处理？ | `summary_asset.content_json` | LONGTEXT、冷热分离、对象存储 |
| 数据量大了怎么分表？ | 字幕/聊天记录 | 按 video_id 或 created_at 分区/分表 |

## 10. 面试演示路径

黑盒演示：

1. 上传一个本地视频。
2. 页面看到任务进度从上传、抽音频、ASR、总结生成到 DONE。
3. 页面左侧“面试钩子”看到：
   - `uk_video_md5`
   - `optimistic version`
   - `video_id + start_ms`
   - `uk_summary_video_type`
4. 进入 MySQL 验证：

```powershell
docker exec omnivid-mysql mysql -uomnivid -pomnivid_pass -D omnivid -e "SELECT id, md5, status, version FROM video_asset ORDER BY id DESC LIMIT 3;"
docker exec omnivid-mysql mysql -uomnivid -pomnivid_pass -D omnivid -e "SELECT id, video_id, current_step, status, progress, version FROM processing_job ORDER BY id DESC LIMIT 3;"
docker exec omnivid-mysql mysql -uomnivid -pomnivid_pass -D omnivid -e "EXPLAIN SELECT * FROM transcript_segment WHERE video_id = 3 ORDER BY start_ms ASC LIMIT 5;"
```

预期验证点：

- `video_asset.status = READY`
- `processing_job.status = DONE`
- `processing_job.version` 随阶段推进递增
- `EXPLAIN.key` 命中 `idx_transcript_video_start`

## 11. 简历写法

强版本：

```text
基于 MySQL 设计长视频解析事实层，覆盖视频资产、任务状态机、字幕时间轴、总结资产和 Agent 聊天记录；通过唯一索引、联合索引和乐观锁保障上传去重、异步任务流转和字幕检索的一致性与查询效率。
```

拆分版本：

```text
基于 `uk_video_md5` 唯一索引实现视频内容级幂等，结合 Redis 锁降低并发重复上传，避免重复 ASR 和重复总结生成。
```

```text
设计 `processing_job` 任务状态机，使用 `version` 乐观锁推进抽音频、ASR、总结生成等异步阶段，避免并发更新导致状态乱序。
```

```text
针对字幕时间轴定位建立 `video_id + start_ms` 联合索引，并通过 `EXPLAIN` 验证查询计划，支撑播放器点击跳转和 Agent 时间戳引用。
```

```text
设计 `summary_asset` 与 `chat_message` 表保存结构化总结和 Agent 追问记录，使 AI 回答具备可追溯来源和长期沉淀能力。
```

谨慎版本：

```text
参与设计 OmniVid 的 MySQL 数据模型，围绕视频去重、解析任务状态、字幕时间轴和总结资产建立索引与约束，并完成核心接口的落库和查询验证。
```

## 12. 避坑话术

不要这样说：

```text
我用了 MySQL、Redis、索引、事务、乐观锁。
```

这样太像堆技术名词。

建议这样说：

```text
我不是为了用 MySQL 而用 MySQL，而是因为长视频解析需要一个最终事实层。比如同一个 MD5 视频只能有一条资产记录，所以用唯一索引兜底；解析任务会被异步线程推进，所以用 version 乐观锁避免状态乱序；字幕点击跳转要求按时间快速定位，所以按 video_id + start_ms 建联合索引。这些点都能在页面和 MySQL 里验证。
```

如果被问“你这个项目 MySQL 最有含金量的点是什么”：

```text
我会选三个：第一是 MD5 唯一索引做幂等兜底，第二是 processing_job 的 version 乐观锁状态机，第三是字幕时间轴的 video_id + start_ms 联合索引。这三个点都不是为了八股硬加的，而是长视频上传、异步解析、点击跳转天然需要。
```

如果被问“还有哪些地方没做完”：

```text
目前 MySQL 已经承担事实层和核心索引，但还有演进空间：视频列表可以从 LIMIT offset 演进到游标分页；字幕关键词搜索可以从 LIKE 演进到全文索引或向量检索；聊天记录可以做冷热分离；任务补偿可以基于 status + updated_at 索引做扫描。这些都是我预留的二阶段优化点。
```

## 13. 高频八股追问速查

### 13.1 B+Tree 索引

面试问题：

```text
MySQL 为什么用 B+Tree？你的项目里哪里体现？
```

项目落点：

- `uk_video_md5(md5)`
- `idx_video_user_created(user_id, created_at)`
- `idx_transcript_video_start(video_id, start_ms)`

回答话术：

```text
OmniVid 里最典型的是字幕时间轴查询。查询通常先限定 video_id，再按 start_ms 排序或定位，联合索引底层是 B+Tree，能把同一个视频的字幕片段按 start_ms 有序组织起来。这样播放器点击跳转和 Agent 引用时间戳时，不需要扫描整个字幕表。
```

### 13.2 最左前缀

面试问题：

```text
联合索引最左前缀是什么？你怎么设计字段顺序？
```

项目落点：

```sql
idx_transcript_video_start(video_id, start_ms)
```

回答话术：

```text
字幕查询一定会带 video_id，因为时间戳只在单个视频内有意义；start_ms 是排序和定位字段，所以把 video_id 放在前面，start_ms 放后面。这样 where video_id = ? order by start_ms 或按时间范围查询都能利用索引。
```

### 13.3 回表与覆盖索引

面试问题：

```text
什么是回表？你项目如何减少回表？
```

项目落点：

```sql
idx_transcript_video_time_cover(video_id, start_ms, end_ms, segment_index)
```

回答话术：

```text
如果二级索引里没有查询需要的字段，MySQL 需要先查二级索引，再回到聚簇索引拿完整行，这就是回表。OmniVid 的时间轴定位通常只需要 start_ms、end_ms、segment_index 等字段，所以我预留了覆盖索引，后续可以让这类轻量查询直接从索引拿结果，减少回表。
```

### 13.4 索引失效

面试问题：

```text
哪些情况会导致索引失效？项目里怎么避免？
```

项目落点：

- `WHERE md5 = :md5`
- `WHERE video_id = :videoId ORDER BY start_ms`
- `WHERE user_id = :userId ORDER BY created_at`

回答话术：

```text
我尽量让高频查询保持等值过滤和索引字段原样使用，比如 MD5 去重直接 where md5 = ?，字幕时间轴直接 where video_id = ? order by start_ms。不会在索引列上做函数计算，也不会用前置模糊匹配破坏索引。当前 LIKE 关键词检索只是 MVP，不作为主要性能路径，后续会改全文索引或向量检索。
```

### 13.5 事务与隔离级别

面试问题：

```text
你项目哪里需要事务？MySQL 默认隔离级别会带来什么影响？
```

项目落点：

- `VideoService.completeUpload`
- 视频资产创建 + 任务创建
- `processing_job` 状态推进

回答话术：

```text
上传完成后需要创建 video_asset 和 processing_job，这两个动作应该保持一致：不能只有视频没有任务，也不能只有任务没有视频。MySQL 默认 RR 隔离级别能避免部分并发读写问题，但业务幂等不能只依赖隔离级别，所以我还用了唯一索引和 version 乐观锁做显式约束。
```

### 13.6 MVCC

面试问题：

```text
MVCC 是什么？和你项目有什么关系？
```

项目落点：

- 列表查询读 `video_asset`
- 任务详情读 `processing_job`
- 异步线程更新任务状态

回答话术：

```text
OmniVid 里有一个典型读写并发场景：前端通过 SSE/详情接口读任务进度，后端异步线程持续更新 processing_job。MVCC 能让读请求尽量不阻塞写请求，提升并发体验。但任务状态的正确推进还是靠 version 乐观锁保证。
```

### 13.7 行锁、间隙锁、死锁

面试问题：

```text
MySQL 行锁什么时候发生？你项目可能死锁吗？
```

项目落点：

- `UPDATE processing_job WHERE id = :id AND version = :version`
- `UPDATE video_asset WHERE id = :id`

回答话术：

```text
任务推进和视频状态更新都是按主键 id 更新，锁粒度比较小，正常只会锁单行。死锁风险主要来自多个事务以不同顺序更新多张表。OmniVid 的状态推进顺序比较固定：任务阶段先推进，最终再 markReady 视频，后续如果引入更多补偿逻辑，会统一更新顺序并做好死锁重试。
```

### 13.8 慢 SQL 与 EXPLAIN

面试问题：

```text
如果字幕查询慢，你怎么排查？
```

项目落点：

```sql
EXPLAIN SELECT * FROM transcript_segment
WHERE video_id = 3
ORDER BY start_ms ASC
LIMIT 5;
```

回答话术：

```text
我会先用 EXPLAIN 看 type、key、rows、Extra。预期 key 命中 idx_transcript_video_start，rows 不应该扫描过多。如果没有命中，就检查 where 条件是否符合最左前缀、是否在索引列上做了函数、order by 是否和索引顺序一致。
```

### 13.9 深分页

面试问题：

```text
LIMIT 100000, 30 为什么慢？你的项目怎么改？
```

项目落点：

```sql
SELECT * FROM video_asset
WHERE user_id = :userId
ORDER BY created_at DESC, id DESC
LIMIT 30
```

回答话术：

```text
深分页慢是因为 MySQL 要跳过大量行。OmniVid 现在是 MVP，只查最近 30 条；如果要翻很多页，会改成游标分页，用上一页最后一条的 created_at 和 id 作为游标，避免大 offset。
```

### 13.10 分库分表

面试问题：

```text
字幕表很大怎么办？怎么分表？
```

项目落点：

- `transcript_segment`
- `chat_message`

回答话术：

```text
字幕表天然可以按 video_id 聚合，因为查询基本都围绕某个视频展开。第一阶段先通过 video_id + start_ms 索引解决单库查询；如果数据量上来，可以按 video_id hash 分表，或者按用户/时间归档。聊天记录则更适合按 created_at 做冷热分离。
```

## 14. 简历埋钩子分层模板

### 14.1 一句话项目描述

```text
OmniVid 是一个面向长视频非结构化数据提纯的 Java + AI Agent 项目，基于 MySQL/Redis/Spring Boot 构建视频上传去重、异步解析状态机、字幕时间轴检索和可追溯问答链路。
```

### 14.2 MySQL 强调版

```text
负责 OmniVid 的 MySQL 数据模型与核心查询设计，围绕视频资产、解析任务、字幕时间轴、总结资产和 Agent 聊天记录建立表结构、唯一约束、联合索引与乐观锁机制。
```

### 14.3 后端工程版

```text
设计长视频上传到解析完成的后端链路：通过 `uk_video_md5` 保证视频幂等，通过 `processing_job.version` 控制异步任务状态流转，通过 `video_id + start_ms` 联合索引支撑字幕点击跳转和 Agent 时间戳引用。
```

### 14.4 面试展开版 STAR

```text
S：长视频重复上传和重复解析成本高，且解析任务耗时长、状态多。
T：需要保证视频资产唯一、任务状态一致、字幕检索高效。
A：我用 MySQL 作为最终事实层，设计 `uk_video_md5` 做内容幂等，`processing_job.version` 做乐观锁状态机，`idx_transcript_video_start` 支撑时间轴查询。
R：上传重复视频能命中去重，异步任务能稳定推进到 DONE，字幕点击和 Agent 引用能基于时间戳快速定位。
```

### 14.5 谦虚但有钩子的版本

```text
项目中我重点落地了 MySQL 事实层设计，包括视频 MD5 唯一约束、任务状态机 version 乐观锁、字幕时间轴联合索引和总结资产幂等约束；同时通过页面和 MySQL 命令验证这些设计确实服务于业务链路。
```
