# OmniVid 已实现功能技术文档

## 1. 文档定位

本文档总结 OmniVid 当前已经落地的完整功能范围，面向两个目标：

1. 从最终用户视角说明现在能在浏览器里走通哪些业务流。
2. 从 Java 后端求职视角说明每个功能背后的技术栈、验证方式和面试钩子。

当前项目主线是“长视频上传、平台 URL 导入、异步解析、字幕时间轴、结构化总结、可追溯 Agent 问答”。系统已经从纯前端 mock 演进为前后端真实联调，并接入本地文件存储、MD5 去重、ffmpeg、whisper.cpp、MySQL、Redis、SSE、视频播放和可配置云端 LLM 生成层。

关联文档：

| 文档 | 作用 |
| --- | --- |
| `CODEX.md` | 项目蓝图和 Vibe Coding 执行规则 |
| `docs/01-career-architecture.md` | 求职型项目架构定位 |
| `docs/02-mysql-redis-hooks.md` | MySQL/Redis 初版技术钩子 |
| `docs/03-backend-agent-playbook.md` | 后端与 Agent 面试打法 |
| `docs/04-interview-hook-map.md` | 全技术栈八股映射 |
| `docs/05-mysql-interview-hooks.md` | MySQL 专项作战手册 |

## 2. 当前实现状态总览

| 模块 | 当前状态 | 用户侧表现 | 技术侧落点 |
| --- | --- | --- | --- |
| 前端工作台 | 已实现 | 暗色工作台，可上传、查看视频库、字幕、总结、任务进度、Agent 问答 | React + Vite + TypeScript + lucide-react |
| 后端 API | 已实现 | 前端所有核心数据来自后端接口 | Spring Boot 3 + Java |
| 本地视频上传 | 已实现 | 选择本地视频后上传到后端 | Multipart 上传、2GB 配置、流式 MD5 |
| 平台 URL 导入 | MVP 已实现 | 粘贴 B站/抖音/小红书公开链接后导入 | `yt-dlp` 下载 + 复用上传解析链路 |
| 本地文件存储 | 已实现 | 上传文件保存到 `apps/api/storage/videos/{md5}` | 本地磁盘存储、按 MD5 分目录 |
| MD5 去重 | 已实现 | 重复上传同一视频会复用已有资产 | Redis/Local lock + MySQL 唯一索引 |
| ffprobe 时长识别 | 已实现 | 页面展示真实视频时长 | ffprobe 子进程读取 duration |
| ffmpeg 抽音频 | 已实现 | 上传后生成 `audio.wav` | Java 调系统进程、超时、日志文件 |
| whisper.cpp ASR | 已实现 | 字幕区展示真实 ASR 片段 | whisper-cli + tiny 模型 + JSON 解析 |
| 结构化总结 | 已实现 | 总结区展示 ASR 驱动的核心观点、会议纪要、博客大纲、PPT 大纲和面试钩子 | 云端 LLM 可配置生成，失败降级本地规则总结器 |
| 异步 DAG | 已实现 | 上传后页面显示处理阶段和进度 | ThreadPoolTaskExecutor + 状态机 |
| SSE 进度推送 | 已实现 | 前端自动刷新任务进度，完成后拉取详情 | Server-Sent Events |
| MySQL 模式 | 已实现 | Docker profile 下数据落 MySQL | MySQL 8.4 + schema-mysql.sql |
| Redis 模式 | 已实现 | Docker profile 下锁、进度、限流使用 Redis | Redis 7.4 + StringRedisTemplate |
| 视频播放 | 已实现 | 页面可播放上传的视频 | `/api/videos/{id}/media` + Range |
| 字幕点击跳转 | 已实现 | 点击字幕，播放器跳到对应时间 | `video.currentTime` + `start_ms` |
| 播放同步字幕 | 已实现 | 播放/拖动时高亮对应字幕 | `timeupdate` / `seeked` |
| 当前视频 Agent | 已实现 | 可针对当前视频提问，并返回引用时间戳 | 字幕关键词检索 + 引用留痕 + 可配置 LLM 证据约束回答 |
| 默认知识库 Agent | 已实现 | 可跨已上传视频检索最相关字幕 | 多视频字幕聚合检索 + 可配置 LLM 证据约束回答 |
| 聊天记录入库 | 已实现 | 问答记录落 MySQL | `chat_message` |
| 面试钩子面板 | 已实现 | 页面展示 MD5、任务、索引、总结资产钩子 | 前端动态读取后端数据 |

未真正接通的能力：

| 能力 | 当前状态 | 说明 |
| --- | --- | --- |
| YouTube/私密/登录态 URL 解析 | 未实现 | 当前 URL 导入只做 B站/抖音/小红书公开链接 MVP，需要本机安装或配置 `yt-dlp` |
| 浏览器插件 | 未实现 | 当前是网站工作台，不是浏览器插件 |
| 真实云端 LLM | 已接入 DeepSeek Chat MVP | 页面保存 Key 后总结和 Agent 回答会优先调用 OpenAI-compatible `/chat/completions`，失败自动降级 |
| 向量检索/RAG | Qdrant MVP 已实现 | 已接入外部向量库 Qdrant、Embedding Provider、字幕向量 upsert/search 和 rerank trace；DeepSeek `/embeddings` 不可用时自动降级本地 hash 向量 |
| RocketMQ | 未实现 | 当前用本地轻量 DAG，后续可演进 |
| 登录、多用户、组织权限 | 未实现 | 当前固定 `DEMO_USER_ID = 1` |

