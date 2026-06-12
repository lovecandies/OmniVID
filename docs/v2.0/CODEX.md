# OmniVid Version 2.0 开发约束

## 产品定位

OmniVid 2.0 是 Java 后端主导的长视频 AI 知识工作台。主线是可靠上传、异步解析、字幕回流、可追溯 Agent 和可部署性，AI 模型是可替换能力，不凌驾于后端工程主叙事。

## 已收束架构

```text
React/Vite/Nginx
  -> Spring Boot 模块化单体
  -> MySQL 最终事实
  -> Redis 高频状态
  -> MySQL Outbox + RocketMQ
  -> ffmpeg + Whisper + OCR
  -> DeepSeek Chat/Summary/Export
  -> Embedding + Qdrant + Rerank
```

## 强制设计原则

1. MySQL 是最终事实；Redis、Qdrant 和缓存必须可重建。
2. 视频、任务与 Outbox 事件必须保持事务一致性。
3. 重复上传、重复消息和重复重试必须幂等。
4. 字幕变化必须回流总结、向量索引和 Agent 缓存。
5. OCR 只能作为保守证据，不得无条件覆盖 ASR。
6. Provider Key 不得明文进入数据库、日志、前端响应或 Git。
7. 每个外部 AI Provider 必须有诊断和降级路径。
8. 不为展示技术名词拆微服务；当前保持模块化单体。
9. 不绕过平台反爬、CAPTCHA、登录、DRM 或版权限制。
10. Docker 与本地 Maven 使用同一份 `apps/api/storage`。

## 模块边界

| 模块 | 主要职责 | 不负责 |
| --- | --- | --- |
| `video` | 视频资产、解析编排、媒体播放 | Provider 管理 |
| `upload` | 分片会话、校验、合并 | 解析业务 |
| `job/mq` | Outbox、MQ、重试、DLQ | 具体解析节点 |
| `asr` / `transcript` | 字幕生成、修复、版本、回流 | Agent 生成 |
| `summary` / `export` | 总结与文件资产生成 | 字幕事实维护 |
| `agent` / `retrieval` | 检索、重排、引用、回答 | 视频解析 |
| `knowledge` | 多视频聚合边界 | 向量底层实现 |
| `security` | Provider Secret 加密 | 用户认证 |
| `observability` | Trace、MDC、结构化日志 | 业务状态 |

## 当前诚实边界

- 当前没有登录和多租户隔离。
- 当前 RocketMQ Consumer 与 API 同进程部署。
- 当前媒体使用本地共享目录，不是对象存储。
- 当前外部 Embedding/Rerank 需要用户自行配置。
- 当前 ASR/OCR 不承诺人工字幕级准确率。

## 变更验收最低要求

任何后续变更至少完成：

```powershell
cd E:\video\apps\api
.\mvnw.cmd test

cd E:\video\apps\web
npm run build

cd E:\video
docker compose -f infra/docker-compose.yml --profile app config --quiet
git diff --check
```

涉及用户流程、播放器或前端交互时，还必须在浏览器做黑盒验收。

涉及 MySQL/Redis/Qdrant/RocketMQ 时，默认使用 Docker profile 验收，不以 H2 单测代替真实基础设施检查。
