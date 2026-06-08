# OmniVid

OmniVid 是一个面向 Java 后端开发和 AI Agent 求职展示的长视频语义解析项目。它把“长视频上传、去重、异步解析、字幕时间轴、结构化总结、跨视频问答”串成一条可演示的工程链路，同时把 MySQL、Redis、JVM、并发、Spring、RAG、向量数据库等面试高频考点埋进真实业务场景里。

当前项目已经从前端 mock 演进为前后端真实联调版本：本地视频上传后，后端会保存文件、计算 MD5、创建解析任务、抽取音频、执行 ASR、生成字幕和总结，并支持带时间戳引用的 Agent 问答。

## 当前成果

| 模块 | 状态 | 可见效果 |
| --- | --- | --- |
| 前端工作台 | 已实现 | 暗色响应式工作台，支持上传、视频库、字幕、总结、Agent 问答和运行时检查面板 |
| 本地视频上传 | 已实现 | 真实文件上传到后端，保存到本地存储目录并计算 MD5 |
| MD5 去重 | 已实现 | 重复视频复用已有资产，Redis/本地锁防抖，MySQL 唯一索引兜底 |
| 异步解析 DAG | 已实现 | 上传后后台执行解析任务，前端展示阶段和进度 |
| ffprobe/ffmpeg | 已实现 | 识别视频时长，抽取 `audio.wav` 供 ASR 使用 |
| whisper.cpp ASR | 已实现 | 字幕区展示真实语音转写片段 |
| 视频播放 | 已实现 | 支持 Range 播放上传视频 |
| 字幕点击跳转 | 已实现 | 点击字幕可跳到播放器对应时间点 |
| 播放同步字幕 | 已实现 | 播放或拖动进度时高亮当前字幕 |
| 结构化总结 | 已实现 | 基于 ASR 生成核心观点、会议纪要、博客大纲、PPT 大纲和面试钩子 |
| DeepSeek Chat | 已实现 | 前端可保存并启用 DeepSeek API Key，总结和 Agent 可调用云端 LLM |
| 当前视频 Agent | 已实现 | 针对当前视频提问，返回回答、执行轨迹和可点击时间戳引用 |
| 默认知识库 Agent | 已实现 | 跨已上传视频检索字幕并回答 |
| MySQL 模式 | 已实现 | Docker profile 下视频、任务、字幕、总结、聊天记录落 MySQL |
| Redis 模式 | 已实现 | 处理防重锁、进度缓存、限流、语义缓存和短期记忆 |
| Qdrant 向量库 | 已实现 MVP | 字幕向量写入 Qdrant，支持向量召回和 rerank trace |
| SSE 实时进度 | 已实现 | 前端通过长连接观察任务状态变化 |
| 失败任务恢复 | 已实现 | 可查看失败任务并重新投递解析 |
| 运行时检查面板 | 已实现 | MySQL、Redis、JVM 线程池、SSE、Retrieval、Vector Store 可观测 |
| 平台 URL 导入 | MVP 已实现 | B站/抖音/小红书公开链接通过 `yt-dlp` 复用解析链路，受平台反爬和 Cookie 影响 |
| URL 导入诊断 | 已实现 | B站 412、403、Cookie 缺失、yt-dlp 缺失、ffmpeg 合并失败会返回 `message/suggestion/detail` |
| ASR 诊断面板 | 已实现 | 当前视频可查看模型文件、`audio.wav`、`asr.json`、字幕条数、`ffmpeg.log` 和 `asr.log` 摘要 |
| RAG 检索过滤 Trace | 已实现 | Retrieval Inspector 展示 candidates、usable、top hit、citations、rejected 和 strict filter |
| 任务重试边界 | 已实现 | 只有最新 FAILED job 允许补偿重试；DONE/RUNNING 误重试返回结构化建议 |

## 还未作为主线完成

- 浏览器插件暂未实现。
- RocketMQ 暂未接入，当前用本地轻量 DAG；MQ 是后续演进和面试钩子。
- DeepSeek 当前主要接 Chat Completions；其 Embeddings 接口不可用时，项目会降级为本地 hash embedding，但向量仍写入 Qdrant。
- URL 导入对平台反爬敏感，B站 HTTP 412 等场景需要 Cookie 或浏览器登录态支持。
- 登录、多租户、计费、企业权限暂未实现，当前固定为 demo 用户。

## 技术栈

- 后端：Java、Spring Boot 3、JDBC/MyBatis 风格 Repository、Maven
- 数据库：MySQL 8.4、Redis 7.4、Qdrant
- AI 链路：DeepSeek Chat、whisper.cpp、ffmpeg、ffprobe、RAG、Agent trace
- 前端：React、Vite、TypeScript、lucide-react
- 基础设施：Docker Compose、SSE、HTTP Range、本地文件存储

## 本地启动

启动基础设施：

```powershell
cd E:\video\infra
docker compose up -d
```

启动后端：

```powershell
cd E:\video\apps\api
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=docker"
```

启动前端：

```powershell
cd E:\video\apps\web
npm run dev -- --host 127.0.0.1
```

访问：

```text
http://127.0.0.1:5173
```

## 黑盒验证

