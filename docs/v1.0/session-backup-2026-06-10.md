# OmniVid 会话备份

备份时间：2026-06-10

## 备份边界

本文件备份当前 Codex 线程中可见的产品需求、技术决策、实现脉络和 1.0 收束结果。Codex 工具可以读取到线程摘要和部分分页记录，但无法导出系统隐藏上下文、内部压缩前 token 全文或所有不可见工具输出。因此这里保存的是“可见会话脉络 + 关键决策 + 成果索引”，不是平台内部原始数据库级聊天转储。

线程信息：

```text
thread id: 019e9c05-7977-7730-85fe-55bb70429fa4
title: 总结 OmniVid 方案路线
workspace: E:\video
remote: https://github.com/lovecandies/OmniVID.git
```

## 原始目标

用户提出 OmniVid：响应式多模态视频语义解析引擎。

核心定位：

- 长视频非结构化数据提纯。
- DAG 异步调度流水线。
- 时间轴感知 RAG。
- 将长视频转成思维导图、博客、会议纪要、PPT 大纲。
- 支持跨视频精准问答。

求职方向：

- 面向 Java 后端开发和 AI Agent 求职。
- 在 MySQL、Redis 基础上扩充 JVM、并发、Spring、MyBatis、MQ、网络、操作系统、AI Agent 等面试钩子。
- 不硬背八股，把考点埋进真实业务链路。

## 需求推进脉络

1. 建立 OmniVid 求职型技术钩子方案。
2. 构建前端工作台页面。
3. 改成暗色系前端风格。
4. 开发后端功能。
5. 允许 Maven/Docker 下载依赖。
6. 运行网页并确认当前可实现能力。
7. 前端从 mock 改为调用真实后端。
8. 接通本地视频上传、MD5、存储、processing job。
9. 接通 ffmpeg 抽音频和 whisper ASR。
10. 修复上传后无明显字幕/总结加载反馈的问题。
11. 增加结构化总结：核心观点、会议纪要、博客大纲、PPT 大纲。
12. 增加 Agent 问答，并要求回答带视频引用。
13. 写 MySQL 面试钩子文档，覆盖应用点、面试问题、话术和简历写法。
14. 逐步补充 Redis、并发、Spring、事务、RAG、失败重试等文档。
15. 增加 B 站/抖音/小红书 URL 解析 MVP。
16. 遇到 B 站 HTTP 412，确认 1.0 不做平台反爬绕过，只给 Cookie/浏览器登录态建议。
17. 接入 DeepSeek LLM。
18. 前端增加 API Key 配置页面。
19. 保存登录过的 LLM API 并显示可用列表。
20. 修正 Agent 无证据时只拒答的问题：改为先说明视频未提及，再调用 LLM 给通用回答。
21. 接入 Embedding、Qdrant、rerank。
22. 明确 DeepSeek 只保留 LLM，Embedding 独立 provider 或 fallback。
23. 启动 Docker MySQL/Redis/Qdrant 模式，并设为后续默认后端启动方式。
24. 强化多视频知识库聚合问答。
25. 增加知识库管理：创建、选择、删除、添加视频、移除视频。
26. 支持引用片段点击跳转视频。
27. 支持多个视频观点对比。
28. 多轮前端布局优化：诊断台、云端 LLM、视频库入口、右侧切换结构化总结/Agent 问答。
29. 处理上传/URL 导入 bug。
30. 调整时间轴字幕为滚动窗口，避免长字幕撑长页面。
31. 优化 ASR 准确率：简体化、英文术语、乱码修复、OCR/ASR 双通道诊断、术语词库。
32. 针对 `38197201623-1-192.mp4` 修复繁体字幕和英文术语问题。
33. 最后收束 1.0：冻结功能，验证所有能力，整理文档，备份会话，上传 GitHub 并定为 version 1.0。

中间大量“继续”“继续刚才中断的操作”“电脑重启了继续”等指令，均表示用户授权按后续路线自动推进，不再逐模块等待确认。

## 关键决策

- 1.0 功能冻结，只做 bug fix、验收、文档和发布。
- 后端启动默认使用 Docker MySQL/Redis/Qdrant 模式。
- DeepSeek 只承担 Chat LLM。
- Embedding 与 LLM 解耦，支持 OpenAI-compatible provider，未配置时 fallback 到本地 hash。
- Qdrant 作为 1.0 外部向量数据库。
- Rerank 1.0 使用本地 rerank，外部 BGE reranker 放入 2.0。
- URL 导入不做反爬绕过，遇到 412/403 只做诊断建议。
- ASR 1.0 目标是无乱码、默认简体、术语纠错、可诊断、可修复，不承诺人工字幕级准确率。
- 浏览器插件、真实 PPT 导出、多用户权限、云部署和 RocketMQ 放入 2.0。

