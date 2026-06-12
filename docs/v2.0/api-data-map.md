# OmniVid 2.0 API 与数据地图

## 核心 API

### 视频、上传与播放

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `POST` | `/api/videos/upload/chunked/sessions` | 创建或恢复上传会话 |
| `GET` | `/api/videos/upload/chunked/sessions/{sessionId}` | 查询缺失分片 |
| `POST` | `/api/videos/upload/chunked/sessions/{sessionId}/parts/{partNumber}` | 上传分片 |
| `POST` | `/api/videos/upload/chunked/sessions/{sessionId}/complete` | 合并并进入解析 |
| `POST` | `/api/videos/upload/file` | 兼容单文件上传 |
| `POST` | `/api/videos/import/url` | 导入公开平台 URL |
| `GET` | `/api/videos` | 视频库 |
| `GET` | `/api/videos/{videoId}` | 视频详情 |
| `GET` | `/api/videos/{videoId}/media` | HTTP Range 播放 |
| `POST` | `/api/videos/{videoId}/retry` | 补偿失败任务 |
| `GET` | `/api/videos/{videoId}/progress` | 当前进度 |
| `GET` | `/api/videos/{videoId}/progress/stream` | SSE 实时进度 |

### 字幕、ASR/OCR 与版本

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `GET` | `/api/videos/{videoId}/transcripts` | 时间轴字幕 |
| `GET` | `/api/videos/{videoId}/transcripts/search` | 字幕检索 |
| `PATCH` | `/api/videos/{videoId}/transcripts/{segmentId}` | 编辑字幕并回流 |
| `GET` | `/api/videos/{videoId}/transcripts/versions` | 字幕版本列表 |
| `GET` | `/api/videos/{videoId}/transcripts/versions/{versionId}` | 版本差异 |
| `POST` | `/api/videos/{videoId}/transcripts/versions/{versionId}/restore` | 恢复版本 |
| `GET` | `/api/videos/{videoId}/asr/diagnostics` | ASR/OCR 诊断 |
| `POST` | `/api/videos/{videoId}/asr/reprocess` | 重新执行 ASR |
| `POST` | `/api/videos/{videoId}/asr/fuse-ocr` | 保守 OCR 融合 |
| `POST` | `/api/videos/{videoId}/asr/align-ocr` | 扩大 OCR 对齐 |
| `POST` | `/api/videos/{videoId}/asr/refine-low-confidence` | 低置信片段修复 |
| `GET/POST` | `/api/asr/glossary` | 术语词库查询/新增 |

### Agent、知识库与检索

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `POST` | `/api/videos/{videoId}/agent/ask` | 当前视频问答 |
| `GET` | `/api/videos/{videoId}/agent/messages` | 问答历史 |
| `DELETE` | `/api/videos/{videoId}/agent/messages` | 清空问答历史 |
| `POST` | `/api/knowledge-bases/default/agent/ask` | 默认全视频知识库问答 |
| `POST` | `/api/knowledge-bases/{id}/agent/ask` | 指定知识库问答 |
| `GET/POST/DELETE` | `/api/knowledge-bases` | 知识库管理 |
| `POST/DELETE` | `/api/knowledge-bases/{id}/videos` | 视频成员管理 |
| `GET` | `/api/knowledge-bases/{id}/coverage` | 覆盖统计 |
| `POST` | `/api/knowledge-bases/{id}/compare` | 多视频观点对比 |
| `GET` | `/api/vector-index/status` | Qdrant 状态 |
| `POST` | `/api/vector-index/rebuild` | 重建向量索引 |

### Provider 与导出

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `GET/POST` | `/api/llm/providers` | DeepSeek Provider 管理 |
| `POST` | `/api/llm/providers/{id}/activate|rotate|disable` | LLM 生命周期 |
| `POST` | `/api/llm/test` | LLM 连通性测试 |
| `GET/POST` | `/api/embedding/providers` | Embedding Provider 管理 |
| `POST` | `/api/embedding/test` | Embedding 测试 |
| `GET/POST` | `/api/rerank/providers` | Rerank Provider 管理 |
| `POST` | `/api/rerank/test` | Rerank 测试 |
| `POST` | `/api/videos/{videoId}/exports` | 导出 Markdown/DOCX/PPTX |

### 运维与诊断

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `GET` | `/api/runtime/status` | 全局运行态 |
| `GET` | `/api/jobs/mq/status` | RocketMQ/Outbox 状态 |
| `GET` | `/api/jobs/events` | 任务事件列表 |
| `POST` | `/api/jobs/events/{eventId}/retry` | DLQ/失败事件重投 |
| `GET` | `/api/mysql/explain` | 字幕查询执行计划 |
| `GET` | `/api/redis/inspect` | Redis Key 诊断 |
| `GET` | `/api/jvm/thread-pool` | 线程池状态 |

## MySQL 表职责

| 表 | 最终事实 | 关键面试点 |
| --- | --- | --- |
| `users` | 当前 demo 用户记录 | 用户边界预留 |
| `video_asset` | 视频元数据、MD5、存储路径 | MD5 唯一索引、秒传 |
| `processing_job` | 解析任务状态机 | 乐观锁、补偿重试 |
| `processing_event` | Outbox 与 DLQ | 本地事务、消息最终一致性 |
| `transcript_segment` | 时间轴字幕 | `video_id + segment_index/start_ms` 查询 |
| `transcript_version` | 字幕完整快照 | 人在回路、版本恢复 |
| `summary_asset` | 总结资产 | 视频+类型唯一约束 |
| `chat_message` | Agent 历史 | 长期记忆 |
| `knowledge_base` | 知识库 | 聚合边界 |
| `knowledge_base_video` | 知识库视频关系 | 多对多、幂等添加 |
| `llm_provider_config` | LLM 配置 | 加密 Key、激活切换 |
| `embedding_provider_config` | Embedding 配置 | 模型维度迁移 |
| `rerank_provider_config` | Rerank 配置 | 动态 Provider |
| `upload_session` | 上传会话 | 断点续传状态机 |
| `upload_part` | 分片元数据 | 分片幂等、MD5 校验 |
| `term_glossary_entry` | ASR 术语规则 | 显式数据回流 |

## Redis 职责

Redis Key 由服务封装管理，业务含义包括：

- 上传/视频 MD5 防重锁。
- 视频任务进度快照。
- Agent 请求限流计数。
- 当前视频和知识库回答缓存。
- Agent 多轮短期记忆。

一致性原则：

- MySQL 是事实层，Redis 是可失效的性能层。
- 字幕编辑或恢复后，清理视频和所属知识库语义缓存。
- Redis 不可用时切换本地实现，不影响关键数据写入 MySQL。

## Qdrant 与 RocketMQ

### Qdrant

- Collection：`omnivid_transcript_segments`。
- Distance：Cosine。
- Payload：字幕 ID、视频 ID、时间戳和文本。
- Embedding 模型变化后执行 `/api/vector-index/rebuild`。

### RocketMQ

- Topic：`omnivid-processing`。
- 消息体只传事件 ID，避免大消息和脏状态。
- Outbox 状态：`PENDING -> PUBLISHED -> CONSUMING -> CONSUMED`。
- 失败路径：退避重试，超过阈值进入 `DLQ`。

## 数据恢复优先级

```text
MySQL + 原始视频
  -> 可恢复解析任务
  -> 可重新生成字幕/总结
  -> 可重建 Qdrant
  -> 可重新填充 Redis
```
