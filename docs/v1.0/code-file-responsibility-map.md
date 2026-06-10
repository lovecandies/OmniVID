# OmniVid 1.0 代码文件职责地图

更新时间：2026-06-10

## 阅读方式

这份文档记录 OmniVid 1.0 仓库中主要代码、配置、脚本和文档文件的职责。后续聊天记录丢失时，先读本文件，可以快速知道“要改哪个文件”和“不要误改哪个文件”。

范围说明：

- 重点覆盖已提交到 Git 的源码、配置、脚本、测试和关键文档。
- `apps/web/dist`、数据库卷、上传视频、日志、ASR 产物、API Key、Cookie 等不应作为手写源码维护，也不应提交。
- 后端当前是 JDBC Repository 风格，不是完整 MyBatis 项目；MyBatis 作为面试迁移钩子保留在文档里。

## 仓库总览

| 路径 | 职责 |
| --- | --- |
| `.gitignore` | 排除构建产物、运行时文件、上传文件、日志和本地敏感配置。 |
| `CODEX.md` | 根目录项目蓝图和 Vibe Coding 协作约束。 |
| `README.md` | GitHub 首页入口，介绍项目定位、能力、启动方式、验收和面试口径。 |
| `项目demo.md` | 项目 demo 说明资料，偏展示用途。 |
| `apps/api` | Spring Boot 后端。 |
| `apps/web` | React/Vite 前端工作台。 |
| `infra` | Docker Compose 基础设施。 |
| `scripts` | 启动和 OCR 辅助脚本。 |
| `docs` | 求职、技术、面试、1.0 收束和归档文档。 |

## 后端根文件

| 文件 | 职责 |
| --- | --- |
| `apps/api/pom.xml` | Maven 项目配置；Spring Boot 3.5、Java 21、Web、JDBC、Redis、Validation、H2、MySQL、opencc4j、测试依赖。 |
| `apps/api/mvnw` | Linux/macOS Maven Wrapper。 |
| `apps/api/mvnw.cmd` | Windows Maven Wrapper。 |
| `apps/api/.mvn/wrapper/maven-wrapper.properties` | Maven Wrapper 下载和版本配置。 |
| `apps/api/.gitattributes` | 后端仓库换行和属性配置。 |
| `apps/api/.gitignore` | 后端局部忽略规则。 |
| `apps/api/README.md` | 后端启动、Docker profile、接口和 smoke test 说明。 |
| `apps/api/CODEX.md` | 后端目录级架构说明。 |
| `apps/api/HELP.md` | Spring Initializr 生成的帮助文档，可作为参考，不承载业务逻辑。 |

## 后端配置和数据库脚本

| 文件 | 职责 |
| --- | --- |
| `apps/api/src/main/resources/application.yml` | 默认本地配置，包含 H2/本地模式、上传路径、ffmpeg/whisper/LLM/vector 默认参数。 |
| `apps/api/src/main/resources/application-docker.yml` | Docker profile 配置，连接 MySQL 3307、Redis 6379、Qdrant 6333，并启用 Redis/Qdrant 实现。 |
| `apps/api/src/main/resources/schema.sql` | H2/默认模式建表脚本，用于本地测试和轻量运行。 |
| `apps/api/src/main/resources/schema-mysql.sql` | MySQL 8.4 建表脚本，包含视频、任务、字幕、总结、LLM、Embedding、知识库、聊天等表和索引。 |

## 后端入口与公共层

| 文件 | 职责 |
| --- | --- |
| `ApiApplication.java` | Spring Boot 启动入口。 |
| `common/ApiException.java` | 业务异常封装，带 HTTP 状态码和可读消息。 |
| `common/GlobalExceptionHandler.java` | 全局异常处理，将业务错误和未知错误转换为统一 JSON 响应。 |
| `common/WebConfig.java` | Web 层配置，主要负责 CORS 和前后端联调访问。 |
| `health/HealthController.java` | 健康检查接口，用于确认后端存活。 |

## 视频上传、播放和 URL 导入