## 1.0 成果摘要

代码成果：

- Spring Boot API。
- React/Vite 前端工作台。
- Docker Compose：MySQL、Redis、Qdrant。
- 本地视频上传与 Range 播放。
- MD5 去重。
- 异步 DAG。
- ffmpeg/ffprobe。
- whisper.cpp ASR。
- 字幕清洗、简体化和术语纠错。
- 结构化总结。
- DeepSeek Provider 管理。
- Agent 单视频问答。
- Qdrant 向量检索。
- 本地 rerank。
- 多视频知识库管理和问答。
- Runtime、MySQL、Redis、JVM、ASR、RAG、Vector 诊断接口。

文档成果：

- 求职型架构文档。
- MySQL/Redis 钩子文档。
- Java 并发、Spring 事务、AI Agent/RAG、任务重试文档。
- 前端重构蓝图。
- 1.0 收束文档包。

验证成果：

- 后端测试通过：8 tests。
- 前端构建通过。
- Docker 后端启动成功。
- Runtime 显示 MySQL/Redis/Qdrant/DeepSeek 可用。
- 视频 4 字幕无乱码/繁体探测命中。
- Agent 单视频问答返回引用。
- Agent 无证据/自我介绍场景不再生硬拒答。
- 临时知识库跨视频问答返回两个视频引用并完成清理。

## GitHub 发布意图

本次会话结束前目标：

1. 将所有 1.0 代码与文档提交到 Git。
2. 推送到 `https://github.com/lovecandies/OmniVID.git`。
3. 创建并推送 `v1.0` 标签。
4. 让 GitHub 仓库成为 OmniVid 1.0 的成果备份和求职展示入口。

## 1.0 文档全量同步追加记录

在用户追问“是否对所有面试文档进行更新”后，确认上一轮只新增了 `docs/v1.0/interview-pack.md`，没有逐个重写旧专项文档。随后用户要求：

```text
旧文档保留备份，基础上继续补全1.0所有文档。一定要详细全面，可以回溯我们所有对话查阅细节。包括功能实现，面试各个技术栈的埋的钩子，面试的问题等等。依旧上传到github,v1.0仓库中。
```

本次追加执行策略：

1. 旧版文档备份到 `docs/archive/pre-v1.0-interview-docs-20260610/`。
2. 新增 `docs/v1.0/feature-implementation-map.md`，按用户视角、后端落点、数据落点、验收方式和面试钩子拆解 1.0 全功能。
3. 新增 `docs/v1.0/full-interview-question-bank.md`，覆盖 MySQL、Redis、Java 并发/JVM、Spring、MyBatis/JDBC、MQ、网络/OS、AI Agent/RAG、ASR/OCR、URL 导入、诊断台和 1.0 边界。
4. 在旧专项文档顶部追加 `Version 1.0 同步补强`，使原文档路径也能直接看到当前实现口径。
5. 更新根 `README.md` 和 `docs/v1.0/README.md`，把完整题库与功能地图加入 GitHub 入口。

## 1.0 连续交接备份追加记录

用户继续要求：

```text
总结一下本video项目我们对话的所有关键性记录。备份一下你所做的所有事情，每个代码文件都做了什么。以便于聊天记录丢失无法继续以后版本的更新，依旧上传到github
```

本次追加执行策略：

1. 新增 `docs/v1.0/conversation-and-work-backup.md`，按时间线备份产品定位、求职方向、前端演进、后端链路、ASR 准确率优化、URL 导入边界、DeepSeek Agent、Embedding/Qdrant/rerank、多视频知识库、诊断台、1.0 收束和 2.0 边界。
2. 新增 `docs/v1.0/code-file-responsibility-map.md`，逐个说明后端 Java 文件、前端文件、脚本、基础设施配置、测试文件和文档文件职责。
3. 更新根 `README.md` 和 `docs/v1.0/README.md`，让 GitHub 首页和 v1.0 文档入口都能直接找到这两份交接文档。
4. 本次只做文档备份和索引更新，不改业务代码，不提交 API Key、Cookie、上传视频或数据库数据。