## 3. 系统运行形态

### 3.1 本地开发模式

默认模式可以不依赖 Docker：

- 数据库：H2 内存库，MySQL 兼容模式。
- 去重锁：本地内存锁。
- 进度缓存：本地内存缓存。
- Agent 限流：本地内存限流。
- 适合：快速启动、演示上传、ASR、字幕、总结、播放。

启动后端：

```powershell
cd E:\video\apps\api
$env:OMNIVID_FFMPEG_PATH = "E:\video\tools\ffmpeg\runtime\ffmpeg-n7.1-latest-win64-gpl-shared-7.1\bin\ffmpeg.exe"
.\mvnw.cmd spring-boot:run
```

启动前端：

```powershell
cd E:\video\apps\web
npm run dev -- --host 127.0.0.1
```

访问地址：

```text
http://127.0.0.1:5173
```

### 3.2 Docker MySQL + Redis 模式

Docker 模式用于把项目讲成真实 Java 后端工程：

- MySQL：保存视频资产、任务、字幕、总结、聊天记录。
- Redis：负责 MD5 防重锁、任务进度缓存、Agent 限流。
- 后端 profile：`docker`。

启动基础设施：

```powershell
cd E:\video\infra
docker compose up -d
```

启动后端：

```powershell
cd E:\video\apps\api
$env:OMNIVID_FFMPEG_PATH = "E:\video\tools\ffmpeg\runtime\ffmpeg-n7.1-latest-win64-gpl-shared-7.1\bin\ffmpeg.exe"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=docker"
```

MySQL 连接信息：

```text
host: localhost
port: 3307
database: omnivid
username: omnivid
password: omnivid_pass
```

Redis 连接信息：

```text
host: localhost
port: 6379
```

### 3.3 云端 LLM 配置

云端 LLM 生成层默认关闭，不影响本地上传、ASR、字幕、总结和 Agent 问答演示。现在支持两种配置方式。

页面配置：

1. 打开前端工作台。
2. 在左侧“云端 LLM”面板填入 Provider 名称、Base URL、模型名和 API Key。
3. 点击“保存并启用”，配置会进入下方可用 Provider 列表。
4. 后续可以在列表中点击“启用”切换当前模型。
5. 点击“测试连接”验证当前 active Provider。

页面配置会保存到 `llm_provider_config` 表。前端只显示 API Key mask，不回显完整 Key；后端重启后会自动加载 active Provider。

环境变量配置：

配置后端环境变量后，系统会在启动时加载默认 LLM 设置，并优先调用 OpenAI-compatible `/chat/completions`：

```powershell
$env:OMNIVID_LLM_ENABLED = "true"
$env:OMNIVID_LLM_API_KEY = "你的 API Key"
$env:OMNIVID_LLM_BASE_URL = "https://api.deepseek.com/v1"
$env:OMNIVID_LLM_MODEL = "deepseek-chat"
$env:OMNIVID_LLM_TIMEOUT = "60s"
```

当前默认值：

```text
OMNIVID_LLM_ENABLED=false
OMNIVID_LLM_BASE_URL=https://api.deepseek.com/v1
OMNIVID_LLM_MODEL=deepseek-chat
```

降级策略：

- 没有 Key、未启用、请求超时或接口返回异常时，自动使用本地总结器和本地 Agent 模板回答。
- 总结生成要求模型返回严格 JSON，解析失败会降级。
- LLM 总结成功时会按 `video_id + type` 覆盖已有本地总结资产；失败时不覆盖旧结果。
- Agent 有字幕证据时会基于 citation 调用当前 active Provider；无证据时先说明“当前视频/知识库未检索到相关字幕”，再调用通用 LLM 回答，不能声称来自视频。

## 4. 用户完整业务流

### 4.1 上传本地视频

用户在左侧“视频上传”区域选择本地视频文件。

前端调用：

```http
POST /api/videos/upload/file
Content-Type: multipart/form-data
```

后端处理：

1. 接收 `MultipartFile`。
2. 流式保存到本地磁盘。
3. 流式计算 MD5。
4. 按 MD5 获取去重锁。
5. 查询 MySQL 是否已有同 MD5 视频。
6. 新视频则创建 `video_asset` 和 `processing_job`。
7. 提交本地异步 DAG 任务。
8. 返回 `deduplicated`、`video`、`job`。

浏览器验证：

1. 上传后，左侧显示 `deduplicated=false` 或 `deduplicated=true`。
2. 上传状态卡显示当前阶段、进度、字幕数量、总结数量。
3. 视频会出现在“视频知识库”列表中。

终端验证：

```powershell
curl.exe -F "file=@E:\video\tools\asr\speech-sample.mp4" http://localhost:8080/api/videos/upload/file
```

期望看到返回 JSON 包含：

```json
{
  "deduplicated": false,
  "video": {
    "id": 1,
    "md5": "...",
    "status": "PROCESSING"
  },
  "job": {
    "currentStep": "PENDING",
    "status": "RUNNING"
  }
}
```

### 4.1.1 平台 URL 导入

