# OmniVid 2.0 收束后续路线

## 2.0 已完成

- Provider Key 加密、mask、轮换、禁用和删除。
- 分片上传、断点续传、完整 MD5 校验和秒传。
- 外部 Embedding/Rerank Provider 管理与本地降级。
- Qdrant 外部向量存储和索引重建。
- ASR + OCR 默认保守融合、术语词库和低置信修复。
- 字幕编辑、版本、恢复与派生资产回流。
- 多视频知识库、覆盖统计、观点对比和引用跳转。
- MySQL Outbox + RocketMQ、消费幂等、重试、DLQ 和人工重投。
- DeepSeek 详细内容生成与 Markdown/DOCX/PPTX 导出。
- Docker 完整部署、GitHub Actions、JSON 日志和跨 MQ Trace。

## 2.1：用户与资产边界

### 登录和权限

- Spring Security 登录。
- Session/JWT 取舍后落地。
- 视频、知识库、聊天和 Provider 按用户隔离。
- 未授权资源返回 403/404。

### 对象存储

- 本地存储适配到 MinIO/S3。
- 分片上传直传。
- 导出资产持久化。
- 临时分片和过期文件清理。

### Provider 安全增强

- 主密钥轮换。
- Vault/KMS 适配。
- Provider 使用审计。
- 每用户 Provider 配置。

## 2.2：Worker 与质量评估

### 独立解析 Worker

- API 与 RocketMQ Consumer 分离部署。
- Worker 水平扩容。
- 节点超时、资源配额和取消任务。
- ffmpeg/ASR 隔离执行。

### RAG 离线评估

- 构建问题、标准引用和期望回答数据集。
- 评估 Recall@K、MRR、引用命中率和无证据判断。
- 比较不同 Embedding 与 Rerank Provider。
- 自动回归报告。

### ASR/OCR 质量评估

- 标注样本集。
- CER/WER 趋势。
- 术语命中率。
- OCR 覆盖率和错误覆盖监控。

## 3.0：企业化

- 组织、角色和知识库权限。
- 计费、配额和成本面板。
- Kubernetes、弹性伸缩和滚动发布。
- Prometheus、Grafana、OpenTelemetry 和告警。
- 对象存储、CDN 和多区域。

## 继续坚持的不做清单

- 平台反爬绕过。
- CAPTCHA、DRM 或账号自动化绕过。
- 为了技术名词数量提前拆微服务。
- 在没有评估集的情况下宣称 AI 精度提升。
- 把 Redis 或 Qdrant 当成不可恢复的最终事实。
