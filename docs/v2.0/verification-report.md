# OmniVid Version 2.0 正式验收报告

验收时间：2026-06-12

## 结论

OmniVid 2.0 已通过代码测试、前端构建、Docker 运行态、媒体播放、基础设施连接、Trace、文档完整性和敏感信息扫描，可以作为 `v2.0` 发布。

## 自动化验证

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 后端测试 | 通过 | 16 tests，0 failures，0 errors |
| 前端生产构建 | 通过 | TypeScript + Vite，1702 modules transformed |
| Compose 配置 | 通过 | `docker compose ... config --quiet` 退出码 0 |
| Docker 镜像与完整重启 | 通过 | API 产物 `api-2.0.0.jar`、Web `omnivid-web@2.0.0`，完整 app profile 重建成功 |
| Git diff 检查 | 通过 | 无 whitespace error，仅有 Windows 行尾提示 |
| v2.0 文档乱码检查 | 通过 | 无 mojibake 标记 |
| v2.0 文档链接检查 | 通过 | 相对链接目标全部存在 |
| 敏感信息扫描 | 通过 | 未发现真实 API Key、GitHub Token 或 Bearer Token |

## Docker 运行态

| 项目 | 结果 |
| --- | --- |
| Profile | `docker` |
| MySQL | connected |
| Redis | connected |
| Qdrant | connected，collection `green` |
| RocketMQ | connected，mode=`rocketmq` |
| JSON Logs | enabled |
| Pending Events | 0 |
| DLQ Events | 0 |
| 视频库 | 11 条 |

发布验收时外部 Embedding 未配置，因此使用 `local-hash`；远程 Rerank 尚未证明可用，因此使用 `local-rerank`。这是预期降级路径，不影响 Qdrant 和 Agent 基础链路。

## 黑盒验证

### 媒体播放

- 对视频库 11 条记录逐一通过 Web Nginx `/api` 代理发送 Range 请求。
- 全部返回 `206 Partial Content`。
- 历史视频文件由 `apps/api/storage` bind mount 提供。

### Trace

- 通过 Web 代理发送自定义 `X-Trace-Id`。
- API 返回状态 200，并在响应头原样返回相同 Trace ID。

### Provider 与知识库

- LLM Provider 管理接口可访问，当前保存 1 个 Provider。
- Embedding Provider 管理接口可访问，当前未保存外部 Provider。
- Rerank Provider 管理接口可访问，当前保存 1 个远程配置并自动降级。
- Provider 新保存数据使用 AES-GCM；历史 Base64 配置可继续读取，建议用户通过 Rotate 迁移。

### Qdrant 与 RocketMQ

- Qdrant collection 存在且状态为 green。
- RocketMQ Publisher/Consumer 连接正常。
- Outbox 当前无待投递事件和 DLQ 事件。

## 测试覆盖

- Spring 上下文与 Trace Header。
- Provider AES-GCM 加解密和旧 Base64 兼容。
- Outbox 唯一约束、CAS 抢占和中断恢复。
- 字幕技术词、简体、乱码清理。
- 上下文修复避免无证据过拟合。
- Markdown、DOCX、PPTX 文件渲染可用性。

## 已知非阻塞边界

- 登录、多租户与企业权限推迟到 2.1+。
- 外部 Embedding/Rerank 需要用户自行配置可用服务。
- 平台 URL 导入受平台授权、Cookie 和反爬限制。
- ASR/OCR 不承诺人工字幕级准确率。
- RocketMQ Consumer 当前仍与 API 同进程部署。
