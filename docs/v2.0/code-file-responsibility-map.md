# OmniVid 2.0 代码职责地图

## 后端入口与公共能力

| 路径 | 职责 |
| --- | --- |
| `ApiApplication.java` | Spring Boot 启动与异步能力入口 |
| `common/GlobalExceptionHandler.java` | 结构化错误响应 |
| `common/WebConfig.java` | CORS 与响应头暴露 |
| `observability/TraceFilter.java` | `X-Trace-Id`、MDC 和请求日志 |
| `security/ProviderSecretService.java` | AES-GCM Provider Secret 加解密与旧数据兼容 |

## 视频、上传和任务

| 路径 | 职责 |
| --- | --- |
| `video/VideoController.java` | 视频、播放、字幕、进度、重试 API |
| `video/VideoService.java` | 视频解析主编排、ASR/OCR、回流和任务状态 |
| `video/VideoRepository.java` | 视频 MySQL 持久化 |
| `video/VideoUrlImportService.java` | yt-dlp URL 导入与诊断 |
| `storage/LocalVideoStorageService.java` | 视频/分片落盘和合并 |
| `upload/ChunkUploadController.java` | 分片上传 API |
| `upload/ChunkUploadService.java` | 会话、缺失分片、合并和 MD5 校验 |
| `upload/ChunkUploadRepository.java` | 上传会话与分片持久化 |
| `job/ProcessingJobRepository.java` | 解析任务状态机 |
| `job/mq/ProcessingEventRepository.java` | Outbox 状态机和 CAS |
| `job/mq/RocketMqOutboxPublisher.java` | Outbox 发布和退避 |
| `job/mq/RocketMqProcessingConsumer.java` | 消费、重试、DLQ 和幂等 |

## 字幕和媒体

| 路径 | 职责 |
| --- | --- |
| `media/FfmpegAudioExtractionService.java` | 音频抽取与人声增强 |
| `media/VideoMetadataProbeService.java` | ffprobe 时长探测 |
| `asr/WhisperAsrService.java` | Whisper 转写 |
| `asr/BurnedSubtitleOcrService.java` | 烧录字幕 OCR、对齐和保守融合 |
| `asr/AsrDiagnosticService.java` | ASR/OCR 质量诊断 |
| `transcript/SubtitleTextSanitizer.java` | 简体化、乱码和术语清理 |
| `transcript/TranscriptContextRepairService.java` | 上下文证据驱动的歧义修复 |
| `transcript/TranscriptRepository.java` | 字幕读写、搜索和替换 |
| `transcript/TranscriptVersionRepository.java` | 字幕快照、差异和恢复 |
| `transcript/TermGlossaryService.java` | 用户术语词库 |

## Agent、知识库和 AI Provider

| 路径 | 职责 |
| --- | --- |
| `agent/AgentService.java` | 输入检查、记忆、召回、引用、LLM、置信度 |
| `agent/AgentController.java` | 当前视频 Agent |
| `agent/KnowledgeBaseAgentController.java` | 默认/自定义知识库 Agent |
| `agent/cache/*` | 本地/Redis 语义缓存 |
| `agent/memory/*` | 本地/Redis 短期记忆 |
| `agent/retrieval/OpenAiCompatibleEmbeddingProvider.java` | Qwen/OpenAI/BGE Embedding 与降级 |
| `agent/retrieval/QdrantVectorStore.java` | Qdrant collection 和向量 CRUD |
| `agent/retrieval/TranscriptVectorSearch.java` | 字幕向量检索与索引重建 |
| `agent/retrieval/AgentRerankService.java` | 远程/本地重排 |
| `agent/retrieval/*ProviderService.java` | Provider 保存、激活、测试、轮换和删除 |
| `knowledge/KnowledgeBaseService.java` | 成员管理、覆盖统计和观点对比 |
| `llm/CloudLlmClient.java` | DeepSeek Chat Completions |
| `summary/CloudSummaryService.java` | 结构化总结 |

## 导出、诊断和基础设施

| 路径 | 职责 |
| --- | --- |
| `export/ExportDocumentGenerator.java` | DeepSeek 详细内容模型与本地兜底 |
| `export/ExportRenderer.java` | Markdown/DOCX/PPTX 渲染 |
| `export/ExportService.java` | 生成缓存与下载响应 |
| `mysql/MysqlExplainService.java` | MySQL EXPLAIN Inspector |
| `redis/RedisInspectService.java` | Redis Key Inspector |
| `jvm/ThreadPoolInspectorController.java` | 线程池 Inspector |
| `runtime/RuntimeStatusController.java` | 全局运行态汇总 |
| `apps/web/src/main.tsx` | 工作台业务交互与 API 调用 |
| `apps/web/src/styles.css` | 三栏暗色工作台布局 |
| `infra/docker-compose.yml` | MySQL、Redis、Qdrant、RocketMQ、API、Web |
| `.github/workflows/ci.yml` | 后端、前端、Compose 与镜像 CI |

## 测试职责

| 测试 | 覆盖 |
| --- | --- |
| `ApiApplicationTests` | Spring 上下文与 Trace Header |
| `ProviderSecretServiceTests` | AES-GCM 与旧 Base64 兼容 |
| `ProcessingEventRepositoryTests` | Outbox 唯一、CAS 和中断恢复 |
| `SubtitleTextSanitizerTests` | 技术词、简体和乱码清洗 |
| `TranscriptContextRepairServiceTests` | 有证据修复与避免过拟合 |
| `ExportRendererTests` | Markdown/DOCX/PPTX 可用性 |
