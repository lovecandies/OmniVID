# OmniVid 2.0 功能实现地图

## 功能到代码与验收证据

| 功能 | 前端入口 | 后端落点 | 数据/基础设施 | 用户可见验收 |
| --- | --- | --- | --- | --- |
| 分片上传与断点续传 | 左侧视频上传 | `upload/ChunkUploadController`、`ChunkUploadService` | `upload_session`、`upload_part`、本地分片目录 | 中断后仅补传缺失分片，完成后进入解析 |
| MD5 秒传与防重 | 上传状态区 | `VideoService`、`DedupeLockService` | Redis 锁、`uk_video_md5` | 重复上传复用已有视频 |
| RocketMQ 可靠异步 | 诊断台 Runtime/Recovery | `job/mq/*` | `processing_event`、RocketMQ | Broker 恢复后任务自动继续；DLQ 可重投 |
| 视频播放与时间轴 | 中间播放器/字幕区 | `VideoController.media` | HTTP Range、本地媒体存储 | 点击字幕跳转；播放器可拖动 |
| ASR + OCR 默认融合 | 诊断台 Recovery | `WhisperAsrService`、`BurnedSubtitleOcrService`、`VideoService` | ffmpeg、whisper.cpp、OCR | 字幕无乱码/繁体残留，诊断可追溯 |
| 术语词库与字幕修复 | ASR 诊断/词库 | `TermGlossaryService`、`SubtitleTextSanitizer` | `term_glossary_entry` | 技术词修正并参与后续回流 |
| 字幕编辑和版本恢复 | 时间轴字幕区 | `TranscriptVersionRepository`、`VideoService` | `transcript_segment`、`transcript_version` | 编辑、差异、恢复均可操作 |
| 结构化总结 | 右侧“结构化总结” | `CloudSummaryService` | `summary_asset`、DeepSeek | 核心观点/会议纪要/博客/PPT 大纲切换 |
| 当前视频 Agent | 右侧“Agent 问答” | `AgentService`、`AgentController` | Redis/MySQL/Qdrant/DeepSeek | 回答带时间戳引用和执行链路 |
| 多视频知识库 | Agent 知识库模式 | `KnowledgeBaseService`、`KnowledgeBaseAgentController` | `knowledge_base`、`knowledge_base_video` | 聚合问答、覆盖统计、观点对比 |
| Embedding Provider | 右上角 Embedding | `EmbeddingProviderService` | `embedding_provider_config`、Qdrant | 保存、测试、切换、轮换、降级 |
| Rerank Provider | 右上角 Rerank | `RerankProviderService`、`AgentRerankService` | `rerank_provider_config` | 远程重排或自动回退本地重排 |
| Provider Key 安全 | LLM/Embedding/Rerank 面板 | `ProviderSecretService` | AES-GCM、MySQL | 页面只显示 mask，可轮换/禁用/删除 |
| Qdrant 向量索引 | 诊断台 AI/RAG | `QdrantVectorStore`、`VectorIndexService` | Qdrant collection | 可查看状态并一键重建 |
| 真实文件导出 | 结构化总结下载按钮 | `export/*` | DeepSeek、Apache POI | 下载可打开的 Markdown/DOCX/PPTX |
| SSE 进度 | 上传/DAG 状态区 | `VideoController.progressStream` | SSE、Redis 进度缓存 | 任务阶段实时变化 |
| MySQL/Redis/JVM Inspector | 诊断台 | `mysql/*`、`redis/*`、`jvm/*` | EXPLAIN、Redis inspect、线程池 | 可演示索引、Key 和线程池 |
| Trace 与结构化日志 | 诊断台 Runtime | `observability/*` | JSON logs、MDC、`X-Trace-Id` | 请求响应和 MQ 日志可串联 |
| 完整 Docker 部署 | 启动脚本 | API/Web Dockerfile | Compose、Nginx、GitHub Actions | 一个脚本拉起全部服务 |

## 主链路状态机

```text
上传会话 CREATED
  -> 分片 UPLOADED
  -> 文件 MERGED
  -> video_asset READY/PROCESSING
  -> processing_job PENDING/RUNNING
  -> processing_event PENDING/PUBLISHED/CONSUMING/CONSUMED
  -> ffmpeg 音频
  -> ASR 字幕
  -> OCR 保守融合
  -> summary_asset
  -> Qdrant 索引
  -> processing_job DONE
```

失败路径：

```text
发布失败 -> Outbox 退避重试
消费失败 -> RocketMQ 重试 -> 应用级 DLQ
解析失败 -> processing_job FAILED -> 人工 retry
Provider 失败 -> 本地 Embedding/Rerank/总结兜底
```

## 字幕回流闭环

```text
字幕人工编辑或 OCR 写回
  -> 保存旧字幕快照
  -> 更新 transcript_segment
  -> 重新生成 summary_asset
  -> 重建 Qdrant 向量
  -> 清理当前视频与所属知识库 Agent 缓存
  -> 后续问答使用新字幕
```

## 2.0 未实现项

| 项目 | 原因 | 后续版本 |
| --- | --- | --- |
| 登录与多租户隔离 | 2.0 优先收束解析可靠性和 AI 回流闭环 | 2.1 |
| 云 KMS / Vault | 当前使用应用级 AES-GCM | 2.1+ |
| 对象存储和 CDN | 当前共享本地持久化目录足以演示 | 2.1+ |
| 独立解析 Worker 集群 | 当前 RocketMQ Consumer 仍在单体 API 中 | 2.1+ |
| Kubernetes / APM | 当前 Compose + JSON Trace 已满足演示 | 3.0 |


## ASR VAD 提速

| 功能 | 前端入口 | 后端落点 | 数据/基础设施 | 用户可见验证 |
| --- | --- | --- | --- | --- |
| ASR VAD 提速 | 诊断台 ASR | `FfmpegAudioExtractionService`、`WhisperAsrService`、`AsrDiagnosticService` | `audio-raw.wav`、`audio.wav`、`audio-vad.wav`、`audio-vad-map.json` | 长停顿视频转写更快，字幕仍能跳回原视频时间点 |
