# OmniVid 1.0 功能实现地图

更新时间：2026-06-10

## 阅读方式

本文面向“我要去面试，怎么证明这个项目不是空壳”的场景。每个功能都按同一结构展开：

```text
用户视角 -> 后端落点 -> 数据落点 -> 诊断/验收 -> 面试钩子
```

1.0 的核心口径是：OmniVid 已经从前端 mock 演进到真实后端联调，默认使用 Docker MySQL/Redis/Qdrant 模式。DeepSeek 只承担 Chat LLM；Embedding 和 LLM 解耦，未配置外部 Embedding 时使用 local hash fallback，但向量库链路仍然进入 Qdrant。

## 1. 本地视频上传

用户视角：

- 在前端选择本地视频。
- 上传后进入任务进度。
- 视频出现在视频库。
- 解析完成后显示视频播放器、时间轴字幕、结构化总结和 Agent 问答。

后端落点：

- `POST /api/videos/upload/file`
- `VideoController.uploadFile`
- `VideoService.uploadFile`
- `LocalVideoStorageService`
- `DedupeLockService`
- `ProcessingJobRepository`

数据落点：

- `video_asset`
- `processing_job`
- 本地文件目录：`storage/videos/{md5}/...`

诊断/验收：

- `GET /api/videos`
- `GET /api/videos/{videoId}`
- `GET /api/videos/{videoId}/progress`
- `GET /api/runtime/status`

面试钩子：

- Multipart 大文件上传。
- 流式 MD5 计算，避免 OOM。
- Redis/本地锁做防重复提交。
- MySQL `uk_video_md5` 做最终幂等兜底。
- 上传请求不阻塞等待 ASR，后端提交异步 DAG。

典型回答：

```text
长视频上传不能把文件完整读进堆里，所以我用流式保存和流式 MD5。MD5 一方面作为本地存储目录，另一方面作为内容指纹。Redis SET NX 负责挡并发窗口，MySQL 唯一索引负责最终一致性。上传接口只创建任务并返回，后续 ffmpeg、ASR、总结都放进异步 DAG。
```

## 2. MD5 去重与秒传

用户视角：

- 上传重复视频时，系统复用已有视频资产、字幕和总结。

后端落点：

- `DedupeLockService`
- `LocalDedupeLockService`
- `RedisDedupeLockService`
- `VideoRepository.findByMd5`
- `VideoRepository.insert`

数据落点：

- `video_asset.md5`
- `uk_video_md5`
- Redis key：`video:lock:{md5}`

诊断/验收：

- 重复上传同一视频时返回 `deduplicated=true`。
- `GET /api/mysql/explain` 可看到 `MD5 Dedupe` 命中 `uk_video_md5`。
- `GET /api/redis/inspect` 可看到 `SETNX Dedupe Lock` 诊断项。

面试钩子：

- 唯一索引。
- 幂等。
- 分布式锁。
- 锁 TTL。
- token 校验释放锁。
- Redis 失效后的 MySQL 兜底。

## 3. 异步 DAG 解析任务

用户视角：

- 上传后看到任务状态和进度。
- 不需要等待长请求阻塞。
- 失败任务可以诊断和重试。

后端落点：

- `ProcessingExecutorConfig`
- `ThreadPoolTaskExecutor omnividProcessingExecutor`
- `ProcessingJobRepository`
- `VideoService.runProcessingDag`
- `GET /api/jvm/thread-pool`

数据落点：

- `processing_job.current_step`
- `processing_job.status`
- `processing_job.progress`
- `processing_job.retry_count`
- `processing_job.error_message`
- `processing_job.version`

诊断/验收：

- `GET /api/jobs/{jobId}`
- `GET /api/jobs/failures`
- `GET /api/videos/{videoId}/progress`
- `GET /api/jvm/thread-pool`

面试钩子：

- 为什么不用 HTTP 同步阻塞。
- 线程池 core/max/queue/rejection。
- 状态机推进。
- 乐观锁/CAS 思想。
- 失败重试和补偿边界。
- SSE 进度推送。

典型回答：

