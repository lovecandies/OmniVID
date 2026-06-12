# OmniVid Version 2.0 正式验收清单

## 发布门槛

| 类别 | 验收动作 | 预期结果 |
| --- | --- | --- |
| 后端 | `apps/api/mvnw.cmd test` | 所有测试通过 |
| 前端 | `npm run build` | TypeScript 与 Vite 生产构建通过 |
| Compose | `docker compose ... config --quiet` | 无配置错误 |
| Runtime | `GET /api/runtime/status` | MySQL、Redis、Qdrant、RocketMQ connected |
| 媒体 | Range 请求任一历史视频 | 返回 `206 Partial Content` |
| 安全 | 扫描 Git diff 与 Provider 表 | 无真实 Key 明文 |
| 文档 | 检查 `docs/v2.0` 索引 | 功能、架构、验收、面试、交接齐全 |

## 用户业务流验收

### 1. Provider 管理

1. 在云端 LLM 面板保存 DeepSeek Key。
2. 刷新页面，只显示 Key mask。
3. 测试连接成功。
4. 轮换、禁用、重新启用 Provider。
5. 验证数据库使用 `enc:v1:`，日志无明文。

### 2. 分片上传和可靠解析

1. 上传本地大视频。
2. 中断后重新选择同一文件。
3. 只上传缺失分片。
4. 合并 MD5 正确。
5. Outbox 事件进入 RocketMQ，任务最终 DONE。
6. 重复上传命中已有视频。

### 3. 视频、字幕和 ASR/OCR

1. 从视频库点击历史视频。
2. 播放器成功加载，拖动正常。
3. 点击字幕跳转时间点。
4. 打开 ASR 诊断，确认无乱码、替换字符和繁体残留风险。
5. 对含烧录字幕视频执行 OCR 评估/融合。

### 4. 字幕编辑与回流

1. 编辑一条字幕并保存。
2. 版本列表新增回滚点。
3. 总结和向量索引重新生成。
4. Agent 能检索修改后的字幕。
5. 恢复历史版本后内容还原。

### 5. Agent 与知识库

1. 对当前视频提问已出现内容。
2. 回答包含可点击时间戳引用。
3. 提问视频未出现内容。
4. 回答先声明无视频证据，再提供通用回答。
5. 创建知识库并加入多个视频。
6. 生成观点对比并点击跨视频引用。

### 6. Embedding、Qdrant 与 Rerank

1. 配置外部 Embedding Provider 并测试。
2. 重建向量索引。
3. Runtime 显示真实 Provider 和维度。
4. 配置 Rerank Provider 并测试。
5. Agent 执行链路显示向量召回和 rerank。
6. 停止远程 Provider，确认自动降级并显示诊断原因。

### 7. 文件导出

1. 选择核心观点、会议纪要、博客或 PPT 大纲。
2. 下载 Markdown、DOCX、PPTX。
3. 三种文件可打开，包含详细章节、行动项和时间戳来源。
4. DeepSeek 不可用时仍能生成本地结构化兜底文件。

### 8. 故障恢复与 Trace

1. 停止 RocketMQ Broker 后创建任务。
2. Outbox 事件保留并重试。
3. 恢复 Broker 后事件自动完成。
4. 构造失败事件进入 DLQ，再人工重投。
5. 使用同一个 `X-Trace-Id` 关联 HTTP、Outbox、MQ Consumer 和 DAG 日志。

## 不作为 2.0 发布阻塞项

- 登录、多租户和权限隔离。
- 对象存储与 CDN。
- 独立 Worker 集群。
- Kubernetes、APM 和告警平台。
- 外部 Embedding/Rerank 的实际账号和付费额度。