用户在左侧“视频上传”区域粘贴 B站、抖音或小红书公开链接。

前端调用：

```http
POST /api/videos/import/url
Content-Type: application/json

{"url":"https://www.bilibili.com/video/..."}
```

后端处理：

1. 从输入文本中提取第一个 HTTP/HTTPS URL。
2. 校验 host 是否属于 B站、抖音或小红书支持域名。
3. 调用 `yt-dlp` 下载公开视频到 `storage/tmp/url-import` 临时目录，并携带桌面浏览器 UA/Referer 处理 B站 412。
4. 将下载文件交给 `LocalVideoStorageService.storeLocalFile`。
5. 通过 `--ffmpeg-location` 让 `yt-dlp` 合并 B站音视频分片。
6. 复用 `VideoService.completeStoredUpload`，继续走 MD5 去重、建 job、ffmpeg、ASR、总结和 SSE 进度。

配置：

```yaml
omnivid:
  url-import:
    ytdlp-path: ${OMNIVID_YTDLP_PATH:E:/video/tools/url/yt-dlp.exe}
    timeout: ${OMNIVID_YTDLP_TIMEOUT:300s}
```

当前边界：

- 支持公开可访问链接，短链会交给 `yt-dlp` 处理跳转。
- 不处理登录态、cookie、私密视频、付费内容和平台反爬绕过。
- 下载阶段当前仍在 HTTP 请求内同步执行；下载完成后复用已有异步解析任务。

### 4.2 重复上传去重

用户重复上传同一个视频。

后端处理：

1. 用相同 MD5 获取锁。
2. 查询 `video_asset.md5`。
3. 命中已有视频时不重复创建资产。
4. 返回 `deduplicated=true`。
5. 前端直接复用已有字幕、总结和视频详情。

数据库保障：

- `video_asset.md5` 上有唯一索引 `uk_video_md5`。
- Redis/Local lock 只是并发窗口内的性能层。
- MySQL 唯一索引是最终幂等兜底。

黑盒验证：

```powershell
docker exec omnivid-mysql mysql -uomnivid -pomnivid_pass -D omnivid -e "SELECT id, md5, original_name, status FROM video_asset;"
```

同一个 MD5 应只出现一条视频资产记录。

### 4.3 异步解析进度

用户上传视频后，页面不等待整个 ASR 过程同步完成，而是展示异步进度。

后端处理阶段：

```text
PENDING
AUDIO_EXTRACTING
AUDIO_EXTRACTED
ASR_TRANSCRIBING
ASR_TRANSCRIBED
SUMMARY_GENERATED_AND_LOCAL_DAG_DONE
```

前端监听：

```http
GET /api/videos/{videoId}/progress/stream
Accept: text/event-stream
```

SSE 示例：

```text
event: progress
data: {"videoId":1,"jobId":1,"currentStep":"ASR_TRANSCRIBING","status":"RUNNING","progress":75}
```

浏览器验证：

1. 上传后进度条从上传、抽音频、ASR、总结逐步推进。
2. 完成后页面自动拉取详情。
3. 字幕区和总结区出现真实内容。

### 4.4 ffmpeg 抽音频

后端从上传视频中抽取 WAV 音频，供 whisper.cpp 使用。

输入：

```text
apps/api/storage/videos/{md5}/{originalName}
```

输出：

```text
apps/api/storage/videos/{md5}/audio.wav
apps/api/storage/videos/{md5}/ffmpeg.log
```

技术点：

- Java 调用系统子进程。
- 设置执行超时。
- 捕获标准输出和错误日志。
- 失败时写入任务失败状态。

黑盒验证：

```powershell
Get-ChildItem E:\video\apps\api\storage\videos -Recurse -Filter audio.wav
Get-ChildItem E:\video\apps\api\storage\videos -Recurse -Filter ffmpeg.log
```

### 4.5 whisper.cpp ASR 字幕生成

后端调用本地 whisper.cpp 对 `audio.wav` 转写。

配置：

```yaml
omnivid:
  asr:
    path: E:/video/tools/asr/runtime/Release/whisper-cli.exe
    model: E:/video/tools/asr/ggml-tiny.bin
    timeout: 180s
```

输出：

```text
apps/api/storage/videos/{md5}/asr.json
apps/api/storage/videos/{md5}/asr.log
```

落库：

```text
transcript_segment
```

关键字段：

| 字段 | 作用 |
| --- | --- |
| `video_id` | 归属视频 |
| `segment_index` | 字幕片段顺序 |
| `start_ms` | 开始时间 |
| `end_ms` | 结束时间 |
| `speaker` | 当前固定为 ASR |
| `content` | 字幕文本 |
| `token_count` | 粗略 token 估算 |

空字幕兜底：

如果 ASR 没识别到有效语音，系统会生成系统提示字幕，明确告诉用户“本地 ASR 已执行，但没有识别到有效语音字幕”。

### 4.6 结构化总结

当前总结支持两种生成路径：

1. 云端 LLM 已启用且返回合法 JSON：使用模型生成核心观点、会议纪要、博客大纲、PPT 大纲和面试钩子。
2. 未配置 Key、模型失败或 JSON 解析失败：使用 ASR 字幕驱动的本地结构化总结兜底。

生成资产：

