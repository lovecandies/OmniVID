# OmniVid Version 2.0 实施日志

更新时间：2026-06-12

## 已完成：Provider API Key 加密与轮换

后端落点：

- `ProviderSecretService`：统一使用 AES-GCM 加密 Provider API Key。
- `LlmProviderService`：LLM Provider 保存、读取、轮换时使用加密服务。
- `EmbeddingProviderService`：Embedding Provider 保存、读取、轮换时使用加密服务。
- `RerankProviderService`：Rerank Provider 保存、读取、轮换时使用加密服务。
- 兼容 1.0 旧数据：旧 Base64 字段仍可读取，新保存数据写成 `enc:v1:`。

前端落点：

- 云端 LLM 面板支持 Rotate、Disable、Delete。
- Embedding 面板支持 Rotate、Disable、Delete。
- 新增 Rerank 面板，支持保存、测试、启用、轮换、禁用、删除。

验证结果：

- 单元测试验证新加密值不包含明文，并可读取旧 Base64。
- 黑盒验证 Rerank Provider 保存后，MySQL 中 `api_key_encoded` 以 `enc:v1:` 开头，且不包含测试明文 key。

## 已完成：分片上传、断点续传、大文件恢复

后端落点：

- `upload_session`：保存上传会话、文件 MD5、文件大小、分片大小、总分片数、状态和已上传字节数。
- `upload_part`：保存每个分片的编号、大小、MD5 和本地存储路径。
- `ChunkUploadController`：创建会话、查询状态、上传分片、完成合并。
- `ChunkUploadService`：校验分片大小、缺失分片、合并完整文件、校验完整 MD5，并复用原 `completeStoredUpload` 解析链路。
- `LocalVideoStorageService`：新增分片落盘和分片合并能力。

前端落点：

- 本地视频上传改为分片上传。
- 浏览器侧使用 `spark-md5` 对文件做增量 MD5。
- 默认 8MB 一个分片。
- 后端返回缺失分片时，前端只补传缺失分片。
- 合并完成后仍进入原视频解析 DAG，页面无需改动后续展示链路。

验证结果：

- 黑盒验证创建 session、上传 part、complete 合并成功。
- complete 后创建视频解析任务并进入 DONE。
- 测试产生的临时视频、session、part 和本地文件已清理。

## 已完成：真实 Embedding 与外部 Rerank 增强

后端落点：

- Embedding Provider 继续支持 Qwen/OpenAI/BGE OpenAI-compatible 配置。
- Embedding Provider 增加轮换、禁用、删除接口。
- 新增 `rerank_provider_config` 表。
- 新增 Rerank Provider 保存、启用、测试、轮换、禁用、删除接口。
- `AgentRerankService` 从启动配置升级为运行时动态配置。
- 删除或禁用远端 Rerank Provider 后自动回到 `local-rerank`。

前端落点：

- 右上角新增 Rerank 入口。
- Rerank 面板显示 runtime provider 和 diagnostic。
- Agent trace 仍展示 `RerankTool` 的 provider、topK、keywordScore、rerankScore 和 diagnostic。

验证结果：

- Runtime 显示 MySQL/Redis/Qdrant 可用。
- Runtime 显示 `rerankProvider=local-rerank`，`rerankDiagnostic=local rerank active`。
- 向量索引重建成功：11 个视频、954 条字幕、949 条写入。

## 已完成：ASR + OCR 默认融合流水线

后端落点：

- 视频 ASR 完成后默认进入保守 OCR 融合节点。
- 默认配置为 `OMNIVID_OCR_AUTO_FUSION_ENABLED=true`、`OMNIVID_OCR_AUTO_FUSION_MODE=conservative`。
- 仅在 OCR 证据可靠时替换字幕，避免用画面噪声覆盖高质量 ASR。
- 融合产生修改时自动重新生成结构化总结、重建 Qdrant 向量索引并清理 Agent 语义缓存。
- ASR 诊断接口展示默认融合开关和当前模式。

验证结果：

- Docker 模式下 `38197201623-1-192.mp4` 诊断接口确认自动融合已开启。
- 字幕质量诊断无乱码风险、无替换字符、无繁体字残留。
- 原始 ASR、FFmpeg、OCR 与任务状态均可从诊断台追溯。

## 已完成：字幕编辑、版本管理和回流

后端落点：

- 新增 `transcript_version` 快照表，保存版本号、来源、说明和完整字幕快照。
- 新增字幕编辑、版本列表、版本差异详情和版本恢复接口。
- 编辑与恢复前自动保存回滚点。
- 字幕发生变化后自动重新生成总结、重建向量索引、清理当前视频和所属知识库的 Agent 缓存。

前端落点：