| 文件 | 职责 |
| --- | --- |
| `video/VideoController.java` | 视频 HTTP 入口；上传、本地媒体播放、视频详情、字幕、总结、进度、URL 导入等接口。 |
| `video/VideoService.java` | 视频核心业务编排；上传、去重、任务创建、DAG 解析、ffmpeg、ASR、总结、向量索引触发。 |
| `video/VideoRepository.java` | `video_asset` 表访问；按 MD5/ID 查询、插入、状态更新、列表查询。 |
| `video/VideoAsset.java` | 视频资产领域对象，对应视频表字段。 |
| `video/VideoDetailResponse.java` | 视频详情响应 DTO，聚合视频、字幕、总结、任务等前端需要的数据。 |
| `video/CompleteUploadRequest.java` | 完成上传请求 DTO，保留未来分片/断点续传扩展入口。 |
| `video/CompleteUploadResponse.java` | 上传完成响应 DTO，返回视频 ID、任务 ID、是否去重等。 |
| `video/VideoUrlImportRequest.java` | URL 导入请求 DTO，包含平台链接、cookies 路径或浏览器 cookies 选择。 |
| `video/VideoUrlImportService.java` | 通过 `yt-dlp` 导入公开视频链接，并把下载结果接入同一套视频处理链路；失败时输出平台诊断建议。 |
| `storage/LocalVideoStorageService.java` | 本地视频文件存储；流式保存、MD5 计算、目录组织和媒体文件定位。 |
| `storage/StoredVideoFile.java` | 本地存储结果对象，记录文件路径、文件名、大小、MD5 等。 |

## 去重锁

| 文件 | 职责 |
| --- | --- |
| `dedupe/DedupeLock.java` | 去重锁句柄，保存 key/token/过期信息，用于安全释放锁。 |
| `dedupe/DedupeLockService.java` | 去重锁接口，屏蔽本地实现和 Redis 实现差异。 |
| `dedupe/LocalDedupeLockService.java` | 本地内存防重锁，适合无 Redis 的开发/测试模式。 |
| `dedupe/RedisDedupeLockService.java` | Redis SETNX 风格防重锁，Docker 模式默认使用。 |

## 异步任务、进度和失败恢复

| 文件 | 职责 |
| --- | --- |
| `job/ProcessingExecutorConfig.java` | 配置本地 DAG 线程池 `omnividProcessingExecutor`。 |
| `job/ProcessingJob.java` | 解析任务领域对象，对应 `processing_job`。 |
| `job/ProcessingJobController.java` | 任务查询、失败任务列表、retry 等 HTTP 接口。 |
| `job/ProcessingJobRepository.java` | 任务表访问；创建任务、状态推进、失败记录、版本更新、retry job。 |
| `job/FailedJobResponse.java` | 失败任务响应 DTO，供诊断台和恢复面板展示。 |
| `progress/ProgressSnapshot.java` | 任务进度快照 DTO，包含阶段、百分比、消息和时间。 |
| `progress/ProgressCacheService.java` | 进度缓存接口。 |
| `progress/LocalProgressCacheService.java` | 本地内存进度缓存实现。 |
| `progress/RedisProgressCacheService.java` | Redis 进度缓存实现，Docker 模式默认使用。 |

## 媒体处理、ASR 和 OCR

| 文件 | 职责 |
| --- | --- |
| `media/FfmpegAudioExtractionService.java` | 调用 ffmpeg 抽取音频、写日志、处理超时和子进程错误。 |
| `media/AudioExtractionException.java` | 音频抽取异常。 |
| `media/AudioExtractionResult.java` | 音频抽取结果，包含 `audio.wav`、日志和时长信息。 |
| `media/VideoMetadataProbeService.java` | 调用 ffprobe 获取视频时长等元数据。 |
| `asr/WhisperAsrService.java` | 调用 whisper.cpp 执行 ASR，解析 JSON 输出为字幕片段。 |
| `asr/AsrTranscriptionResult.java` | ASR 总结果对象。 |
| `asr/AsrTranscriptSegment.java` | ASR 单条字幕片段对象。 |
| `asr/AsrTranscriptionException.java` | ASR 调用或解析异常。 |
| `asr/AsrDiagnosticController.java` | ASR 诊断、OCR 评估、字幕修复、低置信二次处理等接口。 |
| `asr/AsrDiagnosticService.java` | 聚合 ASR 产物、日志、模型、OCR 和修复能力。 |
| `asr/AsrDiagnosticResponse.java` | ASR 诊断响应 DTO。 |
| `asr/AsrQualityResponse.java` | ASR 质量评估响应 DTO。 |
| `asr/BurnedSubtitleOcrService.java` | 画面硬字幕 OCR 辅助服务，用于评估、对齐和融合字幕。 |
| `asr/OcrSubtitleQualityResponse.java` | OCR 字幕质量评估响应 DTO。 |
| `asr/OcrSubtitleSampleResponse.java` | OCR 样本响应 DTO。 |
| `asr/TranscriptRepairResponse.java` | 字幕修复结果响应 DTO。 |