| type | title | 用途 |
| --- | --- | --- |
| `CORE_POINTS` | ASR/LLM 核心观点 | 展示视频内容概览 |
| `MEETING_MINUTES` | 会议纪要 | 生成办公会议纪要 |
| `BLOG_OUTLINE` | 博客大纲 | 生成结构化长文框架 |
| `PPT_OUTLINE` | PPT 大纲 | 生成宣讲提纲 |
| `INTERVIEW_HOOKS` | 面试钩子 | 从字幕关键词或 LLM 总结提取可讲技术点 |

落库：

```text
summary_asset
```

幂等约束：

```text
uk_summary_video_type(video_id, type)
```

浏览器验证：

1. 上传完成后右侧“结构化总结”显示 `5 份已加载`。
2. 总结区可以切换核心观点、会议纪要、博客大纲、PPT 大纲、面试钩子。
3. 未启用 LLM 时，总结内容会引用视频名称、字幕概览和可追溯片段。
4. 启用 LLM 时，`summary_asset.model_name` 会记录真实模型名，`prompt_version` 为 `llm-v1`。
5. 旧视频已有本地总结时，LLM 成功会覆盖同 type 资产；LLM 失败不会破坏旧总结。
6. 面试钩子会根据字幕中是否出现 MySQL、Redis、Agent、DAG 等词动态生成或由 LLM 生成。

### 4.7 视频播放和 Range 支持

前端视频播放器读取后端媒体接口：

```http
GET /api/videos/{videoId}/media
```

后端能力：

- 返回真实本地视频文件。
- 支持 `Accept-Ranges: bytes`。
- 浏览器拖动播放进度时可发起 Range 请求。

Range 验证：

```powershell
curl.exe -I http://localhost:8080/api/videos/1/media
curl.exe -H "Range: bytes=0-1023" http://localhost:8080/api/videos/1/media -o NUL -D -
```

期望：

```text
HTTP/1.1 206
Accept-Ranges: bytes
Content-Range: bytes 0-1023/...
```

### 4.8 字幕点击跳转与播放同步

用户点击任意字幕行，播放器跳到对应时间。

前端实现：

1. 字幕行来自 `/api/videos/{id}/transcripts`。
2. 点击时设置 `video.currentTime = startMs / 1000`。
3. 播放器触发 `timeupdate` / `seeked`。
4. 前端根据当前播放毫秒数高亮对应字幕。

后端查询：

```sql
SELECT *
FROM transcript_segment
WHERE video_id = ?
ORDER BY start_ms ASC;
```

索引：

```text
idx_transcript_video_start(video_id, start_ms)
```

EXPLAIN 验证：

```powershell
docker exec omnivid-mysql mysql -uomnivid -pomnivid_pass -D omnivid -e "EXPLAIN SELECT * FROM transcript_segment WHERE video_id = 1 ORDER BY start_ms ASC LIMIT 5;"
```

期望 `key` 命中：

```text
idx_transcript_video_start
```

### 4.9 当前视频 Agent 问答

用户在 Agent 面板选择“当前视频”，输入问题。

前端调用：

```http
POST /api/videos/{videoId}/agent/ask
Content-Type: application/json

{"question":"这段视频里怎么讲 MySQL？"}
```

后端处理：

1. 限流检查。
2. 读取当前视频最近 6 条 `chat_message`，取最近一条用户问题作为轻量上下文窗口。
3. 读取当前视频字幕。
4. 根据问题关键词和同义词做轻量检索。
5. 按分数排序选出 Top3 字幕片段作为多证据引用。
6. 如果存在字幕证据，优先尝试云端 LLM 基于 citations 生成自然语言回答。
7. 如果 LLM 未启用、超时或失败，降级为本地模板回答。
8. 返回回答、Top1 兼容引用、视频 ID、起止时间、`citations[]`、`confidenceScore/confidenceLevel`、`contextUsed` 和 `trace[]`。
9. 将用户问题和助手回答写入 `chat_message`。
10. 前端切换视频时通过 `/agent/messages` 恢复最近问答历史。

返回示例：

```json
{
  "answer": "根据当前视频的 ASR 字幕，最相关证据是：...",
  "citation": "OmniVid Demo 00:12-00:36",
  "videoId": 1,
  "startMs": 12000,
  "endMs": 36000,
  "citations": [
    {
      "citation": "OmniVid Demo 00:12-00:36",
      "videoId": 1,
      "segmentId": 10,
      "startMs": 12000,
      "endMs": 36000,
      "score": 4,
      "snippet": "字幕证据摘要..."
    }
  ],
  "confidenceScore": 4,
  "confidenceLevel": "HIGH",
  "contextUsed": false,
  "cacheHit": false,
  "trace": [
    {
      "name": "InputGuardrail",
      "status": "done",
      "detail": "question passed injection checks"
    },
    {
      "name": "MemoryTool",
      "status": "miss",
      "detail": "no previous question"
    },
    {
      "name": "TranscriptRetrieveTool",
      "status": "done",
      "detail": "segments=12, evidence=3"
    },
    {
      "name": "CitationBuilderTool",
      "status": "done",
      "detail": "citations=3"
    },
    {
      "name": "LlmGenerateTool",
      "status": "done",
      "detail": "model=deepseek-chat"
    },
    {
      "name": "ConfidenceGuard",
      "status": "done",
      "detail": "level=HIGH, score=4"
    },
    {
      "name": "PersistTool",
      "status": "done",
      "detail": "chat_message saved, short-term memory updated"
    }
  ]
}
```

