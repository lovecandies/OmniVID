# OmniVid 1.0 对话与工作备份

更新时间：2026-06-10

## 备份目的

这份文档用于防止聊天记录丢失后无法继续维护 OmniVid。它保存我们在本项目中已经确认过的产品方向、技术决策、实现过程、关键问题、面试包装方式和后续版本边界。

说明：这里不是平台原始聊天数据库的逐字导出，而是基于当前线程可见上下文整理出的“可恢复工作记录”。如果后续继续开发，应优先读取本文件、`docs/v1.0/technical-architecture.md`、`docs/v1.0/feature-implementation-map.md`、`docs/v1.0/code-file-responsibility-map.md` 和 `docs/v1.0/full-interview-question-bank.md`。

## 项目定位

OmniVid 是面向 Java 后端开发和 AI Agent 求职展示的长视频语义解析项目。它的核心不是单纯做一个视频总结页面，而是把真实工程链路和面试高频技术点放在同一个业务场景里：

```text
长视频上传 -> MD5 去重 -> 异步 DAG 解析 -> ffmpeg 抽音频 -> ASR/OCR 字幕 -> 结构化总结 -> RAG/Agent 问答 -> 时间戳引用 -> 诊断台可观测
```

求职叙事主线：

```text
业务痛点 -> 技术方案 -> 八股关键词 -> 可验证结果
```

一句话介绍：

```text
OmniVid 是一个 Java 后端主导的长视频 AI 知识解析系统，我用 Spring Boot 跑通了从大文件上传、MD5 去重、异步解析、ffmpeg/ASR、字幕时间轴、结构化总结，到可追溯 Agent 问答和多视频知识库聚合的完整链路。MySQL 负责最终事实和状态一致性，Redis 负责防重、进度缓存、限流和短期记忆，Qdrant 负责字幕向量检索，Agent 通过工具调用和时间戳引用降低幻觉。
```

## 关键对话时间线

### 1. 原始产品设想

用户提出 OmniVid：响应式多模态视频语义解析引擎。

最初要求包括：

- 全平台 URL 解析：B 站、YouTube、抖音、小红书。
- 本地 GB 级视频上传。
- MD5 指纹去重和秒传。
- 一键生成思维导图、结构化笔记、会议纪要、PPT 大纲、博客。
- DeepSeek、Qwen、GPT-4o 等模型路由。
- 时间轴感知字幕，点击总结或字幕可跳转视频。
- 多视频个人知识库，支持跨视频精准问答。

后续明确调整为：优先服务 Java 后端开发和 AI Agent 求职，不为了堆技术名词增加无关功能。AI Agent 是加分项，后端工程能力是主叙事。

### 2. 面试钩子补强

用户要求在 MySQL、Redis 基础上补充面试中经常被追问的技术栈，并提前埋钩子。我们形成了“不要硬背八股，而是把八股题埋进业务链路”的策略。

技术钩子矩阵：

- MySQL：MD5 唯一索引、事务、幻读、幂等、联合索引、覆盖索引、EXPLAIN、行锁、乐观锁、深分页。
- Redis：SETNX、Redisson/WatchDog 口径、进度缓存、缓存一致性、Lua 限流、热点 Key、语义缓存。
- Java 并发：线程池参数、拒绝策略、CompletableFuture、CAS、AQS、happens-before。
- JVM：大文件避免 OOM、字幕对象生命周期、GC Roots、jmap、jstack、GC 日志。
- Spring：Filter、Interceptor、AOP、统一异常、事务传播、`@Transactional` 失效、Bean 生命周期、策略模式。
- MyBatis/JDBC：批量入库、N+1 查询、动态 SQL、SQL 注入防护。当前代码是 JDBC 风格 Repository，但面试话术保留 MyBatis 可迁移点。
- MQ：1.0 使用本地 DAG，2.0 可演进 RocketMQ，用于可靠消息、重复消费、顺序消费、死信队列、延迟消息。
- 网络/OS：SSE 长连接、HTTP Range、分片上传、断点续传、ffmpeg 子进程、标准输出阻塞、顺序 IO、零拷贝。
- AI Agent：RAG、Embedding、向量召回、rerank、工具调用、引用约束、低置信度拒答或边界说明。

### 3. 前端从 mock 到真实工作台

前端一开始是本地 mock 数据，后来逐步接通后端接口。期间多轮调整过暗色风格、诊断台位置、云端 LLM 配置入口、视频库入口、结构化总结和 Agent 问答布局。

最终 1.0 前端定位：

