# OmniVid API Blueprint

## 启动约定

后端日常启动统一使用 Docker MySQL/Redis/Qdrant 模式：

```powershell
.\scripts\start-api-docker.ps1
```

黑盒验证目标：

1. `GET /api/runtime/status` -> `profile=docker`，`database.product=MySQL`，`redis.connected=true`，`llm.vectorStoreMode=qdrant`。
2. `GET /api/videos` -> 返回 MySQL 中持久化的视频库，而不是 H2 临时数据。

## 字幕质量链路

ASR 输出进入 MySQL 前必须经过 `SubtitleTextSanitizer` 清洗；读取字幕和总结时也会做编码自愈，避免页面展示乱码。

关键接口：

1. `GET /api/videos/{videoId}/asr/diagnostics` -> 查看 ASR 模型、音频文件、字幕数量和 `quality.garbledRisk`。
2. `POST /api/videos/{videoId}/asr/repair-encoding` -> 修复已有字幕/总结的编码问题，并在字幕变更后重建向量索引。
3. `POST /api/videos/{videoId}/asr/reprocess` -> 对已上传视频重新执行 ffmpeg 抽音频、Whisper ASR、MySQL 字幕替换、总结重建和向量索引重建。

黑盒验证目标：

1. 诊断接口里 `quality.garbledRisk=false`，`replacementCount=0`，`controlCount=0`。
2. 重跑 ASR 后 `GET /api/videos/{videoId}/progress` 最终返回 `DONE` 和 `SUMMARY_GENERATED_AND_LOCAL_DAG_DONE`。
3. `GET /api/videos/{videoId}/transcripts` 返回可读字幕，不能包含替换符或控制字符。

## 当前 ASR 准确度策略

默认模型配置指向 `E:/video/tools/asr/ggml-base.bin`。如果本机存在 `ggml-medium.bin`、`ggml-small.bin`、`ggml-base.bin`，后端会自动优先使用更高精度模型；否则回退到 `ggml-tiny.bin`，保证启动可用。

ffmpeg 抽音频会生成 16kHz 单声道 wav，并加入轻量人声增强滤镜，减少噪声对字幕识别的影响。

## Embedding / Qdrant / Rerank 链路

DeepSeek 只保留为 `CloudLlmClient` 的对话生成模型，不再作为 Embedding provider。字幕语义召回由独立的 OpenAI-compatible Embedding provider 负责，支持 `qwen`、`openai`、`bge` 和 `local` 模式。

Qwen Embedding 示例：

```powershell
$env:OMNIVID_EMBEDDING_ENABLED="true"
$env:OMNIVID_EMBEDDING_MODE="qwen"
$env:OMNIVID_EMBEDDING_API_KEY="your-qwen-key"
$env:OMNIVID_EMBEDDING_MODEL="text-embedding-v4"
.\scripts\start-api-docker.ps1
```

OpenAI Embedding 示例：

```powershell
$env:OMNIVID_EMBEDDING_ENABLED="true"
$env:OMNIVID_EMBEDDING_MODE="openai"
$env:OMNIVID_EMBEDDING_API_KEY="your-openai-key"
$env:OMNIVID_EMBEDDING_MODEL="text-embedding-3-small"
.\scripts\start-api-docker.ps1
```

本地 BGE OpenAI-compatible 服务示例：

```powershell
$env:OMNIVID_EMBEDDING_ENABLED="true"
$env:OMNIVID_EMBEDDING_MODE="bge"
$env:OMNIVID_EMBEDDING_BASE_URL="http://localhost:8000/v1"
$env:OMNIVID_EMBEDDING_MODEL="BAAI/bge-m3"
.\scripts\start-api-docker.ps1
```

Rerank 默认为本地融合分数；如果有 BGE reranker 服务，可以切到远程重排：

```powershell
$env:OMNIVID_RERANK_ENABLED="true"
$env:OMNIVID_RERANK_MODE="bge"
$env:OMNIVID_RERANK_BASE_URL="http://localhost:8001"
$env:OMNIVID_RERANK_ENDPOINT="/rerank"
$env:OMNIVID_RERANK_MODEL="bge-reranker-v2-m3"
.\scripts\start-api-docker.ps1
```

关键验证：

1. `GET /api/runtime/status` -> `llm.embeddingProvider` 不再是 `local-hash` 时，说明真实 Embedding 已接通。
2. `POST /api/vector-index/rebuild` -> Qdrant 会清理旧点，并用当前 Embedding 维度写入当前 MySQL 字幕的真实语义向量。
3. 如果 Qdrant collection 旧维度和新 Embedding 维度不一致，检索链路也会自动删除旧 collection 并按新维度重建。
4. Agent 回答后的执行链路会展示 `VectorRetrieveTool`、`RerankTool`、`CitationBuilderTool`、`LlmGenerateTool` 和 `ConfidenceGuard`。