当前边界：

- 已实现 Top3 多证据可追溯引用。
- 已实现轻量置信度字段，前端展示 `HIGH/MEDIUM/LOW/NONE` 和证据分数。
- 已实现 Agent 多证据按钮点击跳转，当前视频引用会定位播放器，跨视频引用会先切换工作区。
- 已实现无证据通用 LLM 兜底：先说明视频/知识库未命中，再给出通用回答。
- 已实现聊天记录入库。
- 已实现最近问答历史恢复，并将历史 Top1 citation 还原为可点击跳转引用。
- 已实现当前视频 Agent 历史清空。
- 已实现当前视频最近几轮轻量上下文窗口，前端会显示“多轮上下文”标记。
- 已实现 `GET /api/videos/{videoId}/agent/context` 上下文窗口可观测接口，前端展示窗口大小和上一轮问题。
- 已实现 Agent 短期记忆，docker profile 下使用 Redis 保存最近用户问题，清空历史时同步清理。
- 已实现 Agent 轻量执行轨迹 `trace[]`，前端展示输入安全、记忆、字幕召回、引用构造、LLM 生成、置信度守卫和落库步骤。
- 已实现 Agent Prompt Injection Guard，风险问题在召回前拒答，当前视频模式写入 MySQL 审计但不写入 Redis 短期记忆。
- 已实现可配置云端 LLM 证据约束回答，失败自动降级本地模板。
- 已实现 Qdrant 外部向量库检索、Embedding 向量召回和 rerank 执行轨迹；DeepSeek 当前不提供 `/embeddings` 时会显示 `local-hash-fallback`，但向量仍会写入 Qdrant。

### 4.10 默认知识库跨视频问答

用户在 Agent 面板选择“知识库”，输入问题。

前端调用：

```http
POST /api/knowledge-bases/default/agent/ask
Content-Type: application/json

{"question":"哪些视频提到了 Redis？"}
```

后端处理：

1. 读取当前 demo 用户所有视频。
2. 聚合这些视频的字幕。
3. 按关键词打分选出 Top3 相关片段。
4. 有证据时尝试云端 LLM 基于跨视频 citations 生成回答，失败降级本地模板。
5. 返回引用视频名称、时间戳和 `citations[]`。
6. 如果引用的视频不是当前选中视频，前端自动切换到被引用视频详情。

求职叙事：

```text
当前阶段已把多视频字幕聚合检索升级为 Qdrant 外部向量库检索，并保留证据约束 LLM 生成，保证引用可追溯；下一阶段可以升级为更强 Embedding 模型和 rerank 模型，业务接口不需要大改。
```

### 4.11 视频知识库/历史切换

左侧“视频知识库”展示所有已上传视频。

前端调用：

```http
GET /api/videos
GET /api/videos/{videoId}
```

用户行为：

1. 点击任一视频。
2. 页面切换播放器、字幕、总结、任务状态。
3. Agent 当前视频模式也随之切换上下文。

数据库查询：

```sql
SELECT *
FROM video_asset
WHERE user_id = ?
ORDER BY created_at DESC;
```

索引：

```text
idx_video_user_created(user_id, created_at)
```

## 5. 后端接口清单