## 字幕、清洗和术语词库

| 文件 | 职责 |
| --- | --- |
| `transcript/TranscriptRepository.java` | 字幕表访问；批量写入、按视频查询、关键词搜索、时间轴查询。 |
| `transcript/TranscriptSegment.java` | 字幕片段领域对象，对应 `transcript_segment`。 |
| `transcript/TranscriptDraft.java` | 字幕草稿对象，用于修复、OCR 融合或二次处理前的中间态。 |
| `transcript/SubtitleTextSanitizer.java` | 字幕文本清洗；去乱码、转简体、修复替换字符、规范技术术语。 |
| `transcript/TranscriptContextRepairService.java` | 结合上下文和术语词库修复 ASR 低质量文本。 |
| `transcript/TermGlossaryController.java` | 术语词库管理 HTTP 接口。 |
| `transcript/TermGlossaryService.java` | 术语词库业务逻辑；创建、查询、应用术语。 |
| `transcript/TermGlossaryRepository.java` | 术语词库表访问。 |
| `transcript/TermGlossaryEntry.java` | 术语词库领域对象。 |
| `transcript/TermGlossaryCreateRequest.java` | 新增术语请求 DTO。 |

## 结构化总结

| 文件 | 职责 |
| --- | --- |
| `summary/SummaryRepository.java` | 总结资产表访问；按视频和类型保存/查询。 |
| `summary/SummaryAsset.java` | 总结资产领域对象，对应核心观点、会议纪要、博客大纲、PPT 大纲等。 |
| `summary/CloudSummaryService.java` | 基于字幕生成结构化总结；优先调用云端 LLM，失败时降级本地模板。 |
| `summary/CloudSummaryBundle.java` | 云端总结结果聚合对象。 |

## LLM Provider

| 文件 | 职责 |
| --- | --- |
| `llm/CloudLlmController.java` | 云端 LLM 配置、保存、启用、测试和列表接口。 |
| `llm/CloudLlmClient.java` | OpenAI-compatible Chat Completions 客户端，当前主要用于 DeepSeek。 |
| `llm/CloudLlmConfigRequest.java` | 早期/兼容 LLM 配置请求 DTO。 |
| `llm/CloudLlmConfigResponse.java` | LLM 配置响应 DTO。 |
| `llm/CloudLlmResult.java` | LLM 调用结果对象。 |
| `llm/CloudLlmTestResponse.java` | LLM 连接测试响应 DTO。 |
| `llm/LlmProviderConfig.java` | LLM Provider 配置领域对象。 |
| `llm/LlmProviderRepository.java` | LLM Provider 表访问。 |
| `llm/LlmProviderResponse.java` | Provider 列表响应 DTO，返回 mask 后的 key 信息。 |
| `llm/LlmProviderSaveRequest.java` | 保存 Provider 请求 DTO。 |
| `llm/LlmProviderService.java` | Provider 保存、启用、查询、测试和调用编排。 |
| `llm/CloudEmbeddingResult.java` | 旧版/辅助 Embedding 结果对象；当前 Embedding 主链路在 `agent/retrieval` 包。 |

## Agent 问答

| 文件 | 职责 |
| --- | --- |
| `agent/AgentController.java` | 当前视频 Agent 问答、上下文、消息历史清理等接口。 |
| `agent/KnowledgeBaseAgentController.java` | 知识库 Agent 问答接口。 |
| `agent/AgentService.java` | Agent 核心编排；输入检查、记忆、字幕检索、向量检索、rerank、引用、LLM、置信度、持久化。 |
| `agent/AgentAskRequest.java` | Agent 提问请求 DTO。 |
| `agent/AgentAskResponse.java` | Agent 回答响应 DTO，包含答案、引用、trace、置信度等。 |
| `agent/AgentCitation.java` | Agent 引用对象，包含视频 ID、标题、时间戳和文本。 |
| `agent/AgentContextResponse.java` | Agent 上下文响应 DTO，用于前端展示历史和状态。 |
| `agent/AgentTraceStep.java` | Agent 执行链路步骤 DTO。 |
| `agent/ChatMessage.java` | 聊天消息领域对象，对应 `chat_message`。 |
| `agent/ChatMessageRepository.java` | 聊天历史表访问。 |

