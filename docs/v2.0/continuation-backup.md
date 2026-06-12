# OmniVid 2.0 续作交接备份

更新时间：2026-06-12

## 当前版本基线

- 仓库：`https://github.com/lovecandies/OmniVID`
- 分支：`main`
- 发布标签：`v2.0`
- Web：`http://127.0.0.1:5174`
- API：`http://127.0.0.1:8080`
- 默认启动方式：Docker MySQL/Redis/Qdrant/RocketMQ 模式。

## 一键恢复开发环境

```powershell
cd E:\video
.\scripts\start-full-docker.ps1
```

检查：

```powershell
docker compose -f infra/docker-compose.yml --profile app ps
Invoke-RestMethod http://127.0.0.1:8080/api/runtime/status | ConvertTo-Json -Depth 8
Invoke-RestMethod http://127.0.0.1:8080/api/vector-index/status | ConvertTo-Json -Depth 8
```

## 重要目录

| 目录 | 职责 |
| --- | --- |
| `apps/api/src/main/java/com/omnivid/api/video` | 上传完成、视频详情、解析 DAG、媒体播放 |
| `apps/api/src/main/java/com/omnivid/api/upload` | 分片上传与合并 |
| `apps/api/src/main/java/com/omnivid/api/job/mq` | Outbox、RocketMQ、重试、DLQ |
| `apps/api/src/main/java/com/omnivid/api/asr` | Whisper、OCR、质量诊断 |
| `apps/api/src/main/java/com/omnivid/api/transcript` | 字幕、术语、版本、修复 |
| `apps/api/src/main/java/com/omnivid/api/agent` | 当前视频和知识库 Agent |
| `apps/api/src/main/java/com/omnivid/api/agent/retrieval` | Embedding、Qdrant、Rerank、向量索引 |
| `apps/api/src/main/java/com/omnivid/api/knowledge` | 知识库管理、覆盖、观点对比 |
| `apps/api/src/main/java/com/omnivid/api/export` | Markdown/DOCX/PPTX 导出 |
| `apps/api/src/main/java/com/omnivid/api/security` | Provider Key 加密 |
| `apps/api/src/main/java/com/omnivid/api/observability` | Trace 与 MDC |
| `apps/web/src/main.tsx` | 当前工作台完整交互 |
| `apps/web/src/styles.css` | 工作台暗色布局 |
| `infra/docker-compose.yml` | 完整基础设施和 app profile |
| `apps/api/storage` | Docker 与本地 Maven 共享视频存储 |

## 必须保留的设计约束

- 不绕过平台反爬、CAPTCHA、DRM 或登录限制。
- MySQL 是最终事实；Redis 和 Qdrant 可重建。
- Provider Key 不得明文落库、日志或 Git。
- 字幕 OCR 写回必须保守，不能覆盖高质量 ASR。
- 字幕变化必须回流总结、向量索引和 Agent 缓存。
- 重复 MQ 消息不能重复执行解析。
- Docker 与本地 API 必须共享 `apps/api/storage`。

## 当前外部依赖状态

- DeepSeek：用于 Chat、总结和详细导出。
- Embedding：支持 Qwen/OpenAI/BGE OpenAI-compatible；未配置时使用 `local-hash`。
- Rerank：支持 BGE/OpenAI-compatible；不可用时使用 `local-rerank`。
- Qdrant：Docker 模式默认使用。
- Whisper/OCR：依赖本机/容器可用模型和 OCR 环境。

## 2.1 首选方向

1. 登录、权限和真正的用户数据隔离。
2. 对象存储、上传会话清理和生命周期管理。
3. 独立解析 Worker、水平扩容和任务超时。
4. 真实 Embedding/Rerank 默认部署模板与离线评估集。
5. 导出资产持久化、下载鉴权和模板系统。
6. Prometheus/Grafana/OpenTelemetry。

## 故障定位顺序

```text
页面提示
  -> /api/runtime/status
  -> /api/jobs/mq/status 与 /api/jobs/events
  -> /api/videos/{id}/progress
  -> /api/videos/{id}/asr/diagnostics
  -> /api/vector-index/status
  -> docker logs omnivid-api
  -> MySQL processing_job / processing_event
```

视频点击出现 `Video file not found` 时，先检查：

```powershell
docker exec omnivid-api sh -lc "ls -la /data/omnivid/storage/videos"
```

Compose 必须把 `../apps/api/storage` 挂载到 `/data/omnivid/storage`。

## 发布前检查

```powershell
cd E:\video\apps\api
.\mvnw.cmd test

cd E:\video\apps\web
npm run build

cd E:\video
docker compose -f infra/docker-compose.yml --profile app config --quiet
git diff --check
```

最后执行敏感信息扫描，确认没有真实 API Key、Cookie 或个人凭据再提交。
