# OmniVid Version 1.0 收束说明

更新时间：2026-06-10

## 1.0 定位

OmniVid 1.0 是面向 Java 后端开发与 AI Agent 求职展示的可运行版本。1.0 不再继续扩展大功能，重点是把已有链路稳定、验收、文档化，并把后续想象力放入 2.0 路线。

一句话介绍：

> OmniVid 是一个长视频 AI 知识解析工作台，支持本地视频上传、MD5 去重、异步 DAG 解析、ffmpeg 抽音频、whisper ASR、时间轴字幕、结构化总结、DeepSeek 问答、Qdrant 向量检索和多视频知识库问答。项目把 MySQL、Redis、JVM、并发、Spring、RAG、向量数据库和 Agent 工具链这些面试高频考点埋进同一条业务链路。

## 1.0 已冻结功能

| 模块 | 1.0 状态 | 验收结果 |
| --- | --- | --- |
| Docker 运行模式 | 已完成 | `profile=docker`，MySQL/Redis/Qdrant 均已连通 |
| 本地视频上传 | 已完成 | 视频库已有 11 条记录，其中 10 条 READY，1 条历史失败样本 |
| MD5 去重 | 已完成 | MySQL `uk_video_md5` 兜底，Redis/本地锁防抖 |
| 异步 DAG | 已完成 | 任务状态、进度、失败恢复、线程池诊断可见 |
| ffmpeg/ffprobe | 已完成 | 抽取 `audio.wav`，记录 ffmpeg 日志 |
| whisper ASR | 已完成 | 真实字幕入库，支持清洗、简体化、术语纠错和诊断 |
| 时间轴字幕 | 已完成 | 字幕列表、搜索、点击跳转、播放同步高亮 |
| 结构化总结 | 已完成 | 核心观点、会议纪要、博客大纲、PPT 大纲、面试钩子 |
| DeepSeek LLM | 已完成 | 作为 Chat LLM 接入，支持保存 Provider、启用、测试和 Agent 调用 |
| Embedding/向量检索 | 1.0 MVP | Qdrant 外部向量库已接入；Embedding 可配置，默认本地 hash fallback |
| Rerank | 1.0 MVP | 本地 rerank 已进入 Agent trace |
| 单视频 Agent | 已完成 | 命中字幕时返回引用；无视频证据时说明边界后给通用回答 |
| 多视频知识库 | 已完成 | 创建/删除知识库、添加/移除视频、跨视频问答和对比观点 |
| 引用跳转 | 已完成 | Agent 引用包含 `videoId/startMs/endMs`，前端可加载视频并跳转 |
| 诊断台 | 已完成 | Runtime、MySQL、Redis、JVM、ASR、RAG、Qdrant 可观测 |
| URL 导入 | MVP 保留 | 公开链接走 `yt-dlp`；平台反爬、Cookie 和浏览器插件放入 2.0 |

## 1.0 不继续扩展的内容

- 浏览器插件。
- 平台反爬绕过或 B 站/抖音/小红书强鲁棒下载。
- 真实 PPT 文件导出。
- 外部 BGE reranker 生产化。
- 多用户登录、组织权限、计费和审计。
- 云部署、CI/CD 和大规模压测。
- 知识图谱、复杂 Agent 自动规划。

## 1.0 文档包

| 文档 | 用途 |
| --- | --- |
| [验收清单](acceptance-checklist.md) | 从最终用户视角走通 1.0 的黑盒验证 |
| [技术架构](technical-architecture.md) | 说明后端、前端、数据、AI 和诊断链路 |
| [面试包](interview-pack.md) | 简历钩子、面试总叙事和高频追问回答 |
| [2.0 路线](roadmap-2.0.md) | 1.0 之后再做的功能和技术升级 |
| [会话备份](session-backup-2026-06-10.md) | 当前可见会话脉络、关键决策和成果备份 |

## 已有技术文档索引

| 文档 | 当前作用 |
| --- | --- |
| [根目录 CODEX.md](../../CODEX.md) | OmniVid 项目执行纪律、Vibe Coding 协作协议和目录边界 |
| [README.md](../../README.md) | 项目总览、功能表、启动方式、黑盒验证和简历钩子 |
| [apps/api/README.md](../../apps/api/README.md) | 后端运行方式、Docker profile、核心接口和 smoke test |
| [apps/api/CODEX.md](../../apps/api/CODEX.md) | 后端目录级架构说明 |
| [apps/web/CODEX.md](../../apps/web/CODEX.md) | 前端目录级架构说明 |
| [docs/01-career-architecture.md](../01-career-architecture.md) | 求职型项目架构定位 |
| [docs/02-mysql-redis-hooks.md](../02-mysql-redis-hooks.md) | MySQL/Redis 初版业务钩子 |
| [docs/03-backend-agent-playbook.md](../03-backend-agent-playbook.md) | Java 后端与 Agent 面试打法 |
| [docs/04-interview-hook-map.md](../04-interview-hook-map.md) | 全技术栈八股映射速查 |
| [docs/05-mysql-interview-hooks.md](../05-mysql-interview-hooks.md) | MySQL 专项面试手册 |
| [docs/06-implemented-features-tech-doc.md](../06-implemented-features-tech-doc.md) | 已实现功能技术文档 |
| [docs/06-redis-interview-hooks.md](../06-redis-interview-hooks.md) | Redis 专项面试手册 |
| [docs/07-java-concurrency-interview-hooks.md](../07-java-concurrency-interview-hooks.md) | Java 并发与线程池面试手册 |
| [docs/08-spring-transaction-interview-hooks.md](../08-spring-transaction-interview-hooks.md) | Spring 事务面试手册 |
| [docs/09-ai-agent-rag-interview-hooks.md](../09-ai-agent-rag-interview-hooks.md) | AI Agent/RAG 面试手册 |
| [docs/10-task-retry-interview-hooks.md](../10-task-retry-interview-hooks.md) | 任务失败恢复与重试面试手册 |
| [docs/11-frontend-workbench-refactor.md](../11-frontend-workbench-refactor.md) | 前端工作台重构蓝图 |

## 1.0 演示启动

后端默认使用 Docker MySQL/Redis/Qdrant 模式：

```powershell
cd E:\video
.\scripts\start-api-docker.ps1
```

前端启动：

```powershell
cd E:\video\apps\web
npm run dev -- --host 127.0.0.1 --port 5174
```

浏览器访问：

```text
http://127.0.0.1:5174
```

## 1.0 发布口径

1.0 可以对外说“可运行、可演示、可面试追问”，不说“企业级生产可用”。生产化能力放到 2.0，包括权限、部署、稳定 URL 导入、外部 reranker、PPT 文件生成和更严格的数据安全。