## Agent 缓存和记忆

| 文件 | 职责 |
| --- | --- |
| `agent/cache/AgentAnswerCache.java` | Agent 答案缓存接口。 |
| `agent/cache/LocalAgentAnswerCache.java` | 本地内存答案缓存实现。 |
| `agent/cache/RedisAgentAnswerCache.java` | Redis 答案缓存实现，用于降低重复 LLM 成本。 |
| `agent/memory/AgentShortTermMemory.java` | Agent 短期记忆接口。 |
| `agent/memory/LocalAgentShortTermMemory.java` | 本地内存短期记忆实现。 |
| `agent/memory/RedisAgentShortTermMemory.java` | Redis 短期记忆实现。 |

## Embedding、向量检索和 rerank

| 文件 | 职责 |
| --- | --- |
| `agent/retrieval/EmbeddingProvider.java` | Embedding Provider 接口。 |
| `agent/retrieval/LocalHashEmbeddingProvider.java` | 本地 hash embedding fallback，保证无外部 key 也能跑通链路。 |
| `agent/retrieval/OpenAiCompatibleEmbeddingProvider.java` | OpenAI-compatible Embedding 客户端，可接 Qwen/OpenAI/BGE 服务。 |
| `agent/retrieval/DeepSeekEmbeddingProvider.java` | DeepSeek Embedding 兼容尝试/遗留实现；1.0 口径是 DeepSeek 只保留 Chat LLM。 |
| `agent/retrieval/EmbeddingProviderConfig.java` | Embedding Provider 配置领域对象。 |
| `agent/retrieval/EmbeddingProviderController.java` | Embedding Provider 保存、启用、测试、列表接口。 |
| `agent/retrieval/EmbeddingProviderRepository.java` | Embedding Provider 表访问。 |
| `agent/retrieval/EmbeddingProviderResponse.java` | Embedding Provider 响应 DTO。 |
| `agent/retrieval/EmbeddingProviderSaveRequest.java` | 保存 Embedding Provider 请求 DTO。 |
| `agent/retrieval/EmbeddingProviderService.java` | Embedding Provider 管理和调用编排。 |
| `agent/retrieval/EmbeddingProviderTestResponse.java` | Embedding 测试响应 DTO。 |
| `agent/retrieval/TranscriptVectorSearch.java` | 字幕向量搜索服务，连接 embedding、Qdrant 和候选结果。 |
| `agent/retrieval/QdrantVectorStore.java` | Qdrant 客户端；collection 检查、upsert、search、状态诊断。 |
| `agent/retrieval/AgentRerankService.java` | 本地 rerank 服务，对关键词和向量候选排序并过滤低质量证据。 |
| `agent/retrieval/VectorIndexController.java` | 向量索引状态和重建接口。 |
| `agent/retrieval/VectorIndexService.java` | 字幕向量索引构建和重建编排。 |
| `agent/retrieval/VectorIndexRebuildResponse.java` | 向量索引重建响应 DTO。 |
| `agent/retrieval/VectorIndexStatusResponse.java` | 向量索引状态响应 DTO。 |

## 多视频知识库

| 文件 | 职责 |
| --- | --- |
| `knowledge/KnowledgeBase.java` | 知识库领域对象。 |
| `knowledge/KnowledgeBaseController.java` | 知识库创建、删除、详情、视频增删接口。 |
| `knowledge/KnowledgeBaseCreateRequest.java` | 创建知识库请求 DTO。 |
| `knowledge/KnowledgeBaseDetailResponse.java` | 知识库详情响应 DTO，包含关联视频。 |
| `knowledge/KnowledgeBaseRepository.java` | `knowledge_base` 和 `knowledge_base_video` 表访问。 |
| `knowledge/KnowledgeBaseService.java` | 知识库业务逻辑；创建、删除、添加/移除视频、查询。 |
| `knowledge/KnowledgeBaseVideoRequest.java` | 添加或移除知识库视频请求 DTO。 |

## MySQL、Redis、JVM 和运行时诊断