前端右侧 `Embedding` 入口可以保存并激活 Qwen/OpenAI/BGE provider。对应接口：

1. `GET /api/embedding/providers` -> 查看已保存 Embedding provider。
2. `POST /api/embedding/providers` -> 保存并激活 provider。
3. `POST /api/embedding/providers/{id}/activate` -> 切换 active provider。
4. `POST /api/embedding/test` -> 用当前 active provider 请求一次 `/embeddings`，成功后运行态会显示真实维度。

## 多视频知识库聚合问答

知识库由 `knowledge_base` 和 `knowledge_base_video` 两张表管理。前端 Agent 切到“知识库”模式后，可以创建知识库、加入/移出视频、删除知识库，并用当前选中的知识库做聚合问答。

关键接口：

1. `GET /api/knowledge-bases` -> 查看知识库列表与成员视频数量。
2. `POST /api/knowledge-bases` -> 创建知识库。
3. `GET /api/knowledge-bases/{id}` -> 查看知识库详情和成员视频。
4. `POST /api/knowledge-bases/{id}/videos` -> 把视频加入知识库，重复调用保持幂等。
5. `DELETE /api/knowledge-bases/{id}/videos/{videoId}` -> 移出视频。
6. `POST /api/knowledge-bases/{id}/agent/ask` -> 只在该知识库的视频字幕中检索、重排、引用和回答。

黑盒验证目标：

1. 创建临时知识库并加入两个 `READY` 视频后，详情接口 `videos.length=2`。
2. 对该知识库提问“对比这个知识库中多个视频的核心观点差异”，Agent 返回 `answerMode=KNOWLEDGE_BASE_CITED` 且 `citations.length>0`。
3. 前端 citation chip 点击后会加载 citation 所属视频，并跳转到 `startMs` 对应位置。
4. 删除临时知识库后，`GET /api/knowledge-bases` 不再包含该记录。
## 烧录字幕 OCR 质量评估与融合

视频画面中可见但没有内嵌字幕轨的字幕，不能通过 ffprobe 直接提取。当前后端增加了轻量 OCR 辅助链路：Whisper 仍负责音频时间轴，OCR 只在抽样帧中读取画面底部字幕，并作为可验证参考。

关键接口：
1. `GET /api/videos/{videoId}/asr/evaluate-ocr` -> 抽样统计 ASR 与画面字幕 OCR 的字符错误率（CER）和相似度，不写数据库。
2. `POST /api/videos/{videoId}/asr/fuse-ocr` -> 对高置信、完整字幕行执行保守融合，写回 MySQL 后重建总结和 Qdrant 向量索引。

黑盒验证目标：
1. `ocrAvailable=true`，`ocrHitCount` 接近 `sampledCount`，表示烧录字幕可被 OCR 捕获。
2. `averageFusedSimilarity > averageSimilarity`，表示融合策略确实提高了样本准确率。
3. 写回后 `GET /api/videos/{videoId}/asr/diagnostics` 仍保持 `quality.garbledRisk=false`、`traditionalCount=0`。
## Final ASR Precision Pass

Scope:
1. `POST /api/videos/{videoId}/asr/align-ocr` runs ASR + OCR dual-channel alignment over a larger subtitle sample and writes back only high-confidence visual subtitle evidence.
2. `POST /api/videos/{videoId}/asr/refine-low-confidence` targets likely low-confidence ASR segments by using clean, high-confidence burned-in subtitles as the second recognizer.
3. `GET/POST/PUT/DELETE /api/asr/glossary` manages user-defined ASR term glossary entries, such as `my sql -> MySQL` or `cloud code -> Claude Code`.

Data hooks:
1. `term_glossary_entry` is initialized in both `schema.sql` and `schema-mysql.sql`.
2. Glossary terms are applied during new ASR transcript insertion, existing transcript repair, OCR write-back, summary rebuild and vector reindex flows.
3. OCR write-back avoids LLM rewriting; only ASR text, OCR evidence and explicit glossary rules may alter subtitles.

Black-box validation:
1. `mvnw test` must pass.
2. `npm run build` must pass.
3. Docker mode runtime must report `profile=docker`, `database.product=MySQL`, `redis.connected=true`, and `llm.vectorStoreMode=qdrant`.
4. A temporary glossary entry can be created, listed and deleted through `/api/asr/glossary` without leaving residue.
