# OmniVid 2.0 Docker / CI / Observability

更新时间：2026-06-12

## 部署目标

在新机器、GitHub Actions 和面试演示环境中稳定复现 Web、API、MySQL、Redis、Qdrant 和 RocketMQ，并用同一个 Trace ID 串联请求、消息和解析任务。

## 启动模式

基础设施模式：

```powershell
cd E:\video\infra
docker compose up -d
```

完整应用模式：

```powershell
cd E:\video
.\scripts\start-full-docker.ps1
```

等价命令：

```powershell
docker compose -f infra/docker-compose.yml --profile app up -d --build
```

访问：

- Web：`http://127.0.0.1:5174`
- API：`http://127.0.0.1:8080`
- Runtime：`http://127.0.0.1:8080/api/runtime/status`

## 容器职责

| 服务 | 职责 |
| --- | --- |
| `omnivid-web` | Nginx 托管 React 产物，并同源代理 `/api` |
| `omnivid-api` | Spring Boot API、MQ Consumer 和解析 DAG |
| `omnivid-mysql` | 最终业务事实、任务与 Outbox |
| `omnivid-redis` | 锁、进度、限流、缓存和短期记忆 |
| `omnivid-qdrant` | 字幕向量索引 |
| `omnivid-rocketmq-namesrv` | RocketMQ NameServer |
| `omnivid-rocketmq-broker` | RocketMQ Broker |

## 媒体持久化

Canonical 视频目录是：

```text
E:\video\apps\api\storage
```

Compose 将其 bind mount 到：

```text
/data/omnivid/storage
```

本地 Maven 与 Docker API 因此共享同一份视频。不要改回空命名卷，否则 MySQL 中存在的视频会出现 `Video file not found`。

验证：

```powershell
docker exec omnivid-api sh -lc "ls -la /data/omnivid/storage/videos"
curl.exe -I -H "Range: bytes=0-1023" http://127.0.0.1:8080/api/videos/11/media
```

预期媒体响应为 `206 Partial Content`。

## 环境变量和 Secrets

- 使用 `infra/.env.example` 作为模板。
- 真实 API Key 不提交 Git。
- `OMNIVID_PROVIDER_KEY_SECRET` 用于 Provider AES-GCM 加密。
- Docker 内访问宿主机模型服务使用 `host.docker.internal`，不能使用 `localhost`。

## Trace 链路

```text
HTTP request
  -> TraceFilter 生成/继承 X-Trace-Id
  -> MDC
  -> ProcessingCommand
  -> MySQL processing_event payload
  -> RocketMQ message property
  -> Consumer MDC
  -> VideoService DAG logs
```

日志使用 JSON，关键字段：

```text
traceId, method, path, status, durationMs, videoId, jobId, eventId
```

验证：

```powershell
$headers = @{"X-Trace-Id"="omnivid-v2-demo"}
Invoke-WebRequest http://127.0.0.1:8080/api/runtime/status -Headers $headers
docker logs omnivid-api --tail 100
```

## GitHub Actions

`.github/workflows/ci.yml` 包含：

1. Java 21 + Maven 后端测试。
2. Node 22 + npm 前端生产构建。
3. Compose 配置校验。
4. API 与 Web Docker 镜像构建。

本地等价检查：

```powershell
.\scripts\ci-local.ps1
```

## 黑盒验收

1. `docker compose ... config --quiet` 无输出且退出码为 0。
2. `docker compose ... ps` 中所有应用容器运行，API/Web 健康。
3. Runtime 显示 MySQL、Redis、Qdrant 和 RocketMQ connected。
4. 视频 Range 请求返回 206。
5. 手动 Trace ID 出现在响应头和 JSON 日志。
6. 停止 Broker 后 Outbox 不丢事件，恢复后自动消费。

## 面试钩子

- Docker：多阶段构建、镜像分层、健康检查、bind mount 与 named volume。
- CI：测试、构建、Compose 校验和镜像可复现。
- 网络：Nginx 同源代理、HTTP Range、SSE。
- 可观测：JSON logs、MDC、Trace ID 跨 MQ。
- 故障恢复：Outbox、DLQ、媒体目录持久化。