```text
视频解析是长耗时任务，ffmpeg、ASR、LLM 都可能持续几十秒甚至更久，所以我没有让 HTTP 请求同步等待，而是创建 processing_job 后投递到本地轻量 DAG。DAG 节点推进时更新 MySQL 状态，Redis 缓存最新进度，前端通过 SSE 观察变化。后续如果上 RocketMQ，DAG 节点可以拆成消息事件，但 1.0 先把状态机、幂等和失败恢复讲清楚。
```

## 4. ffmpeg/ffprobe 音视频处理

用户视角：

- 上传视频后可以识别时长。
- 系统自动抽取音频用于 ASR。
- 视频播放器可以播放本地上传的视频。

后端落点：

- `FfmpegAudioExtractionService`
- `VideoController.media`
- `GET /api/videos/{videoId}/media`

数据/文件落点：

- `audio.wav`
- `ffmpeg.log`
- `video_asset.duration_ms`

诊断/验收：

- `GET /api/videos/{videoId}/asr/diagnostics`
- 查看 `audioExists/audioSizeBytes/ffmpegLogTail`

面试钩子：

- Java 调系统进程。
- 子进程超时。
- 标准输出/错误输出阻塞。
- ffmpeg 日志。
- HTTP Range 播放。
- 大文件 IO。

## 5. whisper ASR 与字幕质量优化

用户视角：

- 视频 READY 后出现真实时间轴字幕。
- 默认中文转简体。
- 技术术语尽量保留英文形态。
- 出现乱码或繁体时可以修复。

后端落点：

- `WhisperAsrService`
- `SubtitleTextSanitizer`
- `TranscriptContextRepairService`
- `BurnedSubtitleOcrService`
- `TermGlossaryController`
- `AsrDiagnosticController`

数据落点：

- `transcript_segment`
- `term_glossary_entry`

诊断/验收：

- `GET /api/videos/{videoId}/asr/diagnostics`
- `POST /api/videos/{videoId}/asr/repair-encoding`
- `GET /api/videos/{videoId}/asr/evaluate-ocr`
- `POST /api/videos/{videoId}/asr/fuse-ocr`
- `POST /api/videos/{videoId}/asr/align-ocr`
- `POST /api/videos/{videoId}/asr/refine-low-confidence`
- `GET /api/asr/glossary`

1.0 已做的质量优化：

- 去除 BOM、替换字符和异常控制字符。
- 检测 Latin1/UTF-8 乱码并尝试修复。
- OpenCC 简繁转换，额外修正 `妳/祢` 等残留繁体代词。
- 术语规则：MySQL、Redis、Redisson、SETNX、MyBatis、Spring Boot、Qdrant、Embedding、Rerank、RAG、LLM、AI Agent、Codex、Claude Code 等。
- ASR 初始 prompt 包含 Java 后端和 AI Agent 热词。
- OCR 作为画面字幕强证据，用于评估、对齐和低置信片段二次修复。
- 术语词库支持页面管理，为后续模型热词注入埋钩子。

面试钩子：

- ASR 不是单纯调模型，还要做后处理、质量诊断和可回滚修复。
- 画面 OCR 可作为强证据修 ASR，但 1.0 保持保守写回，避免过拟合。
- 技术词识别错误可用术语词库、上下文修复和二次识别处理。

## 6. 时间轴字幕与点击跳转

用户视角：

- 字幕按时间展示。
- 点击字幕后播放器跳到对应画面。
- 长字幕用滚动窗口避免页面过长。
- 播放时高亮当前字幕。

后端落点：

- `GET /api/videos/{videoId}/transcripts`
- `GET /api/videos/{videoId}/transcripts/search?q=...`
- `TranscriptRepository`

数据落点：

- `transcript_segment.video_id`
- `transcript_segment.start_ms`
- `transcript_segment.end_ms`
- `idx_transcript_video_start`
- `idx_transcript_video_time_cover`

面试钩子：

- 联合索引顺序为什么是 `video_id + start_ms`。
- B+Tree 最左前缀。
- 覆盖索引和回表。
- 关键词检索为什么不是最终方案，后续引入全文索引或向量检索。