1. 上传本地视频 -> 验证: 页面出现新视频、任务进度从上传进入音频抽取、ASR、总结生成，最终字幕和总结加载出来。
2. 点击字幕 -> 验证: 播放器跳转到字幕对应时间，播放时字幕高亮跟随变化。
3. 配置 DeepSeek -> 验证: 在页面保存 API Key 后，连接测试通过，Runtime 面板显示 LLM 可用。
4. 向 Agent 提问视频内容 -> 验证: 回答带来源视频和时间戳引用，点击引用能定位播放器。
5. 提问视频未提到的问题 -> 验证: Agent 先说明视频或知识库没有检索到相关字幕，再调用 DeepSeek 给出通用回答。
6. 打开检查面板 -> 验证: MySQL 索引、Redis Key、线程池、SSE、Retrieval、Qdrant 状态均可观察。
7. 重复上传同一视频 -> 验证: 页面显示命中去重，MySQL 中不会重复创建同一 MD5 视频资产。
8. 粘贴无法解析的平台链接 -> 验证: URL 导入错误展示建议和日志摘要，例如 Cookie 登录态、yt-dlp 更新或 ffmpeg 配置。
9. 选择已解析视频 -> 验证: ASR Diagnostic 面板显示模型、音频、ASR JSON、字幕行数和日志尾巴。
10. 向知识库 Agent 提问 -> 验证: Retrieval Inspector 展示宽召回候选、rerank 后证据、严格引用过滤和 rejected 数量。
11. 对 DONE 视频点击重试 -> 验证: 后端返回 409，并说明已完成任务不进入补偿队列；对 FAILED 视频重试会创建新的 retry job。

## 面试主叙事

一句话介绍：

> OmniVid 是一个 Java 后端主导的长视频 AI 知识解析系统，我用 Spring Boot 跑通了从大文件上传、MD5 去重、异步解析、ffmpeg/ASR、字幕时间轴、结构化总结到可追溯 Agent 问答的一整条链路。MySQL 负责最终事实和状态一致性，Redis 负责防重、进度缓存、限流和短期记忆，Qdrant 负责字幕向量检索，Agent 通过检索工具和时间戳引用降低幻觉。

遇到八股追问时，可以这样回到业务：

- MySQL：视频 MD5 唯一索引、任务状态机、乐观锁、字幕联合索引、深分页优化。
- Redis：`SETNX` 防重锁、进度缓存、限流、语义缓存、短期记忆、缓存一致性。
- Java 并发：本地 DAG、线程池参数、拒绝策略、异步异常处理、任务重试。
- JVM：大文件上传避免 OOM、字幕切片对象生命周期、GC 日志、`jmap`/`jstack` 排查。
- Spring：统一异常、AOP 日志、事务传播、`@Transactional` 失效、策略路由。
- MQ：当前本地 DAG，后续可演进 RocketMQ，讲可靠消息、重复消费、死信队列和幂等。
- 网络/OS：SSE 长连接、HTTP Range、ffmpeg 子进程、标准输出阻塞、顺序 IO。
- AI Agent：RAG、Embedding、向量召回、rerank、工具调用、引用约束和低置信度处理。
- URL 导入：`yt-dlp` 子进程、平台反爬、HTTP 412、Cookie 登录态、失败诊断和降级提示。
- ASR 诊断：模型文件、音频抽取产物、JSON 输出、日志摘要、空字幕兜底和子进程超时。
- 任务补偿：FAILED 才能 retry，DONE 走 MD5 复用，RUNNING 依赖 SSE，避免状态机重复推进。

## 简历钩子

- 基于 Spring Boot 构建长视频解析工作台，完成本地视频上传、MD5 去重、异步解析任务、ASR 字幕、结构化总结和视频时间轴跳转。
- 基于 MySQL 设计视频资产、解析任务、字幕片段、总结资产和聊天记录模型，通过唯一索引、联合索引和乐观锁保证幂等与状态一致性。
- 基于 Redis 实现上传防重复提交、任务进度缓存、Agent 限流、语义缓存和短期记忆，降低重复解析与重复推理成本。
- 使用 Java 线程池实现轻量 DAG 解析流水线，并对 ffmpeg、ASR、总结生成等长耗时节点做状态流转、失败记录和重试恢复。
- 接入 DeepSeek Chat 和 Qdrant，构建带工具调用和执行轨迹的 Agent 问答链路，支持跨视频字幕检索、可点击时间戳引用和无证据通用回答兜底。
- 建设 URL/ASR/RAG/Recovery 多个 Inspector 面板，将平台导入失败、ASR 产物、向量召回过滤和任务补偿状态转化为可观测、可演示、可面试追问的工程证据。

## 文档索引

- [项目蓝图](CODEX.md)
- [求职型架构](docs/01-career-architecture.md)
- [MySQL/Redis 技术钩子](docs/02-mysql-redis-hooks.md)
- [后端 Agent 面试打法](docs/03-backend-agent-playbook.md)
- [全技术栈八股映射](docs/04-interview-hook-map.md)
- [MySQL 面试钩子手册](docs/05-mysql-interview-hooks.md)
- [已实现功能技术文档](docs/06-implemented-features-tech-doc.md)
- [Redis 面试钩子手册](docs/06-redis-interview-hooks.md)
- [Java 并发与线程池手册](docs/07-java-concurrency-interview-hooks.md)
- [Spring 事务手册](docs/08-spring-transaction-interview-hooks.md)
- [AI Agent RAG 手册](docs/09-ai-agent-rag-interview-hooks.md)
- [任务失败恢复手册](docs/10-task-retry-interview-hooks.md)
