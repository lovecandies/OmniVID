# OmniVid v2.1 Public Release Closure Blueprint

## Assumptions

- The release target is a single-node Docker Compose deployment with one API container.
- Public Web and API share one HTTPS origin through Caddy.
- A real domain and server credentials are external inputs; this repository ships reproducible deployment assets and validation scripts.
- User data isolation is enforced by authenticated user ownership checks, not by trusting frontend state.
- Redis is mandatory for public mode: sessions, login throttling, API throttling, task progress, Agent cache and short-term memory.

## Delivery Scope

1. Tenant boundary audit
   - Video list/detail/media/transcripts/summaries/progress
   - Processing jobs
   - Transcript edit/version/restore
   - ASR diagnostics and repair actions
   - Export generation
   - Agent messages and question answering
   - Knowledge bases and Provider configs

2. Redis distributed security
   - Redis-backed login failure windows
   - CAPTCHA-required signal after configurable failure threshold
   - Redis-backed API rate limiting
   - Admin endpoint for active session invalidation

3. Production deployment
   - Docker restart policy for automatic recovery after reboot
   - Caddy HTTPS gateway profile
   - Production secret and domain checks
   - Public configuration verification commands

4. Backup and restore
   - MySQL logical backup
   - Provider encryption secret configuration backup
   - Video storage metadata manifest
   - Restore script with dry-run default and explicit apply switch

## Black-Box Acceptance

1. User B cannot list, view, play, export, query, edit, reprocess or diagnose User A's video.
2. User B cannot read User A's job, Agent messages, knowledge base or Provider configs.
3. Login failures write to Redis and still block after API restart.
4. Repeated API requests return `429` after the configured distributed window.
5. Admin can invalidate a user's active Redis Session.
6. Docker public configuration validates without placeholder production secrets.
7. Backup script creates database SQL, env backup and storage manifest; restore script runs dry-run safely.

## Current Verification Record

- Backend unit and integration tests: `27` passed, `0` failed.
- Frontend production build: `npm run build` passed and generated `apps/web/dist`.
- Docker app profile: MySQL, Redis, Qdrant, RocketMQ, API and Web started; API and Web containers reported `healthy`.
- API health endpoint: `GET http://localhost:8080/api/health` returned `{"service":"omnivid-api","status":"UP"}`.
- Tenant isolation matrix now covers media playback, transcripts, transcript versions, edit/restore, summaries, progress, ASR repair/reprocess, Agent, export, knowledge base and Provider operations.
- Docker profile uses Redis for login throttling, API throttling, indexed sessions, progress, Agent answer cache and short-term memory.
- Redis login throttling black-box result:
  - second failed login returned `captchaRequired=true`;
  - `omnivid:security:login-fail:*` existed in Redis;
  - after `docker restart omnivid-api`, the same login window still returned `429`.
- Redis API throttling black-box result: 13 consecutive CSRF requests returned `200` for requests 1-12 and `429` for request 13 with the configured test window.
- Redis Session invalidation black-box result: admin invalidated one active target user session; the target user's next `GET /api/videos` returned `401`.
- Production preflight: `.\scripts\public-preflight.ps1` passed with a temporary non-placeholder `infra/.env`; the temporary file was removed after verification.
- Backup rehearsal: `.\scripts\backup-production.ps1` created `backups/omnivid-20260613-184209`.
- Restore rehearsal: `.\scripts\restore-production.ps1 -BackupDir .\backups\omnivid-20260613-184209` completed dry-run successfully.

## Release Notes

- `spring.session.redis.repository-type=indexed` is required in Docker/Redis profiles so `/api/admin/sessions/users/{userId}/invalidate` can find sessions by principal name.
- `infra/.env` must not be committed. The repository keeps only `infra/.env.example`; production deployers create the real file on the server.
- Public production must keep `OMNIVID_COMPATIBILITY_UPLOAD_ENABLED=false` and `OMNIVID_URL_IMPORT_ALLOW_LOCAL_COOKIE_SOURCE=false`.