## 7. 结构化总结

用户视角：

- 右侧切换核心观点、会议纪要、博客大纲、PPT 大纲。
- “一键生成对应的 PPT/会议纪要/博客等”作为未来真实导出能力入口。

后端落点：

- `GET /api/videos/{videoId}/summaries`
- `SummaryRepository`
- `CloudSummaryService`
- `VideoService.generateSummaries`

数据落点：

- `summary_asset`
- `uk_summary_video_type(video_id, type)`

总结类型：

- `CORE_POINTS`
- `MEETING_MINUTES`
- `BLOG_OUTLINE`
- `PPT_OUTLINE`
- `INTERVIEW_HOOKS`

面试钩子：

- 同一视频同一类型总结用唯一约束保证幂等。
- LLM 生成严格 JSON，失败降级本地规则总结器。
- 总结只基于 ASR 字幕，不能编造视频外事实。
- PPT 目前是大纲，不是 1.0 的真实 PPTX 文件导出。

## 8. DeepSeek LLM Provider

用户视角：

- 右侧“云端 LLM”入口打开后保存 DeepSeek API Key。
- 可查看可用 Provider 列表。
- 可启用 Provider 和测试连接。
- Agent 和总结优先调用激活的 LLM。

后端落点：

- `CloudLlmController`
- `CloudLlmClient`
- `LlmProviderService`
- `LlmProviderRepository`

数据落点：

- `llm_provider_config`
- `uk_llm_provider(provider_name, base_url, model)`

面试钩子：

- OpenAI-compatible API 适配。
- API Key 不回显，只展示 mask。
- 1.0 仍需说明：当前是 demo 级编码存储，2.0 要做 KMS/Secrets Manager。
- LLM 超时、失败、JSON 解析失败都要降级。

## 9. Embedding、Qdrant 与 Rerank

用户视角：

- Agent 问答链路里可看到向量检索和 rerank trace。
- 诊断台可查看 Qdrant 状态和向量索引。
- 可手动重建向量索引。

后端落点：

- `EmbeddingProviderController`
- `EmbeddingProviderService`
- `OpenAiCompatibleEmbeddingProvider`
- `LocalHashEmbeddingProvider`
- `TranscriptVectorSearch`
- `QdrantVectorStore`
- `VectorIndexController`
- `AgentRerankService`

数据落点：

- `embedding_provider_config`
- Qdrant collection：`omnivid_transcript_segments`

诊断/验收：

- `GET /api/embedding/providers`
- `POST /api/embedding/test`
- `GET /api/vector-index/status`
- `POST /api/vector-index/rebuild`
- `GET /api/runtime/status`

1.0 口径：

- DeepSeek 只保留 Chat LLM。
- Embedding 可接 Qwen/OpenAI/BGE OpenAI-compatible 服务。
- 未配置时使用 local hash fallback。
- Qdrant 是真实外部向量数据库。
- Rerank 是本地 rerank，外部 BGE reranker 放入 2.0。

面试钩子：

- 召回和重排分层。
- 向量库和关系库分工。
- Embedding provider 和 LLM provider 解耦。
- 低置信证据过滤：关键词、cosine、rerank 阈值。

## 10. 单视频 Agent

用户视角：

- 针对当前视频提问。
- 命中视频内容时返回回答和时间戳引用。
- 没命中视频内容时，先说明视频未检索到，再给通用回答。
- 自我介绍类问题直接介绍 Agent 自身。
- 答案后可展开执行链路。

后端落点：

- `POST /api/videos/{videoId}/agent/ask`
- `GET /api/videos/{videoId}/agent/messages`
- `GET /api/videos/{videoId}/agent/context`
- `DELETE /api/videos/{videoId}/agent/messages`
- `AgentService`

数据落点：

- `chat_message`
- Redis 短期记忆 key
- Redis 精确问题缓存 key

Agent trace：

```text
InputGuardrail
MemoryTool
TranscriptRetrieveTool
VectorRetrieveTool
RerankTool
CitationBuilderTool
AnswerPolicyTool
LlmGenerateTool
ConfidenceGuard
PersistTool
```