- 时间轴字幕支持选中、编辑和保存。
- 字幕版本区支持查看差异、定位变化片段和恢复历史版本。
- 保存或恢复后页面刷新为最新字幕、总结和版本状态。

验证结果：

- 在测试视频上完成“编辑 -> 查看差异 -> 恢复”黑盒验收。
- 差异接口准确识别 1 条修改；恢复后字幕内容与原始内容逐字一致。
- MySQL 中保留编辑前和恢复前两个可追溯版本。

## 已完成：多视频知识库增强

后端落点：

- 新增知识库覆盖统计接口，展示视频数、就绪数、字幕片段数和总时长。
- 新增多视频观点对比接口，按视频提取观点、共享主题、差异和时间戳引用。
- 字幕变更时清理默认知识库和所属自定义知识库的 Agent 语义缓存。

前端落点：

- 知识库管理区展示覆盖统计。
- 支持一键生成观点对比报告。
- 逐视频观点附带可点击时间戳引用，可跳转到对应视频片段。

验证结果：

- 临时知识库聚合 2 个现有视频、225 条字幕。
- 成功生成 2 份逐视频观点、6 个时间戳引用和共享主题。
- 验收完成后临时知识库已自动删除。

## 已完成：RocketMQ 可靠异步任务升级

后端落点：

- Docker 模式将本地线程池调度升级为 RocketMQ，H2/测试模式保留本地异步执行。
- 新增 MySQL Outbox 表 `processing_event`，任务创建与事件写入处于同一事务，消息体只传递事件 ID。
- Outbox Publisher 支持发布失败退避重试；Broker/JVM 暂时不可用时，事件不会丢失。
- Consumer 使用数据库 CAS 将事件推进为 `CONSUMING`，防止并发重复消息同时执行同一个解析任务。
- 应用重启时恢复中断消费；消费失败进入 RocketMQ 重试，达到上限进入应用级 DLQ。
- 新增事件列表、RocketMQ 状态和 DLQ 人工重投接口。
- 诊断台展示 RocketMQ 连接、待投递、消费完成和 DLQ 数量。

验证结果：

- Docker 模式下 MySQL、Redis、Qdrant、RocketMQ NameServer、Broker、Publisher 与 Consumer 全部连通。
- 主动停止 Broker 后创建解析任务，事件保留在 MySQL 并按退避策略重试；Broker 恢复后同一事件自动完成。
- 人工重复发送同一 `eventId` 后，视频版本、任务版本、更新时间和总结数量均未变化。
- 构造必然失败的临时消息后，事件经过 3 次消费进入 `DLQ`；人工重投接口将其恢复为 `PENDING`，测试数据随后清理。
- 新增 Outbox 状态机测试，完整测试结果为 12 个测试、0 失败。

## 已完成：Docker / CI / 部署复现与结构化 Trace

后端落点：

- 新增结构化 JSON 日志，关键字段包含 `traceId`、HTTP 方法、路径、状态码、耗时、`videoId`、`jobId` 和 `eventId`。
- 新增 `TraceFilter`，支持客户端传入 `X-Trace-Id`，响应头返回同一 traceId。
- `ProcessingCommand` 携带 traceId，HTTP -> MySQL Outbox -> RocketMQ -> Consumer -> DAG 日志可串联。
- API Runtime 新增 observability 状态，展示日志格式、Trace Header 和当前请求 traceId。

基础设施落点：

- 新增 API 多阶段 Dockerfile，运行镜像内置 Java 21、ffmpeg、Python 和 yt-dlp。
- 新增 Web 多阶段 Dockerfile，Nginx 托管静态资源并同源代理 `/api`。
- `infra/docker-compose.yml` 新增 `app` profile，完整拉起 API + Web，同时保留默认 infra-only 开发模式。
- 新增 `infra/.env.example`、`scripts/start-full-docker.ps1` 和 `scripts/ci-local.ps1`。
- 新增 GitHub Actions：后端测试、前端构建、compose 校验和 API/Web 镜像构建。

验证结果：

- `docker compose --profile app config --quiet` 作为 CI 和本地复现的配置校验入口。
- 完整 `app` profile 已黑盒启动，Web、API、MySQL、Redis、Qdrant、RocketMQ NameServer 与 Broker 均正常运行，API/Web 健康检查通过。
- Runtime 面板可以展示 Trace/JSON logs 状态，MySQL、Redis、Qdrant 和 RocketMQ 均显示 connected。
- 手动 `X-Trace-Id` 请求可通过响应头、Nginx 代理和 API JSON 日志关联。
- 构造失败事件后，`traceId` 可串联 Outbox 发布、三次 RocketMQ 消费和最终 DLQ；测试数据已清理。
- 不可解析的 Outbox 毒消息会被隔离到 DLQ，不会阻塞后续发布批次。
- TraceFilter 在请求结束时恢复 MDC，避免线程复用污染后续日志。
- 后端完整测试为 13 个测试、0 失败，前端生产构建、Compose 校验和 Docker 镜像构建均通过。