| 文件 | 职责 |
| --- | --- |
| `runtime/RuntimeStatusController.java` | Runtime 状态接口，聚合数据库、Redis、Qdrant、LLM、Embedding 等状态。 |
| `runtime/RuntimeStatusResponse.java` | Runtime 状态响应 DTO。 |
| `mysql/MysqlExplainController.java` | MySQL EXPLAIN 诊断接口。 |
| `mysql/MysqlExplainService.java` | 关键 SQL 查询计划诊断，面向 MD5、字幕、任务、分页等面试钩子。 |
| `mysql/MysqlExplainPlan.java` | 单条查询计划对象。 |
| `mysql/MysqlExplainResponse.java` | EXPLAIN 响应 DTO。 |
| `redis/RedisInspectController.java` | Redis key 诊断接口。 |
| `redis/RedisInspectService.java` | Redis 防重、进度、限流、缓存、记忆 key 的可观测聚合。 |
| `redis/RedisInspectResponse.java` | Redis 诊断响应 DTO。 |
| `redis/RedisKeyInspectItem.java` | 单个 Redis key 诊断项。 |
| `jvm/ThreadPoolInspectorController.java` | DAG 线程池诊断接口。 |
| `jvm/ThreadPoolInspectorResponse.java` | 线程池状态响应 DTO。 |

## 限流

| 文件 | 职责 |
| --- | --- |
| `ratelimit/AgentRateLimiter.java` | Agent 限流接口。 |
| `ratelimit/LocalAgentRateLimiter.java` | 本地内存限流实现。 |
| `ratelimit/RedisAgentRateLimiter.java` | Redis 限流实现，用于高频问答保护。 |

## 后端测试

| 文件 | 职责 |
| --- | --- |
| `apps/api/src/test/java/com/omnivid/api/ApiApplicationTests.java` | Spring Boot 上下文加载测试。 |
| `apps/api/src/test/java/com/omnivid/api/transcript/SubtitleTextSanitizerTests.java` | 字幕清洗、简体化、乱码和术语修复测试。 |
| `apps/api/src/test/java/com/omnivid/api/transcript/TranscriptContextRepairServiceTests.java` | 上下文修复和术语词库修复测试。 |

## 前端工作台

| 文件 | 职责 |
| --- | --- |
| `apps/web/package.json` | 前端依赖和脚本；React 19、Vite、TypeScript、lucide-react。 |
| `apps/web/package-lock.json` | npm 依赖锁定文件，保证安装版本可复现。 |
| `apps/web/index.html` | Vite HTML 入口。 |
| `apps/web/tsconfig.json` | TypeScript 配置。 |
| `apps/web/vite.config.ts` | Vite 配置。 |
| `apps/web/CODEX.md` | 前端目录级架构说明。 |
| `apps/web/src/vite-env.d.ts` | Vite TypeScript 环境声明。 |
| `apps/web/src/main.tsx` | 前端主入口和主要工作台逻辑；API client、上传、URL 导入、视频播放器、字幕、总结/Agent 切换、LLM/Embedding 配置、诊断台、知识库管理、SSE 进度、引用跳转都集中在此。 |
| `apps/web/src/styles.css` | 前端整体样式；暗色专业工作台、三列布局、滚动字幕、右侧交互面板、诊断台、LLM、视频库、知识库和 Agent trace。 |

## 脚本

| 文件 | 职责 |
| --- | --- |
| `scripts/start-api-docker.ps1` | 默认后端启动脚本；启动 Docker Compose MySQL/Redis/Qdrant，释放 8080 旧进程，使用 docker profile 启动 Spring Boot，并轮询 runtime status。 |
| `scripts/ocr_burned_subtitles.py` | OCR 画面硬字幕辅助脚本，供后端 `BurnedSubtitleOcrService` 调用。 |

## 基础设施

| 文件 | 职责 |
| --- | --- |
| `infra/docker-compose.yml` | MySQL 8.4、Redis 7.4、Qdrant 1.14.1 容器、端口、健康检查和持久卷。 |
| `infra/README.md` | Docker 基础设施说明。 |

## 文档体系