- 左侧/中部是主视频工作区：上传、本地视频播放、时间轴字幕。
- 右侧是工作区切换：结构化总结与 Agent 问答水平切换，而不是上下堆叠。
- 右上交互入口：云端 LLM、诊断台、视频库等以按钮形式打开，只在需要时占用右侧空间。
- 时间轴字幕使用滚动窗口，避免长字幕把页面撑得过长。
- Agent 回答后的执行链路做成可展开按钮，默认隐藏，展开后展示输入检查、多轮记忆、字幕检索、向量检索、重排、引用生成、DeepSeek 调用、置信度判断和持久化。

用户曾要求“更专业高级”、后来发现一版太丑，又恢复到更稳的工作台结构。最终以功能清晰、面试演示可控、三列区域紧凑为准，不做营销首页。

### 4. 后端基础链路

后端从 Spring Boot 项目开始，逐步接入：

- 本地视频上传。
- 文件落盘。
- 流式 MD5 计算。
- MD5 去重。
- `processing_job` 任务创建。
- 任务状态流转。
- 本地 DAG 异步解析。
- Redis/本地两套防重锁和进度缓存实现。
- Docker MySQL/Redis 模式。

用户明确说 Maven/Docker 可以下载，因此项目允许拉取依赖和启动 Docker 服务。

后续又确定：以后启动后端默认要启动 Docker MySQL/Redis/Qdrant 模式，使用 `scripts/start-api-docker.ps1`。

### 5. ASR、字幕和准确率优化

用户反馈上传本地视频后最初没有明显反应，且字幕/总结每次都是相同 mock。我们随后接入真实 ASR 链路：

- ffprobe 识别视频时长。
- ffmpeg 抽取 `audio.wav`。
- whisper.cpp 执行 ASR。
- ASR JSON 解析成时间轴字幕。
- 字幕入库。
- 总结基于真实字幕生成。
- 视频播放器支持本地 Range 播放。
- 点击字幕可跳转视频时间点。

用户多次强调字幕准确率：

- 不允许出现乱码。
- 默认全部转简体字。
- `38197201623-1-192.mp4` 出现繁体字幕，需要修复。
- 英文术语识别不准确，需要改善。
- 视频中有画面字幕，应利用 OCR。
- 文本识别错、中文同音词、英文术语错，需要高级优化。

1.0 已做的 ASR 质量措施：

- `SubtitleTextSanitizer` 清理 BOM、替换字符、异常控制字符和疑似编码错误。
- opencc4j 简繁转换，默认输出简体。
- 技术术语规则修复，如 MySQL、Redis、Redisson、SETNX、MyBatis、Spring Boot、Qdrant、Embedding、Rerank、RAG、LLM、AI Agent、Codex、Claude Code。
- whisper prompt 注入 Java 后端和 AI Agent 热词。
- `TranscriptContextRepairService` 结合上下文和术语词库修正低质量片段。
- `TermGlossary` 管理术语词库，为后续人工/模型热词优化留入口。
- `BurnedSubtitleOcrService` 通过 OCR 评估、对齐、融合画面硬字幕，把 OCR 作为强证据修正 ASR。
- 低置信片段可二次识别和修复。
- ASR 诊断台展示模型、音频产物、ASR JSON、字幕条数、ffmpeg/asr 日志尾部。

边界口径：1.0 追求“无乱码、默认简体、技术词尽量修复、可诊断、可回滚”，不承诺达到人工字幕级准确率。2.0 可继续做更强模型、更好的 VAD、专用术语热词和外部校对流程。

### 6. URL 导入和合规边界

用户要求实现 B 站、抖音、小红书 URL 解析。1.0 做了 URL 导入 MVP，后端通过 `yt-dlp` 复用公开链接解析能力，并把下载后的文件进入同一套上传、MD5、DAG、ASR 链路。

用户实际上传 Bilibili 链接时遇到 HTTP 412：

```text
Unable to download webpage: HTTP Error 412: Precondition Failed
Unable to download JSON metadata: HTTP Error 412: Precondition Failed
```

我们明确边界：

- 1.0 不做平台反爬绕过。
- 不做 CAPTCHA 绕过、指纹规避、账号自动化或爬虫规避。
- 页面只给出合规诊断建议：使用 cookies.txt 路径、选择 browser cookies、确认浏览器已登录、更新 yt-dlp、检查 ffmpeg。
- URL 导入作为 MVP 保留，可靠性受平台风控影响。

### 7. DeepSeek LLM 和 Agent 行为修正

