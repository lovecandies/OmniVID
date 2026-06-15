# OmniVid v2.1 Multi-Tenant Isolation Blueprint

## Scope

This slice turns the v2.1 login session into a real data boundary:

1. Video list/detail/media/transcript/summary/export/Agent APIs are scoped to the current logged-in user.
2. Chunked upload sessions are owned by the current user.
3. Knowledge bases are owned by the current user, and can only include that user's videos.
4. LLM, Embedding and Rerank provider rows are owned by the current user.
5. Agent cache scopes include the user boundary for default knowledge-base queries.

## Product Decision

MD5 dedupe is now tenant-local in v2.1. Cross-tenant instant reuse should be reintroduced later with a separate public `content_asset` table and a private `user_video_asset` authorization table. Reusing another user's `video_asset` row directly would leak ownership and break resource checks.

## Database Boundary

Tables with tenant columns:

- `video_asset.user_id`
- `upload_session.user_id`
- `knowledge_base.user_id`
- `llm_provider_config.user_id`
- `embedding_provider_config.user_id`
- `rerank_provider_config.user_id`

Tenant-aware unique keys:

- `video_asset(user_id, md5)`
- `knowledge_base(user_id, name)`
- `llm_provider_config(user_id, provider_name, base_url, model)`
- `embedding_provider_config(user_id, mode, base_url, model)`
- `rerank_provider_config(user_id, mode, base_url, endpoint, model)`

## Black-Box Validation

1. A user creates a video row -> 验证: A can open `/api/videos/{id}`, B gets `404`.
2. A user creates a chunk upload session -> 验证: B cannot read or complete that session.
3. A user creates a knowledge base -> 验证: B cannot list/detail/delete it.
4. A user saves a provider -> 验证: B provider list is empty and B cannot activate A's provider ID.
5. A/B upload the same MD5 -> 验证: each user gets a separate `video_asset` row.

## Implementation Status

已完成并通过自动化验证：

- 视频上传、列表、详情、媒体、字幕、总结、导出、Agent 入口使用当前登录用户校验资源归属。
- 分片上传 session 使用当前登录用户创建和读取，非 owner 访问返回 `404`。
- 知识库列表、详情、增删视频、删除、覆盖率和对比都限制在当前用户的视频集合内。
- LLM / Embedding / Rerank Provider 按用户保存、激活、测试和删除；每次 Agent / 导出 / 向量重建前加载当前用户 active provider。
- Agent 缓存和限流 scope 增加 `user:{id}` 前缀，默认知识库不再跨用户共享缓存。
- 后台云端总结使用 `video_asset.user_id` 加载视频所有者的 LLM Provider，避免异步线程丢失 SecurityContext 后误用其他用户配置。
- 诊断接口中的失败 job、RocketMQ event、ASR diagnostics 均通过视频归属进行隔离。

自动化用例：`TenantIsolationTests` 覆盖 A/B 用户同 MD5 上传、视频越权 `404`、知识库越权 `404`、Provider 列表隔离和跨用户激活失败。

## Interview Hooks

- Spring Security: session principal -> service-layer ownership checks.
- MySQL: composite unique keys, index migration, tenant-local MD5 dedupe.
- Redis Session: authentication is shared, authorization remains enforced in SQL/service layer.
- RAG/Agent: cache key must include tenant scope, otherwise answer cache can leak across users.
- SaaS design: authentication proves who you are; authorization proves which resource you can touch.

## Final Acceptance - 2026-06-13

- Provider runtime configuration is thread-scoped for LLM, Embedding and Rerank clients. Concurrent users cannot overwrite each other's in-memory API key or model configuration.
- Provider runtime context is cleared after every HTTP request, so pooled servlet threads do not retain tenant configuration.
- Qdrant rebuild is non-destructive. A tenant rebuild only upserts authorized transcript segments and never deletes the shared collection.
- If an embedding dimension does not match the existing Qdrant collection, the request falls back instead of recreating the collection and destroying other tenants' vectors.
- `TenantIsolationTests` now verifies video, processing job, Agent, export, knowledge base, chunk upload session, LLM provider, Embedding provider and Rerank provider boundaries.
- `ProviderRuntimeIsolationTests` verifies concurrent provider configuration remains isolated between threads.

Docker black-box result:

- User A can list its uploaded video.
- User B cannot list User A's video.
- User B receives `404` for User A's video detail, processing job and Agent endpoint.
- User A and User B receive their own active Provider model/base URL.
- Public runtime status contains only the default Provider configuration.