| 方法 | 路径 | 状态 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/api/health` | 已实现 | 健康检查 |
| `POST` | `/api/videos/upload/file` | 已实现 | 真实本地视频上传 |
| `POST` | `/api/videos/import/url` | MVP 已实现 | B站/抖音/小红书公开 URL 导入 |
| `POST` | `/api/videos/upload/complete` | 已实现 | 兼容登记接口，不执行真实文件上传 |
| `GET` | `/api/videos` | 已实现 | 视频列表 |
| `GET` | `/api/videos/{videoId}` | 已实现 | 视频详情，包含 job、字幕、总结 |
| `GET` | `/api/videos/{videoId}/media` | 已实现 | 视频文件播放，支持 Range |
| `GET` | `/api/videos/{videoId}/transcripts` | 已实现 | 当前视频字幕 |
| `GET` | `/api/videos/{videoId}/summaries` | 已实现 | 当前视频总结 |
| `GET` | `/api/videos/{videoId}/progress` | 已实现 | 当前视频任务进度快照 |
| `GET` | `/api/videos/{videoId}/progress/stream` | 已实现 | SSE 任务进度流 |
| `GET` | `/api/jobs/{jobId}` | 已实现 | 任务详情 |
| `POST` | `/api/videos/{videoId}/agent/ask` | 已实现 | 当前视频 Agent 问答 |
| `GET` | `/api/videos/{videoId}/agent/messages` | 已实现 | 当前视频最近问答历史 |
| `GET` | `/api/videos/{videoId}/agent/context` | 已实现 | 当前视频 Agent 上下文窗口状态 |
| `DELETE` | `/api/videos/{videoId}/agent/messages` | 已实现 | 清空当前视频问答历史 |
| `POST` | `/api/knowledge-bases/default/agent/ask` | 已实现 | 默认知识库跨视频问答 |

## 6. 数据库设计

### 6.1 表结构总览

| 表 | 用途 | 当前是否使用 |
| --- | --- | --- |
| `users` | 用户表 | 预留，当前固定 demo 用户 |
| `video_asset` | 视频资产 | 已使用 |
| `processing_job` | 解析任务状态机 | 已使用 |
| `transcript_segment` | 时间轴字幕 | 已使用 |
| `summary_asset` | 总结资产 | 已使用 |
| `chat_message` | Agent 聊天记录 | 已使用 |
| `llm_provider_config` | 云端 LLM Provider 配置列表 | 已使用 |

### 6.2 核心索引

| 索引 | 表 | 作用 | 面试关键词 |
| --- | --- | --- | --- |
| `uk_video_md5` | `video_asset` | 视频内容去重 | 唯一索引、幂等、并发冲突 |
| `idx_video_user_created` | `video_asset` | 视频列表排序 | 联合索引、分页、最左前缀 |
| `idx_job_video` | `processing_job` | 查询视频最新任务 | 普通索引、任务状态机 |
| `idx_job_status_updated` | `processing_job` | 后续失败任务扫描 | 补偿任务、状态扫描 |
| `uk_transcript_video_index` | `transcript_segment` | 字幕批量入库幂等 | 唯一约束、重复写防护 |
| `idx_transcript_video_start` | `transcript_segment` | 时间轴字幕查询 | B+Tree、ORDER BY、EXPLAIN |
| `idx_transcript_video_time_cover` | `transcript_segment` | 时间点定位预留 | 覆盖索引、回表 |
| `uk_summary_video_type` | `summary_asset` | 总结资产幂等 | 唯一索引、资产重复生成 |
| `uk_llm_provider` | `llm_provider_config` | Provider 去重更新 | 唯一约束、配置幂等、active 单选 |

### 6.3 状态一致性设计

`processing_job` 里有：

```text
current_step
status
progress
retry_count
error_message
started_at
finished_at
version
```

当前任务流转通过 `version` 做乐观锁式更新边界：

```text
WHERE id = ? AND version = ?
```

这可以在面试中展开：

- 为什么长任务不能同步阻塞 HTTP。
- 为什么任务状态需要落库。
- 为什么需要乐观锁。
- 状态乱序如何防。
- 失败任务如何后续补偿。

## 7. Redis 设计

Docker profile 下启用 Redis，默认 profile 使用本地内存实现。

### 7.1 MD5 去重锁

Redis Key：

```text
video:lock:{md5}
```

实现方式：

```text
SET key token NX PX ttl
```

当前代码使用 `StringRedisTemplate.opsForValue().setIfAbsent(key, token, ttl)`。

释放时会校验 token：

```text
只有 key 当前值等于本请求 token 时才删除
```

面试钩子：

- `SETNX`
- 锁过期时间
- token 防误删
- Redisson WatchDog 可作为升级点
- MySQL 唯一索引兜底

### 7.2 任务进度缓存

Redis Key：

```text
omnivid:progress:{videoId}
```

类型：

```text
Hash
```

字段：

```text
jobId
currentStep
status
progress
```

TTL：

```text
30 minutes
```

用途：

- 前端查询进度优先读缓存。
- SSE 每 500ms 推送进度快照。
- MySQL 仍保存最终任务状态。

### 7.3 Agent 限流

Redis Key：

```text
omnivid:agent:rate:{scope}:{window}
```

当前策略：

```text
10 秒窗口内最多 5 次
```

实现方式：

1. `INCR key`
2. 第一次写入时设置过期时间
3. 超过阈值返回 429

面试钩子：

- 计数器限流
- 固定窗口缺点
- Lua 原子性升级
- 令牌桶/滑动窗口升级

## 8. 前端功能结构

当前前端是单页工作台，核心区域分三栏。

### 8.1 左侧：上传与工程钩子

组件：

- `UploadPanel`
- `VideoLibraryPanel`
- `PipelinePanel`
- `HookPanel`

用户能看到：

- 上传入口。
- 平台 URL 导入入口。
- 去重结果。
- 任务状态和进度条。
- 视频知识库列表。
- 轻量 DAG 阶段。
- MySQL 面试钩子实时数据。

### 8.2 中间：视频与字幕

组件：

- `VideoPanel`
- `TranscriptPanel`

用户能看到：

- 真实上传的视频。
- 当前播放时间。
- 当前引用片段。
- ASR 字幕列表。
- 点击字幕跳转。
- 播放时自动高亮字幕。

### 8.3 右侧：总结与 Agent

组件：

- `SummaryPanel`
- `AgentPanel`

用户能看到：

- 结构化总结。
- 面试钩子 mindmap 式展示。
- 当前视频问答。
- 知识库问答。
- 引用时间戳按钮。

## 9. 目录与代码落点

| 路径 | 作用 |
| --- | --- |
| `apps/web/src/main.tsx` | 前端工作台主逻辑 |
| `apps/web/src/styles.css` | 暗色 UI 样式 |
| `apps/api/src/main/java/com/omnivid/api/video` | 视频上传、详情、媒体、进度入口 |
| `apps/api/src/main/java/com/omnivid/api/storage` | 本地文件存储 |
| `apps/api/src/main/java/com/omnivid/api/media` | ffmpeg 抽音频、ffprobe 时长 |
| `apps/api/src/main/java/com/omnivid/api/asr` | whisper.cpp ASR |
| `apps/api/src/main/java/com/omnivid/api/job` | 任务状态机和线程池 |
| `apps/api/src/main/java/com/omnivid/api/progress` | 本地/Redis 进度缓存 |
| `apps/api/src/main/java/com/omnivid/api/dedupe` | 本地/Redis 去重锁 |
| `apps/api/src/main/java/com/omnivid/api/ratelimit` | 本地/Redis Agent 限流 |
| `apps/api/src/main/java/com/omnivid/api/agent` | 当前视频和知识库 Agent |
| `apps/api/src/main/java/com/omnivid/api/transcript` | 字幕表访问 |
| `apps/api/src/main/java/com/omnivid/api/summary` | 总结资产访问 |
| `apps/api/src/main/resources/schema-mysql.sql` | MySQL 表结构 |
| `apps/api/src/main/resources/application.yml` | 默认/H2 配置 |
| `apps/api/src/main/resources/application-docker.yml` | MySQL/Redis 配置 |
| `infra/docker-compose.yml` | MySQL + Redis 容器 |

## 10. 黑盒验收清单

### 10.1 服务健康

```powershell
Invoke-RestMethod http://localhost:8080/api/health
```

预期：

```text
返回服务健康 JSON 或 OK 信息
```

### 10.2 上传视频

```powershell
curl.exe -F "file=@E:\video\tools\asr\speech-sample.mp4" http://localhost:8080/api/videos/upload/file
```

预期：

- 返回 `video.id`。
- 返回 `job.id`。
- 返回 `deduplicated=false` 或 `true`。

### 10.3 查看进度

```powershell
Invoke-RestMethod http://localhost:8080/api/videos/1/progress
```

预期：

```text
progress 最终为 100
status 最终为 DONE
```

### 10.4 查看视频详情

```powershell
Invoke-RestMethod http://localhost:8080/api/videos/1
```

预期：

- `video.status = READY`
- `transcripts.length > 0`
- `summaries.length = 2`

### 10.5 查看 MySQL 表

```powershell
docker exec omnivid-mysql mysql -uomnivid -pomnivid_pass -D omnivid -e "SHOW TABLES;"
```

预期包含：

```text
video_asset
processing_job
transcript_segment
summary_asset
chat_message
users
```

### 10.6 验证字幕索引

```powershell
docker exec omnivid-mysql mysql -uomnivid -pomnivid_pass -D omnivid -e "EXPLAIN SELECT * FROM transcript_segment WHERE video_id = 1 ORDER BY start_ms ASC LIMIT 5;"
```

预期：

```text
key = idx_transcript_video_start
```

### 10.7 验证 Redis Key

```powershell
docker exec omnivid-redis redis-cli keys "*omnivid*"
```

可能看到：

```text
omnivid:progress:{videoId}
omnivid:agent:rate:{scope}:{window}
```

上传瞬间可能看到：

```text
video:lock:{md5}
```

### 10.8 验证 Agent

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/videos/1/agent/ask" -Method Post -ContentType "application/json" -Body '{"question":"你是谁"}'
```

