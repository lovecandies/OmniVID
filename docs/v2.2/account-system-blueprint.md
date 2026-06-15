# v2.2 产品账号体系蓝图

## 目标

OmniVid v2.2 的产品账号体系围绕“公网发布后的真实数据边界”设计。用户能够管理自己的账号、设备、配额和数据；管理员能够处理异常任务和资源占用，但不能越权查看用户隐私数据或 API Key 明文。

## 功能路线

1. 邮箱验证
   - 注册后用户保持可登录。
   - 用户可请求邮箱验证 Token。
   - 验证成功后 `users.email_verified=true`。
   - Demo 环境返回 dev token，生产环境只返回发送状态，后续可接真实邮件服务。

2. 忘记密码与修改密码
   - 忘记密码按邮箱生成一次性 Token。
   - 重置成功后更新 `password_hash`、`password_updated_at`，并消费 Token。
   - 登录态用户可通过旧密码修改密码。

3. 登录设备与 Session 管理
   - 基于 Spring Session Indexed Repository 查询当前用户 Session。
   - 当前用户只能查看和删除自己的 Session。
   - 管理员可以按用户主动失效 Session。

4. 用户配额
   - 默认限制视频数量、知识库数量和存储字节数。
   - 上传文件、分片上传会话、URL 导入和知识库创建前先做配额检查。
   - 用量由 MySQL 实时统计，避免只依赖缓存。

5. 数据导出与账号注销
   - 导出当前用户的视频、任务、字幕、总结、知识库、Provider masked 配置和聊天记录。
   - 注销采用软删除：账号禁用并清除邮箱唯一占用，业务数据保留为不可登录状态，后续可扩展异步清理。

6. 管理员控制台接口
   - 用户列表与详情。
   - 任务列表、失败任务列表、异常任务标记。
   - 资源占用汇总。
   - Provider 配置只展示 `api_key_masked`，不解密、不返回明文。

## 数据模型

- `users`
  - `email_verified`
  - `disabled`
  - `deleted_at`
  - `password_updated_at`

- `account_token`
  - `token`
  - `user_id`
  - `purpose`
  - `expires_at`
  - `consumed_at`

- `user_quota`
  - `user_id`
  - `max_storage_bytes`
  - `max_video_count`
  - `max_knowledge_base_count`

## 用户视角验收流

1. 注册用户 A 和用户 B。
2. 用户 A 上传视频，用户 B 无法查看、播放、导出或问答该视频。
3. 用户 A 创建知识库到达限制后，再创建会收到明确的配额错误。
4. 用户 A 请求密码重置，用 Token 修改密码，旧密码登录失败，新密码登录成功。
5. 用户 A 打开 Session 列表，删除另一个 Session 后该设备失效。
6. 管理员登录后能查看用户和失败任务，但 Provider API Key 始终是 `sk-...xxxx` 样式。

## 简历话术

- 基于 Spring Security 与 Redis Session 构建产品级账号体系，支持登录设备管理、Session 主动失效与管理员权限隔离。
- 设计 MySQL 配额模型与用户资源统计，在视频上传、分片上传、URL 导入和知识库创建入口做统一配额拦截。
- 实现邮箱验证、忘记密码、修改密码和账号注销链路，通过一次性 Token、过期时间和消费状态保证安全性。
- 构建管理员控制台接口用于异常任务处理和资源占用排查，同时保证用户 Provider API Key 仅密文存储、仅掩码展示。

## v2.2 已实现接口

账号自助：

- `GET /api/account/quota`
- `POST /api/account/email/verification/request`
- `POST /api/account/email/verification/confirm`
- `POST /api/account/password/forgot`
- `POST /api/account/password/reset`
- `POST /api/account/password/change`
- `GET /api/account/sessions`
- `DELETE /api/account/sessions/{sessionId}`
- `GET /api/account/export`
- `DELETE /api/account`

管理员控制台：

- `GET /api/admin/users`
- `GET /api/admin/users/{userId}`
- `GET /api/admin/resources`
- `GET /api/admin/tasks`
- `GET /api/admin/tasks/failures`
- `POST /api/admin/tasks/{jobId}/mark-failed`

前端入口：

- 右侧顶部新增“账号”交互入口。
- 展开后显示用户邮箱验证状态、配额、登录设备、数据导出、账号注销和管理员控制台摘要。
- 普通用户会看到管理员权限提示；管理员可查看资源占用、用户列表和失败任务。

## v2.2 验证记录

- 后端：`apps/api` 下执行 `./mvnw.cmd test`，31 个测试通过。
- 前端：`apps/web` 下执行 `npm run build`，TypeScript 与 Vite 构建通过。
- 浏览器：打开 `http://127.0.0.1:5174`，登录工作台正常渲染，无控制台错误。

## v2.2 公网安全收束

- `/api/vector-index/**` 已收紧为管理员权限，普通用户不能触发重建索引。
- 登录限流与 API 限流统一通过 `ClientIpResolver` 解析客户端 IP，只在可信内网/本机代理来源时读取 `X-Forwarded-For`。
- Nginx 转发时覆盖 `X-Forwarded-For` 为 `$remote_addr`，避免客户端伪造链路绕过限流。
- Docker Compose 内部服务端口绑定到 `127.0.0.1`，公网入口只保留 Caddy 网关。
- `backups/` 已加入 `.gitignore`，避免 MySQL dump 和 Provider 加密密钥备份误提交。
- `restore-production.ps1` 已检查 `docker cp` 和 MySQL restore 的退出码，失败时不再误报成功。

## 面试追问预案

- 问：为什么邮箱验证和重置密码要单独建 `account_token` 表？
  答：Token 是一次性凭证，和用户主表生命周期不同。单表可表达 purpose、过期时间、消费状态，便于加索引、审计和定时清理。

- 问：为什么配额统计不只放 Redis？
  答：配额属于计费和资源边界，必须以 MySQL 事实数据为准。Redis 可以做缓存，但最终判断需要回到 `video_asset` 和 `knowledge_base` 的用户归属统计。

- 问：管理员为什么不能看 API Key 明文？
  答：Provider Key 是用户私密凭证。系统只存加密密文和 masked key，管理员接口只查询 `api_key_masked`，不调用解密逻辑。

- 问：Session 管理为什么用 Redis Session？
  答：单机 HttpSession 只能管理当前进程内会话，公网部署和重启恢复需要集中式 Session。Redis Indexed Repository 可以按 principal 查询并主动失效设备。

- 问：账号注销为什么先做软删除？
  答：视频解析、导出、任务和审计存在异步链路。软删除可以立即禁止登录并释放邮箱唯一约束，后续再扩展异步物理清理，避免删除中途破坏数据一致性。