面试钩子：

- Agent 不是裸 LLM，而是工具链。
- RAG 证据约束。
- 引用可追溯。
- 防幻觉。
- 短期记忆和长期聊天记录分层。
- 低置信度和无证据回答边界。

## 11. 多视频知识库

用户视角：

- 创建知识库。
- 添加或移除视频。
- 对知识库提问。
- 对比多个视频观点。
- 点击引用可切换视频并跳转时间戳。

后端落点：

- `KnowledgeBaseController`
- `KnowledgeBaseService`
- `KnowledgeBaseRepository`
- `KnowledgeBaseAgentController`
- `AgentService.askKnowledgeBase`

数据落点：

- `knowledge_base`
- `knowledge_base_video`
- `uk_knowledge_base_video`
- `idx_kb_video_video`

诊断/验收：

- `GET /api/knowledge-bases`
- `POST /api/knowledge-bases`
- `POST /api/knowledge-bases/{id}/videos`
- `POST /api/knowledge-bases/{id}/agent/ask`
- `DELETE /api/knowledge-bases/{id}`

面试钩子：

- 多对多关系表。
- 唯一约束防重复添加。
- 跨视频 RAG。
- 多视频引用和来源追溯。
- 知识库权限、多租户是 2.0。

## 12. 诊断台

用户视角：

- 右上角点击诊断台。
- 查看 Runtime、MySQL、Redis、JVM、ASR、RAG、Qdrant、Recovery。
- 不占用主工作台空间。

后端落点：

- `RuntimeStatusController`
- `MysqlExplainController`
- `RedisInspectController`
- `ThreadPoolInspectorController`
- `AsrDiagnosticController`
- `VectorIndexController`
- `ProcessingJobController`

面试钩子：

- 可观测性。
- 黑盒验证。
- 运行时依赖状态。
- 索引计划解释。
- 线程池状态。
- 子进程日志。
- RAG 检索质量。

## 13. URL 导入 MVP

用户视角：

- 粘贴 B 站/抖音/小红书公开链接尝试导入。
- 遇到 B 站 412 或反爬时显示建议：cookies.txt 或 browser cookies。

后端落点：

- `POST /api/videos/import/url`
- `VideoUrlImportService`
- `yt-dlp`

1.0 口径：

- URL 导入保留 MVP。
- 平台反爬不属于 1.0 继续攻关范围。
- 不做绕过，不做账号自动化，不做 CAPTCHA 或 fingerprint 规避。

面试钩子：

- 子进程调用。
- 失败诊断。
- 合规边界。
- 外部平台依赖不可控时如何降级。

## 14. 1.0 可演示功能总表

| 功能 | 是否 1.0 已实现 | 面试关键词 |
| --- | --- | --- |
| 本地视频上传 | 是 | Multipart、流式 IO、MD5 |
| MD5 去重 | 是 | Redis 锁、MySQL 唯一索引、幂等 |
| 异步 DAG | 是 | 线程池、状态机、失败重试 |
| ffmpeg/ffprobe | 是 | 子进程、超时、日志、音频抽取 |
| whisper ASR | 是 | ASR、JSON 解析、字幕质量 |
| ASR/OCR 融合诊断 | 是 | OCR 强证据、低置信修复 |
| 术语词库 | 是 | ASR 热词、上下文修复 |
| 时间轴字幕 | 是 | 联合索引、点击跳转、播放器 |
| 结构化总结 | 是 | LLM JSON、唯一约束、降级 |
| DeepSeek Chat | 是 | OpenAI-compatible、Provider 管理 |
| Embedding Provider | MVP | provider 解耦、fallback |
| Qdrant | 是 | 外部向量库、collection、upsert/search |
| Rerank | MVP | local rerank、召回重排 |
| 单视频 Agent | 是 | RAG、引用、防幻觉 |
| 多视频知识库 | 是 | 多对多关系、跨视频问答 |
| 诊断台 | 是 | 可观测性、黑盒验证 |
| URL 导入 | MVP | yt-dlp、失败诊断、合规边界 |