| 文件 | 职责 |
| --- | --- |
| `docs/01-career-architecture.md` | 求职型项目架构定位。 |
| `docs/02-mysql-redis-hooks.md` | MySQL/Redis 初版技术钩子。 |
| `docs/03-backend-agent-playbook.md` | Java 后端 + Agent 面试打法。 |
| `docs/04-interview-hook-map.md` | 全技术栈八股映射速查。 |
| `docs/05-mysql-interview-hooks.md` | MySQL 专项面试手册。 |
| `docs/06-implemented-features-tech-doc.md` | 已实现功能技术文档。 |
| `docs/06-redis-interview-hooks.md` | Redis 专项面试手册。 |
| `docs/07-java-concurrency-interview-hooks.md` | Java 并发和线程池面试手册。 |
| `docs/08-spring-transaction-interview-hooks.md` | Spring 事务面试手册。 |
| `docs/09-ai-agent-rag-interview-hooks.md` | AI Agent/RAG 面试手册。 |
| `docs/10-task-retry-interview-hooks.md` | 任务失败恢复和重试面试手册。 |
| `docs/11-frontend-workbench-refactor.md` | 前端工作台重构蓝图。 |
| `docs/archive/pre-v1.0-interview-docs-20260610/` | 旧版文档备份目录，保留历史版本不覆盖。 |
| `docs/v1.0/README.md` | 1.0 文档总入口。 |
| `docs/v1.0/acceptance-checklist.md` | 1.0 黑盒验收清单。 |
| `docs/v1.0/technical-architecture.md` | 1.0 技术架构文档。 |
| `docs/v1.0/interview-pack.md` | 1.0 面试包装、简历钩子和主叙事。 |
| `docs/v1.0/full-interview-question-bank.md` | 1.0 完整面试题库。 |
| `docs/v1.0/feature-implementation-map.md` | 1.0 功能实现地图。 |
| `docs/v1.0/session-backup-2026-06-10.md` | 1.0 会话备份。 |
| `docs/v1.0/roadmap-2.0.md` | 2.0 后续路线。 |
| `docs/v1.0/conversation-and-work-backup.md` | 当前新增：完整对话和工作备份。 |
| `docs/v1.0/code-file-responsibility-map.md` | 当前新增：代码文件职责地图。 |

## 常见改动入口

| 想改的功能 | 优先文件 |
| --- | --- |
| 上传、MD5、DAG 解析 | `VideoController.java`、`VideoService.java`、`VideoRepository.java`、`ProcessingJobRepository.java` |
| 视频播放和 Range | `VideoController.java`、`LocalVideoStorageService.java` |
| ffmpeg/ffprobe | `FfmpegAudioExtractionService.java`、`VideoMetadataProbeService.java` |
| ASR 准确率 | `WhisperAsrService.java`、`SubtitleTextSanitizer.java`、`TranscriptContextRepairService.java`、`TermGlossaryService.java`、`BurnedSubtitleOcrService.java` |
| 字幕查询和跳转 | `TranscriptRepository.java`、`apps/web/src/main.tsx` |
| 结构化总结 | `CloudSummaryService.java`、`SummaryRepository.java`、`apps/web/src/main.tsx` |
| LLM 配置 | `LlmProviderService.java`、`CloudLlmClient.java`、`CloudLlmController.java`、`apps/web/src/main.tsx` |
| Agent 回答策略 | `AgentService.java` |
| Agent 缓存/记忆 | `agent/cache/*`、`agent/memory/*`、`ChatMessageRepository.java` |
| Embedding/Qdrant | `agent/retrieval/*` |
| 多视频知识库 | `knowledge/*`、`KnowledgeBaseAgentController.java`、`AgentService.java` |
| 诊断台 | `runtime/*`、`mysql/*`、`redis/*`、`jvm/*`、`AsrDiagnosticController.java`、`VectorIndexController.java`、`apps/web/src/main.tsx` |
| 前端布局和样式 | `apps/web/src/main.tsx`、`apps/web/src/styles.css` |
| Docker 启动 | `scripts/start-api-docker.ps1`、`infra/docker-compose.yml`、`application-docker.yml` |

## 不要提交的内容

- 真实 DeepSeek/OpenAI/Qwen/BGE API Key。
- B 站/抖音/小红书 cookies。
- `storage/videos` 上传视频文件。
- ffmpeg、ASR、OCR 日志和中间产物。
- Docker MySQL/Redis/Qdrant 数据卷。
- 本地 IDE 缓存。
- 临时截图、浏览器缓存和个人账号信息。