用户要求真实云端 LLM 总结/问答，并希望 API Key 不再只靠 PowerShell 环境变量，而是在前端页面添加配置入口。

1.0 已实现：

- 前端云端 LLM 面板。
- 保存登录过的 LLM API。
- 显示可用 provider 列表。
- 启用 provider。
- 测试连接。
- DeepSeek Chat LLM 调用。
- API Key 只做 mask 展示，不在接口响应中明文回显。

用户发现 DeepSeek 有 token 消耗，但 Agent 回答仍“没有命中缓存/没有足够证据就拒答”。随后需求被修正为：

```text
如果问题中有视频提到的内容，就定位字幕并调用大模型解释。
如果问题中没有视频提到的内容，不要拒绝回答，先说明视频中没有提到，再调用大模型回答。
```

1.0 Agent 最终行为：

- 命中当前视频字幕：返回答案、引用片段、时间戳，可点击跳转视频。
- 没命中字幕：说明当前视频没有检索到足够相关证据，再调用 DeepSeek 给通用回答。
- 自我介绍类问题：可以介绍 OmniVid Agent 自身，而不是硬性拒答。
- 返回执行链路 trace：InputGuardrail、MemoryTool、TranscriptRetrieveTool、VectorRetrieveTool、RerankTool、CitationBuilderTool、AnswerPolicyTool、LlmGenerateTool、ConfidenceGuard、PersistTool。
- 问答保存到 MySQL，短期记忆保存到 Redis 或本地实现。

### 8. Embedding、Qdrant 和 rerank

用户要求“接真实 Embedding，DeepSeek 只保留 LLM，Embedding 可接 Qwen/OpenAI/BGE 服务，Qdrant 存真实语义向量，加 rerank，提高 Agent 命中率”。

1.0 决策：

- DeepSeek 只承担 Chat LLM。
- Embedding Provider 与 LLM Provider 解耦。
- 支持 OpenAI-compatible Embedding Provider。
- 如果未配置外部 Embedding，则使用本地 hash embedding fallback，保证链路可演示。
- Qdrant 作为真实外部向量数据库。
- 字幕向量写入 Qdrant collection：`omnivid_transcript_segments`。
- Agent 检索链路包含关键词检索、向量检索和本地 rerank。
- 外部 BGE reranker 放到 2.0。

### 9. 多视频知识库

用户要求强化 Agent 多视频知识库聚合问答，添加知识库管理功能，引用片段可点击跳转视频，并能对比多个视频观点。

1.0 已实现：

- 创建知识库。
- 删除知识库。
- 添加视频到知识库。
- 从知识库移除视频。
- 查询知识库详情。
- 对知识库提问。
- Agent 跨多个视频检索字幕。
- 引用携带 `videoId/startMs/endMs`，前端点击引用可加载对应视频并跳转。
- 对比多个视频观点时，Agent 会在回答中保留来源引用。

面试口径：

- 多对多关系表。
- 唯一约束防重复添加。
- 跨视频 RAG。
- 来源追溯。
- 多租户权限和知识库共享放到 2.0。

### 10. 诊断台和可观测性

用户多次要求诊断台从页面深处移动到更容易访问的位置，并做成交互按钮。最终诊断台用于把“面试能讲的技术证据”可视化。

诊断台覆盖：

- Runtime：MySQL、Redis、Qdrant、LLM、Embedding、SSE 等运行时状态。
- MySQL Explain：展示关键查询命中索引、联合索引和覆盖索引口径。
- Redis Inspect：展示防重锁、进度缓存、限流、语义缓存、短期记忆等 key 设计。
- JVM Thread Pool：展示 DAG 线程池参数、活跃线程、队列、完成数。
- ASR Diagnostic：展示模型、音频、ASR JSON、字幕数量、日志尾部。
- Retrieval Inspector：展示候选召回、向量检索、rerank、strict filter、rejected 和 citations。
- Vector Index：展示 Qdrant 状态和重建入口。
- Recovery：展示失败任务和 retry 边界。

这些功能的核心价值不是为了“炫监控”，而是在面试里证明：项目不是静态 demo，而是能解释运行状态、失败原因和性能路径。

### 11. 前端 UI 关键问题与修复

对话中处理过的前端问题：