## 当前验证命令

```powershell
cd E:\video\apps\api
.\mvnw.cmd test

cd E:\video\apps\web
npm run build

cd E:\video
.\scripts\start-full-docker.ps1
Invoke-RestMethod http://localhost:8080/api/runtime/status | ConvertTo-Json -Depth 6
```

## 已完成：ASR VAD 提速

后端落点：

- `FfmpegAudioExtractionService`：完整音频写入 `audio-raw.wav`，再用 ffmpeg `silencedetect` 识别静音区间并生成裁剪后的 `audio.wav` / `audio-vad.wav`。
- `AudioVadMap` / `AudioVadSegment`：记录源音频区间与转写音频区间的映射，落盘为 `audio-vad-map.json`。
- `WhisperAsrService`：默认转写 `audio-vad.wav`，并在解析 `asr.json` 后把字幕时间轴映射回原视频。
- `AsrDiagnosticService`：诊断接口新增 VAD 音频、映射文件和片段数量。

前端落点：

- ASR Diagnostic 面板新增 `VAD Audio` 和 `VAD Map` 状态格，能看到 `audio-vad.wav` 是否存在、映射是否生效以及片段数量。

验证目标：

- 上传或重跑含长停顿的视频后，视频目录应同时存在 `audio-raw.wav` 与 `audio-vad.wav`。
- VAD 生效时 `audio-vad.wav` 时长明显短于 `audio-raw.wav`，`ASR_TRANSCRIBING` 等待时间随输入音频缩短。
- 点击字幕仍跳回原视频真实时间点。
- 静音检测失败或收益太小时自动回退完整音频，不中断解析任务。

## 已完成：PPTX / DOCX / Markdown 真实导出

后端落点：

- 新增统一 `ExportDocument` 内容模型，详细章节、行动项和时间戳来源在三种格式中保持一致。
- 基于视频字幕和所选结构化总结调用 DeepSeek 扩写详细会议纪要、核心观点报告、博客或汇报稿。
- DeepSeek 不可用或 JSON 不完整时，自动生成不编造事实的本地兜底文档。
- 使用 Apache POI 真实渲染 DOCX 与 PPTX；Markdown 使用 UTF-8 输出。
- 同一视频、同一总结类型和同一字幕版本复用已生成文档，避免连续下载三种格式时重复消耗 Token。

前端落点：

- 结构化总结面板新增 Markdown、DOCX、PPTX 三个下载入口。
- 生成状态会展示文件名以及 `DeepSeek` 或 `本地结构化兜底` 模式。

验证结果：

- Apache POI 可重新打开生成的 DOCX 和 PPTX，Markdown 包含标题、摘要、章节、行动项和来源片段。
- 后端完整测试增加到 16 个测试、0 失败；前端生产构建通过。
- Docker 模式对 `AI_Agent_Docker.mp4` 真实调用 `deepseek-chat` 生成会议纪要：Markdown 包含 9 个详细章节，DOCX 包含标题/行动项/来源片段，PPTX 共 13 页。
- 首次 DeepSeek 生成约 25.7 秒；随后 DOCX、PPTX 复用同一文档，各约 2 秒完成，避免重复 Token 消耗。

## 已完成：Docker 媒体存储一致性修复

- Docker API 从空命名卷切换为 bind mount `apps/api/storage`。
- 本地 Maven 与 Docker API 共享同一份历史视频和解析产物。
- 黑盒检查视频库 11 条媒体记录均返回 `206 Partial Content`。
- 浏览器点击历史视频成功加载，播放器 `readyState=4`，不再出现 `Video file not found`。

## 2.0 正式版文档收束

- 新增发布说明、技术架构、功能实现地图、API/数据地图。
- 新增面试主叙事、简历钩子和完整面试题库。
- 新增正式验收报告和续作交接备份。
- 原 2.0 路线、蓝图和验收计划改为正式发布口径。
- 登录/多租户、对象存储、独立 Worker 和 APM 明确推迟到 2.1+。

## 2.1+ 后续方向

1. 登录、用户隔离与知识库权限边界。
2. 对象存储、上传会话清理和导出资产持久化。
3. 将解析 Worker 独立部署并支持水平扩容。
4. 建立 Embedding/Rerank 与 ASR/OCR 离线评估集。
5. 接入 Prometheus、Grafana 和 OpenTelemetry。
