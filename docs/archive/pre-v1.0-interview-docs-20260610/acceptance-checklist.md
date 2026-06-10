# OmniVid 1.0 黑盒验收清单

验收时间：2026-06-10 19:12-19:45 Asia/Shanghai

## 验收结论

OmniVid 1.0 核心链路已通过本地黑盒验收：后端测试通过、前端构建通过、Docker MySQL/Redis/Qdrant 模式启动成功，视频库、ASR、总结、单视频 Agent、多视频知识库、运行诊断和向量库状态均可通过 API 验证。

## 命令级验收

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 后端单测 | `cd E:\video\apps\api; .\mvnw.cmd test` | `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` |
| 前端构建 | `cd E:\video\apps\web; npm run build` | Vite production build passed |
| Docker 后端 | `cd E:\video; .\scripts\start-api-docker.ps1` | `profile=docker, db=MySQL, redis=True, vector=qdrant` |
| Runtime | `GET /api/runtime/status` | MySQL connected, Redis connected, Qdrant connected, DeepSeek configured |
| 健康检查 | `GET /api/health` | `status=UP` |

## Runtime 状态

```text
profile: docker
database: MySQL
redis: connected
llm chat: enabled + configured
llm model: deepseek-chat
embedding provider: local-hash fallback
vector store: qdrant:omnivid_transcript_segments
vector endpoint: http://localhost:6333
rerank provider: local-rerank
```

## 数据与 AI 验收

| 功能 | 验收方式 | 结果 |
| --- | --- | --- |
| 视频库 | `GET /api/videos` | 11 条视频记录，10 条 READY，1 条历史 FAILED |
| 字幕与总结 | `GET /api/videos/4/transcripts`, `/summaries` | `38197201623-1-192.mp4` 有 394 条字幕、5 份总结 |
| 字幕清洗 | Node 直接读取 API 文本 | 视频 4 未命中乱码/繁体探测，样例为简体中文 |
| ASR 诊断 | `GET /api/videos/4/asr/diagnostics` | 模型、音频、ASR JSON、日志均存在，`garbledRisk=false` |
| MySQL EXPLAIN | `GET /api/mysql/explain` | MD5 去重命中唯一索引，字幕时间轴命中 `idx_transcript_video_start` |
| Redis 检查 | `GET /api/redis/inspect` | 防重锁、进度缓存、限流、语义缓存、短期记忆模式可观测 |
| Qdrant 状态 | `GET /api/vector-index/status` | collection `green`，points count 949 |
| LLM Provider | `GET /api/llm/providers` | DeepSeek active，last test OK |
| Embedding Provider | `GET /api/embedding/providers` | 当前无外部 provider，走本地 hash fallback |

## Agent 验收

### 单视频命中证据

请求：

```http
POST /api/videos/4/agent/ask
```

问题：

```text
视频里 Codex 和 Claude Code 是怎么比较的？
```

结果：

```text
citations: 1
trace: InputGuardrail -> MemoryTool -> TranscriptRetrieveTool -> VectorRetrieveTool -> RerankTool -> CitationBuilderTool -> AnswerPolicyTool -> LlmGenerateTool -> ConfidenceGuard -> PersistTool
```

### 无视频证据的通用回答

请求：

```http
POST /api/videos/4/agent/ask
```

问题：

```text
请介绍一下你自己。
```

结果：

```text
citations: 0
trace: InputGuardrail -> AgentIntroIntent -> TranscriptRetrieveTool -> LlmGenerateTool -> PersistTool
answer: 能说明自己是 OmniVid 视频语义问答 Agent，并解释有证据和无证据时的回答边界。
```

### 多视频知识库对比

流程：

```text
create temp knowledge base -> add video 4 -> add video 10 -> ask comparison -> delete temp knowledge base
```

问题：

```text
请分别比较 38197201623-1-192.mp4 里 Codex/Claude Code 的观点，以及 2026_AI_-Claude_Code_Codex_goal.mp4 里 Codex Automode 的观点，必须给出两个视频的引用。
```

结果：

```text
citations: 2
citedVideoIds: 4, 10
cleanup: temp knowledge base deleted, remainingKnowledgeBases=0
```

## 用户侧演示路径

1. 打开前端工作台。
2. 点击本地上传，选择一个视频文件。
3. 观察 DAG 进度进入上传、音频抽取、ASR、总结生成。
4. 视频 READY 后，检查播放器、时间轴字幕和结构化总结。
5. 点击字幕，播放器跳转到对应时间点。
6. 切换结构化总结类型：核心观点、会议纪要、博客大纲、PPT 大纲。
7. 在 Agent 问答中提问视频内容，检查引用片段。
8. 点击引用片段，视频跳转到引用时间点。
9. 打开云端 LLM，查看 DeepSeek Provider。
10. 打开诊断台，查看 Runtime、MySQL、Redis、JVM、ASR、RAG、Qdrant。
11. 打开视频库，选择历史视频。
12. 打开知识库管理，创建知识库，加入两个视频并做对比问答。

## 1.0 已知边界

- URL 导入保留 MVP，遇到 B 站 412、403、Cookie 缺失时只做诊断提示，不在 1.0 继续处理平台反爬。
- Embedding 默认仍可用本地 hash fallback，外部 Qwen/OpenAI/BGE Embedding provider 属于可配置能力，不作为 1.0 必须在线依赖。
- Rerank 采用本地 rerank，外部 BGE reranker 放入 2.0。
- 当前固定 demo 用户，未实现登录、多租户和权限隔离。
- 已有历史视频中可能存在早期 ASR 误听词，1.0 重点保证无乱码、默认简体、可诊断、可修复，不承诺达到人工字幕级准确率。