预期：

- 返回 `answer`。
- 返回 `citation`。
- 返回 `startMs` 和 `endMs`。

## 11. 面试讲法总线

项目一句话：

```text
OmniVid 是一个长视频 AI 知识解析系统，我用 Java Spring Boot 搭了从大文件上传、MD5 去重、异步解析、ffmpeg/ASR、字幕时间轴、结构化总结到可追溯 Agent 问答的一整条后端链路。MySQL 负责最终事实和状态一致性，Redis 负责防重、进度缓存和限流，前端通过 SSE 和视频 Range 播放把异步任务过程可视化。
```

### 11.1 MySQL 钩子

可讲场景：

- 视频 MD5 去重。
- 任务状态机。
- 字幕时间轴查询。
- 总结资产幂等。
- 聊天记录留痕。

回答模板：

```text
同一个视频可能被多人重复上传，所以我用 MD5 做内容指纹。Redis 锁负责降低并发窗口内的重复请求，MySQL 的 uk_video_md5 唯一索引负责最终兜底。即使服务重启或 Redis 锁失效，MySQL 也能保证不会插入两条相同视频资产。
```

### 11.2 Redis 钩子

可讲场景：

- `video:lock:{md5}` 防重复上传。
- `omnivid:progress:{videoId}` 缓存任务进度。
- `omnivid:agent:rate:{scope}:{window}` 限制高频问答。

回答模板：

```text
Redis 在这个项目里不是事实源，而是性能层和并发控制层。比如上传时用 SET NX PX 做短锁，进度用 Hash 缓存并设置 TTL，Agent 问答用 INCR 做固定窗口限流。最终视频资产、任务状态和聊天记录仍然落 MySQL。
```

### 11.3 Java 并发钩子

可讲场景：

- 上传请求快速返回。
- 后端线程池执行本地 DAG。
- ffmpeg 和 ASR 是长耗时阻塞任务。
- SSE 独立推送任务进度。

回答模板：

```text
视频解析属于长耗时任务，我没有让 HTTP 请求同步等待，而是创建 processing_job 后把抽音频、ASR、总结放进 ThreadPoolTaskExecutor 执行。任务状态每一步落库并缓存到 Redis，前端通过 SSE 观察状态推进。
```

### 11.4 JVM/操作系统钩子

可讲场景：

