# OmniVid Version 2.0 文档中心

发布日期：2026-06-12

## 版本定位

OmniVid 2.0 将 1.0 的长视频 AI 求职 Demo 收束为可部署、可恢复、可观测、可持续校正的准生产级视频知识工作台。

核心闭环：

```text
分片上传/URL 导入
  -> MySQL Outbox + RocketMQ 异步解析
  -> ffmpeg + Whisper ASR + 保守 OCR 融合
  -> 字幕编辑/版本/回流
  -> DeepSeek 结构化总结
  -> Qdrant 检索 + 可选真实 Embedding/Rerank
  -> 当前视频/多视频知识库 Agent
  -> 时间戳引用跳转
  -> Markdown/DOCX/PPTX 真实导出
```

## 发布状态

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| Provider Key 加密、轮换、禁用、删除 | 已实现并验收 | AES-GCM，新数据使用 `enc:v1:`，兼容旧 Base64 |
| 分片上传、断点续传、MD5 合并校验 | 已实现并验收 | 前端默认 8MB 分片 |
| RocketMQ 可靠异步 | 已实现并验收 | MySQL Outbox、CAS 消费、退避重试、DLQ、人工重投 |
| ASR + OCR 默认融合 | 已实现并验收 | 保守写回，字幕变化后自动回流总结/向量/缓存 |
| 字幕编辑、版本和恢复 | 已实现并验收 | 编辑和恢复前自动生成完整快照 |
| 多视频知识库与观点对比 | 已实现并验收 | 覆盖统计、跨视频问答、可点击引用 |
| Qdrant 向量库 | 已实现并验收 | Docker 模式连接 Qdrant，支持重建和维度迁移 |
| 外部 Embedding / Rerank | 管理链路已实现 | 需用户配置 Qwen/OpenAI/BGE 或兼容服务；不可用时本地降级 |
| DeepSeek 总结、Agent、详细导出 | 已实现并验收 | 导出 Markdown、DOCX、PPTX |
| Docker / CI / JSON Trace | 已实现并验收 | 完整 Compose、GitHub Actions、`X-Trace-Id` |
| 登录、多租户、计费 | 推迟到 2.1+ | 当前仍为 demo 用户边界 |

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [发布说明](release-notes.md) | 2.0 成果、边界、升级与已知限制 |
| [技术架构](technical-architecture.md) | 运行架构、数据流、一致性和降级设计 |
| [功能实现地图](feature-implementation-map.md) | 每项功能的前端、接口、数据和验收证据 |
| [API 与数据地图](api-data-map.md) | 核心接口、MySQL 表、Redis Key、Qdrant、RocketMQ |
| [代码职责地图](code-file-responsibility-map.md) | 主要模块、文件职责和测试落点 |
| [实施日志](implementation-log.md) | 2.0 模块落点和逐项验证记录 |
| [正式验收报告](verification-report.md) | 发布前测试、构建和黑盒检查结果 |
| [面试主叙事与钩子](interview-pack.md) | 简历写法、项目介绍、技术栈埋钩子 |
| [完整面试题库](full-interview-question-bank.md) | 高频追问、回答结构和项目证据 |
| [续作交接备份](continuation-backup.md) | 后续版本继续开发所需上下文 |
| [Docker / CI / Observability](deployment-observability.md) | 部署复现、媒体存储和 Trace |
| [RocketMQ 架构](rocketmq-architecture.md) | Outbox、消费幂等、重试与 DLQ |
| [真实文件导出](export-blueprint.md) | DeepSeek 文档扩写和 Office 渲染 |
| [本地备份清单](local-backup-manifest.md) | 本地备份目录索引、公开提交边界和恢复提示 |
| [2.1+ 路线](roadmap.md) | 2.0 收束后续优化 |

## 一键启动

```powershell
cd E:\video
.\scripts\start-full-docker.ps1
```

- Web：`http://127.0.0.1:5174`
- API：`http://127.0.0.1:8080`
- Runtime：`http://127.0.0.1:8080/api/runtime/status`

## 发布验收入口

```powershell
cd E:\video
.\scripts\ci-local.ps1
docker compose -f infra/docker-compose.yml --profile app config --quiet
```

用户视角的完整演示路径：

```text
配置 DeepSeek
  -> 分片上传视频
  -> 查看 RocketMQ 任务进度
  -> 检查 ASR/OCR 字幕
  -> 编辑字幕并查看版本
  -> 生成结构化总结
  -> 创建知识库并对比多个视频
  -> Agent 提问并点击引用跳转
  -> 下载 Markdown/DOCX/PPTX
  -> 在诊断台查看 MySQL/Redis/Qdrant/MQ/Trace
```


## 最近增量

- ASR 抽音频已经改为 VAD 提速链路：保留 `audio-raw.wav`，向 Whisper 仅喂入有效人声片段。
- 当前恢复文件在仓库根目录：`codex-session-019eb1b9-full-chat.md`、`codex-session-019eb1b9-index.md`、`codex-session-019eb1b9-recovered-chat.md`、`codex-session-019eb1b9-tool-timeline.md`。
- 运行时 `backups/` 与 `备份/` 继续本地保留，避免把数据库 dump 和本地产物直接推到 GitHub。
- 后续接续开发优先看 `README.md`、`apps/api/CODEX.md`、`docs/v2.0/continuation-backup.md`。
