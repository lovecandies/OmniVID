# OmniVid Version 2.0 发布说明

发布日期：2026-06-12

## 一句话成果

OmniVid 2.0 把长视频上传、可靠异步解析、ASR/OCR 字幕校正、可追溯 RAG Agent、多视频知识库和真实文件导出串成了可通过 Docker 一键复现的完整业务链路。

## 相比 1.0 的主要升级

### 可靠性

- 本地上传升级为分片上传、断点续传和完整 MD5 校验。
- Docker 模式使用 MySQL Outbox + RocketMQ 调度解析任务。
- 消费端通过数据库 CAS 抢占事件，处理重复投递。
- 发布失败退避重试，消费失败进入 DLQ，支持人工重投。
- Docker 与本地 Maven 共享 `apps/api/storage`，避免元数据存在但视频文件不可见。

### AI 精度与可回流

- ffmpeg 抽音频默认保留 `audio-raw.wav`，并通过 VAD/静音检测裁剪 `audio-vad.wav`，减少 Whisper 转写无效空白和长停顿。
- Whisper ASR 完成后默认进入保守 OCR 融合。
- 字幕经过简体化、乱码清理、术语归一和上下文修复。
- 低置信片段可二次识别，支持术语词库管理。
- 用户可以编辑字幕、查看版本差异、恢复历史版本。
- 字幕变更后自动重新生成总结、重建向量索引并失效相关 Agent 缓存。

### RAG 与知识库

- Qdrant 成为 Docker 模式下的外部向量数据库。
- 支持运行时保存、测试、启用和轮换 Qwen/OpenAI/BGE Embedding Provider。
- 支持外部 BGE/OpenAI-compatible Rerank Provider，并保留本地重排降级。
- Agent 展示输入检查、记忆、关键词召回、向量召回、重排、引用、LLM 与置信度链路。
- 知识库支持多视频聚合问答、覆盖统计和观点对比。
- 引用片段可点击切换视频并跳转到时间点。

### 文档资产生成

- DeepSeek 根据字幕和结构化总结扩写详细内容。
- 同一内容模型可渲染 Markdown、DOCX 和 PPTX。
- DeepSeek 不可用时使用不编造事实的本地结构化兜底。
- 同一视频、总结类型和字幕版本复用内容生成结果，减少重复 Token 消耗。

### 安全、部署与可观测

- LLM、Embedding、Rerank API Key 使用 AES-GCM 加密保存，前端只显示 mask。
- 支持 Provider Key 轮换、禁用和删除。
- API 与 Web 使用多阶段 Dockerfile，Nginx 同源代理 `/api`。
- GitHub Actions 执行后端测试、前端构建、Compose 校验和镜像构建。
- JSON 日志与 `X-Trace-Id` 串联 HTTP、Outbox、RocketMQ Consumer 和解析 DAG。

## 当前运行态说明

发布时默认 Docker 运行态：

- MySQL、Redis、Qdrant、RocketMQ、API、Web：已连接。
- DeepSeek Chat：可通过前端 Provider 面板配置。
- Embedding：未配置外部 Provider 时自动使用 `local-hash`。
- Rerank：远程 Provider 不可用时自动使用 `local-rerank`。
- Qdrant：即使使用本地 hash embedding，向量仍存储在 Qdrant。

## 明确边界

- 当前没有登录和多租户隔离，固定为 demo 用户。
- 平台 URL 导入受平台反爬、Cookie 和授权状态影响，不绕过 CAPTCHA、DRM 或平台限制。
- ASR/OCR 采用保守融合，不承诺替代人工字幕。
- VAD 当前基于本地 ffmpeg 静音检测，能显著减少空白和长停顿；纯 BGM 是否被裁剪取决于音量阈值，不绕过 Whisper 的最终识别兜底。
- 外部 Embedding/Rerank 的实际效果和可用性取决于用户配置的服务。
- 当前是单体 API + 基础设施容器，没有拆成独立 Worker 微服务。

## 面试价值

2.0 可以围绕同一条业务链路回答：

- MySQL：唯一索引、联合索引、乐观锁、Outbox、本地事务、状态机。
- Redis：防重锁、进度缓存、限流、短期记忆、语义缓存与失效。
- Java/JVM：线程池、异步异常、流式 IO、子进程、OOM 与故障排查。
- RocketMQ：消息可靠性、重复消费、重试、DLQ、最终一致性。
- AI Agent：Embedding、Qdrant、Rerank、引用约束、缓存和人在回路。
- DevOps：Docker 多阶段构建、Compose、CI、健康检查、JSON Trace。

## 升级与回滚

- v1.0 文档、代码历史和 `v1.0` 标签保留。
- 2.0 的数据库初始化通过 `schema-mysql.sql` 使用 `CREATE TABLE IF NOT EXISTS` 增量兼容现有数据。
- Provider 旧 Base64 Key 可继续读取，新保存数据使用 `enc:v1:`。
- Docker 媒体文件固定使用宿主机 `apps/api/storage`，重建 API 容器不会丢失视频。
