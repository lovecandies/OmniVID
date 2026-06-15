# OmniVid Infrastructure Blueprint

## Scope

This directory owns reproducible local deployment: MySQL, Redis, Qdrant, RocketMQ, API container, web container and Nginx proxy.

## Rules

1. Default `docker compose up -d` only starts shared infrastructure, so local Maven/Vite development remains fast.
2. Full reproduction uses the `app` profile: `docker compose --profile app up -d --build`.
3. Secrets must be passed through environment variables or `.env`, never committed.
4. API logs must stay structured JSON and include `traceId` where request, MQ or DAG context exists.
5. Web container must call the API through same-origin `/api` behind Nginx.
6. Public deployment uses the optional Caddy gateway profile for domain routing and automatic HTTPS.

## Black-Box Validation

1. `docker compose -f infra/docker-compose.yml --profile app config --quiet` exits successfully.
2. `GET http://localhost:8080/api/health` returns `UP`.
3. `GET http://localhost:5174` opens the workbench.
4. A request with `X-Trace-Id` returns the same response header and emits a JSON log containing that trace ID.
5. An authenticated admin can call `GET http://localhost:8080/api/runtime/status` to inspect Docker profile, MySQL, Redis, Qdrant and RocketMQ.
6. Docker/Redis profile must use indexed Redis Session storage so `POST /api/admin/sessions/users/{userId}/invalidate` can return `invalidatedSessions >= 1` for an active target user.