- 上传后没有明显字幕/总结反馈：改为接真实后端并展示任务进度。
- Agent 追问结果每次相同：去掉 mock 固定回答，接真实检索和 LLM。
- 诊断台被长字幕挤到很深：改为顶部交互入口。
- 云端 LLM 配置页面太占空间：改为类似诊断台的交互按钮。
- 本地视频库位置调整：移动到右上角交互区。
- 引用片段文本框和视频进度条重叠：下移引用区域，保证视频控件完整。
- 时间轴字幕过长：改为 5-6 条左右高度的滚动窗口。
- 结构化总结和 Agent 问答上下结构拥挤：改为右侧水平切换。
- 结构化总结按钮：核心观点、会议纪要、博客大纲、PPT 大纲按选择生成对应内容，并埋好未来真实 PPT/会议纪要/博客生成入口。

### 12. 1.0 收束和文档化

用户要求制定 1.0 收尾工作，将后续优化放到 2.0。我们最终确定：

- 1.0 不再继续扩展大功能。
- 1.0 只做验证、bug fix、文档、备份、GitHub 发布。
- 2.0 再做浏览器插件、真实 PPTX 导出、RocketMQ、外部 BGE reranker、多用户权限、云部署、CI/CD、稳定平台 URL 导入、知识图谱等。

已经形成的 v1.0 文档包：

- `docs/v1.0/README.md`：1.0 总入口。
- `docs/v1.0/acceptance-checklist.md`：黑盒验收清单。
- `docs/v1.0/technical-architecture.md`：技术架构。
- `docs/v1.0/interview-pack.md`：面试叙事和简历写法。
- `docs/v1.0/full-interview-question-bank.md`：完整面试题库。
- `docs/v1.0/feature-implementation-map.md`：功能实现地图。
- `docs/v1.0/session-backup-2026-06-10.md`：上一轮会话备份。
- `docs/v1.0/roadmap-2.0.md`：2.0 路线。
- `docs/v1.0/conversation-and-work-backup.md`：本次更完整的对话与工作备份。
- `docs/v1.0/code-file-responsibility-map.md`：每个代码文件职责地图。

旧文档已保留备份：

```text
docs/archive/pre-v1.0-interview-docs-20260610/
```

## 当前 1.0 已实现功能

可从最终用户视角演示：

1. 启动 Docker MySQL/Redis/Qdrant 后端模式。
2. 启动 React 前端工作台。
3. 上传本地视频。
4. 后端保存文件并计算 MD5。
5. 重复视频命中 MD5 去重。
6. 创建异步解析任务。
7. 通过 SSE/轮询看到处理进度。
8. ffmpeg 抽取音频。
9. whisper ASR 生成真实字幕。
10. 字幕清洗、简体化、术语修正。
11. 可查看 ASR 诊断。
12. 播放上传视频。
13. 点击字幕跳转视频。
14. 播放时高亮当前字幕。
15. 生成核心观点、会议纪要、博客大纲、PPT 大纲、面试钩子等结构化总结。
16. 在前端配置 DeepSeek Chat Provider。
17. 保存、启用、测试 LLM Provider。
18. 对当前视频提问。
19. Agent 命中字幕时返回可点击时间戳引用。
20. Agent 未命中视频证据时说明边界并调用 LLM 给通用回答。
21. 展开 Agent 执行链路 trace。
22. 配置 Embedding Provider 或使用 fallback。
23. 将字幕向量写入 Qdrant。
24. Agent 使用关键词检索、向量检索和 rerank。
25. 创建多视频知识库。
26. 添加/移除视频。
27. 对知识库进行跨视频问答。
28. 点击知识库引用跳转对应视频。
29. 打开诊断台查看 Runtime、MySQL、Redis、JVM、ASR、RAG、Qdrant、Recovery。
30. 查看失败任务并对 FAILED 任务重试。
31. 粘贴平台 URL 进行 MVP 导入，失败时返回结构化建议。

## 关键技术决策

| 决策 | 结果 | 面试口径 |
| --- | --- | --- |
| 1.0 默认 Docker MySQL/Redis/Qdrant | 真实数据库和缓存，不只 H2 mock | 数据一致性、缓存、向量库可实际演示 |
| 本地 DAG 优先，RocketMQ 放 2.0 | 降低 1.0 复杂度 | 先证明状态机、幂等、失败恢复，再解释 MQ 演进 |
| DeepSeek 只做 Chat LLM | 避免把 LLM 和 Embedding 强耦合 | Provider 解耦、模型路由、降级 |
| Embedding 独立 Provider | 可接 Qwen/OpenAI/BGE 服务 | RAG 召回链路可扩展 |
| Qdrant 作为外部向量库 | 真实 vector store，不只是内存搜索 | 向量存储、collection、upsert、search |
| 本地 hash embedding fallback | 没有外部 embedding key 也能演示 | 降级策略、可用性优先 |
| URL 导入不绕过反爬 | 合规保守 | 外部平台依赖不可控时做诊断和降级 |
| ASR 追求无乱码、简体化和诊断 | 不承诺人工字幕级准确率 | AI 工程要做后处理、质量评估、回滚 |
| Agent 不硬拒答 | 无证据时说明边界再通用回答 | 防幻觉与用户体验平衡 |
| 1.0 冻结大功能 | 转向文档、验证和发布 | 项目管理和版本边界意识 |

