# OmniVid Local Infrastructure

This folder contains the reproducible Docker runtime for OmniVid: MySQL, Redis, Qdrant, RocketMQ, API and Web.

## Start

```powershell
cd E:\video\infra
docker compose up -d
```

This starts infrastructure only, so local Maven/Vite development remains fast.

## Full Reproduction

```powershell
cd E:\video
.\scripts\start-full-docker.ps1
```

Equivalent raw Docker command:

```powershell
cd E:\video\infra
docker compose --profile app up -d --build
```

Expected result:

- API health: `http://localhost:8080/api/health`
- Web: `http://localhost:5174`
- An authenticated admin can open `http://localhost:8080/api/runtime/status` to inspect MySQL, Redis, Qdrant and RocketMQ.

## Public Domain And PWA

1. Point the domain's `A`/`AAAA` record to the deployment server.
2. Copy `infra/.env.example` to `infra/.env` and replace every production placeholder.
3. Allow inbound TCP `80`, TCP `443` and UDP `443`.
4. Start the application and public HTTPS gateway:

```powershell
cd E:\video\infra
docker compose --profile app --profile public up -d --build
```

Caddy obtains and renews the TLS certificate automatically. Open `https://<OMNIVID_DOMAIN>` and use the browser's install action or OmniVid's **安装应用** button to install the PWA.

The PWA caches only the application shell and static assets. API responses, authenticated data and video media are never cached.

Required public-production values:

```dotenv
SPRING_PROFILES_ACTIVE=docker,production
SPRING_DATASOURCE_PASSWORD=<strong-random-password>
OMNIVID_PROVIDER_KEY_SECRET=<at-least-32-random-characters>
OMNIVID_ADMIN_EMAILS=admin@example.com
OMNIVID_DOMAIN=video.example.com
OMNIVID_PUBLIC_BASE_URL=https://video.example.com
OMNIVID_SESSION_COOKIE_SECURE=true
OMNIVID_LOGIN_MAX_FAILURES=5
OMNIVID_LOGIN_CAPTCHA_THRESHOLD=3
OMNIVID_LOGIN_FAILURE_WINDOW=10m
OMNIVID_API_RATE_LIMIT_MAX_REQUESTS=300
OMNIVID_API_RATE_LIMIT_WINDOW=1m
OMNIVID_COMPATIBILITY_UPLOAD_ENABLED=false
OMNIVID_URL_IMPORT_ALLOW_LOCAL_COOKIE_SOURCE=false
```

The `production` profile refuses to start with weak or placeholder secrets, a missing admin email, or a non-HTTPS public URL. It also disables metadata-only compatibility uploads and user-controlled server-local cookie sources.

Before starting the public gateway, run:

```powershell
cd E:\video
.\scripts\public-preflight.ps1
```

Expected result: `Public production preflight passed.`

## Run API With Local Maven And Docker Infra

```powershell
cd E:\video\apps\api
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=docker"
```

## Services

- MySQL: `localhost:3307`
  - Database: `omnivid`
  - User: `omnivid`
  - Password: `omnivid_pass`
- Redis: `localhost:6379`
- Qdrant: `localhost:6333`
- RocketMQ NameServer: `localhost:9876`
- RocketMQ Broker: `localhost:10911`

The default API profile still uses in-memory H2, so the project can run even before Docker Desktop is installed.

## Video Storage

Docker and local Maven mode share `apps/api/storage` as the canonical video storage directory. The API container bind-mounts it at `/data/omnivid/storage`, so existing MySQL video records remain playable after switching startup modes or recreating the API container.

## Observability

API logs are JSON. Every HTTP request returns `X-Trace-Id`; pass the same header from a client to correlate request logs, RocketMQ event logs and DAG execution logs.

Runtime, MySQL, Redis, JVM, vector-index and MQ diagnostic endpoints require an account listed in `OMNIVID_ADMIN_EMAILS`.

## Backup And Restore

Create a production backup:

```powershell
cd E:\video
.\scripts\backup-production.ps1
```

This writes a timestamped folder under `backups/` containing:

- `mysql-omnivid.sql`
- `infra.env.backup` or `infra.env.missing.txt`
- `storage-manifest.json`
- `backup-metadata.json`

For a full storage archive, add `-IncludeStorageFiles`. This can be large when GB-level videos are present.

Restore rehearsal is dry-run by default:

```powershell
cd E:\video
.\scripts\restore-production.ps1 -BackupDir .\backups\omnivid-YYYYMMDD-HHMMSS
```

Actual restore requires an explicit switch:

```powershell
.\scripts\restore-production.ps1 -BackupDir .\backups\omnivid-YYYYMMDD-HHMMSS -Apply
```

Add `-RestoreEnv` only when you intentionally want to overwrite `infra/.env`. Add `-RestoreStorageFiles` only for backups created with `-IncludeStorageFiles`.

## Security Acceptance

```powershell
cd E:\video\apps\api
.\mvnw.cmd test

cd E:\video\apps\web
npm run build

cd E:\video
docker compose -f infra\docker-compose.yml --profile app --profile public config --quiet
```

Local development defaults to in-process counters. Docker and public production use Redis-backed login throttling, API throttling, Session storage, Agent cache and short-term memory so limits survive API restarts.

Docker and public production also require indexed Redis Session storage:

```yaml
spring:
  session:
    store-type: redis
    redis:
      repository-type: indexed
```

This is required for `POST /api/admin/sessions/users/{userId}/invalidate`, because the admin endpoint must find active sessions by principal name before deleting them.

Latest v2.1 closure verification on this workstation:

- `mvn test`: `27` tests passed.
- `npm run build`: passed.
- Redis login throttling survived `docker restart omnivid-api`.
- API limiter returned `429` on the 13th request in the configured test window.
- Admin session invalidation returned `invalidatedSessions=1`; the target user's next `/api/videos` request returned `401`.
- `.\scripts\public-preflight.ps1`: passed with temporary production-like values.
- `.\scripts\backup-production.ps1`: created `backups/omnivid-20260613-184209`.
- `.\scripts\restore-production.ps1 -BackupDir .\backups\omnivid-20260613-184209`: dry-run passed.