- 大文件上传不能一次性读入内存。
- ffmpeg/whisper 是外部进程。
- 子进程要处理超时和日志。
- 本地文件存储涉及顺序 IO。

回答模板：

```text
上传和抽音频都容易引发 OOM 或阻塞问题，所以我按流式方式保存文件和计算 MD5；ffmpeg 和 whisper.cpp 作为外部进程调用，设置超时并把日志落文件，失败后推进任务失败状态。
```

### 11.5 网络钩子

可讲场景：

- SSE 进度推送。
- 视频播放 Range 请求。
- Multipart 上传。

回答模板：

```text
任务进度是服务端单向推给前端，所以我用 SSE 而不是 WebSocket，复杂度更低。视频播放接口支持 Range，浏览器拖动进度条时可以请求部分字节，不需要一次性下载完整视频。
```

### 11.6 AI Agent 钩子

可讲场景：

- 当前视频检索问答。
- 默认知识库跨视频问答。
- 回答必须带时间戳引用。
- 执行轨迹展示输入安全、记忆、召回、引用、置信度和落库步骤。
- Prompt Injection Guard 拦截绕过引用、伪造时间戳、泄露系统提示和越权访问类问题。
- 无证据通用回答：先说明视频/知识库未命中，再调用 LLM 做补充。

回答模板：

```text
当前 Agent 先做输入安全检查，再从当前视频或默认知识库中通过 Qdrant 做字幕向量召回，并结合关键词分数 rerank，构造 videoId、startMs、endMs、citation。云端 LLM 启用时，有证据就只拿这些 citations 生成回答，不能自己编时间戳；无证据就先披露未命中，再走通用 LLM 回答。DeepSeek 当前不提供 `/embeddings` 时会自动降级本地 hash 向量，下一阶段可以替换为更强 Embedding 模型和专门的 rerank 模型。
```

## 12. 简历写法

可直接使用的简历 bullet：

```text
基于 Spring Boot 构建长视频 AI 解析系统，实现本地视频上传、流式 MD5 计算、内容去重、异步解析任务状态机和视频资产管理。
```

```text
接入 B站/抖音/小红书公开 URL 导入能力，基于 yt-dlp 下载平台视频后复用本地上传、MD5 去重和异步解析链路，统一文件与 URL 两类入口。
```

```text
设计 MySQL 表结构保存视频资产、解析任务、时间轴字幕、总结资产和 Agent 聊天记录，通过唯一索引、联合索引和乐观锁式 version 字段保证幂等性、状态一致性和查询效率。
```

```text
接入 Redis 实现视频上传防重锁、任务进度缓存和 Agent 问答限流，将 Redis 作为性能层，MySQL 作为最终事实源。
```

```text
使用 ThreadPoolTaskExecutor 实现轻量 DAG 异步流水线，串联 ffmpeg 抽音频、whisper.cpp ASR 和本地结构化总结，并通过 SSE 向前端实时推送任务进度。
```

```text
实现视频媒体 Range 播放接口和时间轴字幕点击跳转能力，基于 video_id + start_ms 联合索引优化字幕查询，并通过 EXPLAIN 验证索引命中。
```

```text
构建带可追溯引用和执行轨迹的轻量 Agent 问答模块，支持当前视频和默认知识库跨视频字幕检索，回答返回视频来源、时间戳和 trace 步骤，并将问答记录落库。
```

```text
接入 OpenAI-compatible 云端模型网关，支持通过环境变量切换 DeepSeek/Qwen/OpenAI 兼容模型；总结和 Agent 回答优先调用 LLM，失败自动降级本地生成，保证演示链路稳定。
```

```text
实现 Agent 输入安全 Guardrail，在字幕召回前拦截绕过引用、伪造时间戳、泄露系统提示和越权访问类问题，安全拒答写入审计但不污染短期记忆。
```

## 13. 当前边界与后续路线

下一步推荐按“一次只加一个模块”的节奏继续：

| 优先级 | 模块 | 为什么做 |
| --- | --- | --- |
| P1 | Redis 面试钩子专项文档 | 和 MySQL 文档对称，补齐缓存/锁/限流八股 |
| P1 | 更强 Embedding + rerank | 把当前 local-hash fallback 升级为真实语义 Embedding 和专门重排模型 |
| P1 | LLM 结果引用校验 | 对 Agent 生成答案做 citation 白名单校验和低置信度拒答 |
| P2 | Agent 语义缓存 | 对应 Redis Vector/缓存穿透/击穿/雪崩 |
| P2 | 失败重试和任务补偿 | 强化 MQ/任务系统面试叙事 |
| P2 | RocketMQ 演进 | 从本地 DAG 升级到消息队列 |
| P3 | URL 导入增强 | 支持 cookie、私密视频、下载任务异步化和更多平台 |
| P3 | 登录和多用户 | 从 demo 用户升级为真实用户体系 |

当前最适合面试讲的版本定位：

```text
这是一个 Java 后端主导的长视频 AI 解析 MVP，已经跑通真实上传、ASR、字幕、总结、SSE、MySQL、Redis、Qdrant 外部向量库、轻量 Agent、DeepSeek Chat 和向量召回链路。更强 Embedding、专门重排、严格引用校验和 RocketMQ 是下一阶段演进点，我会在面试中明确区分已实现能力和架构升级方向。
```