## 面试主叙事

### MySQL

业务入口：同一个视频可能被多个用户重复上传，字幕要按时间快速定位，任务状态要可靠推进。

可讲：

- `video_asset.md5` 唯一索引做最终去重兜底。
- `processing_job` 状态机用状态、进度、版本号、错误信息保存异步任务事实。
- `transcript_segment(video_id, start_ms)` 联合索引用于时间轴字幕查询。
- `summary_asset(video_id, type)` 唯一约束保证总结幂等。
- `knowledge_base_video` 唯一约束防重复添加视频。
- EXPLAIN 诊断台把索引命中变成可演示证据。

### Redis

业务入口：上传防抖、任务进度、Agent 限流、语义缓存、短期记忆。

可讲：

- 上传防重复提交用 Redis SETNX 风格锁。
- 任务进度放 Redis，前端查询更快，MySQL 仍保存最终状态。
- Agent 高频问答限流用 Redis 计数窗口。
- Agent 精确问题缓存降低重复 LLM 成本。
- 多轮问答短期记忆放 Redis，长期记录落 MySQL。

### Java 并发和 JVM

业务入口：视频解析是长耗时任务，不能阻塞 HTTP。

可讲：

- 本地 DAG 使用 `ThreadPoolTaskExecutor`。
- 线程池参数、队列容量、拒绝策略和任务状态互相配合。
- ffmpeg、ASR、LLM 都是可能阻塞的节点，需要超时、日志和异常处理。
- 大文件上传使用流式处理，避免堆内存 OOM。
- 诊断台可观察线程池活跃数、队列和完成任务数。

### Spring 和事务

业务入口：上传、创建任务、状态推进、总结写入都要保持一致。

可讲：

- Controller 只负责 HTTP 入口，Service 组织业务，Repository 负责 SQL。
- `GlobalExceptionHandler` 统一异常响应。
- `ApiException` 统一业务错误。
- CORS 由 `WebConfig` 控制。
- 事务边界围绕视频入库、任务创建、状态更新、总结/字幕写入设计。

### AI Agent/RAG

业务入口：用户问视频内容时，回答必须能追溯到字幕和时间戳。

可讲：

- Agent 是工具链，不是直接把问题丢给 LLM。
- 输入检查后先做记忆读取，再做字幕关键词检索和向量检索。
- rerank 后构建引用。
- 有证据时带引用回答；无证据时先说明边界再通用回答。
- trace 面板展示每一步工具调用结果，便于解释和排查。

## 2.0 以后再做

1. 浏览器插件。
2. 真正 PPTX/Docx 导出。
3. RocketMQ 或 Kafka 化 DAG。
4. 外部 BGE reranker。
5. 更强 ASR：VAD、说话人分离、热词注入、人工校对工作流。
6. 平台 URL 导入生产化，但不做违规绕过。
7. 用户登录、多租户、权限和审计。
8. 云部署、CI/CD、监控告警。
9. 对象存储和断点续传。
10. 知识图谱和更复杂 Agent 规划。

## 恢复开发步骤

### 启动后端

```powershell
cd E:\video
.\scripts\start-api-docker.ps1
```

验证：

```powershell
Invoke-RestMethod http://localhost:8080/api/runtime/status | ConvertTo-Json -Depth 6
```

### 启动前端

```powershell
cd E:\video\apps\web
npm run dev -- --host 127.0.0.1 --port 5174
```

访问：

```text
http://127.0.0.1:5174
```

### 验证构建

```powershell
cd E:\video\apps\api
.\mvnw.cmd test

cd E:\video\apps\web
npm run build
```

### Git 发布口径

当前 v1.0 发布目标：

- `main` 保存所有 1.0 代码和文档。
- `v1.0` tag 指向最新 1.0 备份提交。
- 不提交真实 API Key、Cookie、个人账号数据、上传视频文件和数据库卷。

