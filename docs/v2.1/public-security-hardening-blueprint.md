# OmniVid v2.1 Public Security Hardening Blueprint

## Assumptions

- The public Web app and API use one HTTPS origin.
- Existing authenticated users keep the same product workflow.
- Admin access is configured by `OMNIVID_ADMIN_EMAILS`; no role-management UI is introduced in this slice.
- Video uploads accept common containers only: MP4, MOV, MKV, WebM and AVI.
- Local development keeps permissive defaults; the `production` Spring profile enforces release secrets.

## Security Boundary

### Public endpoints

- `GET /api/health`
- `GET /api/auth/csrf`
- `POST /api/auth/register`
- `POST /api/auth/login`

All unsafe HTTP methods require the CSRF token returned by `/api/auth/csrf`.

### Authenticated endpoints

- Video upload, processing, playback, transcripts and summaries
- Agent, knowledge base and exports
- Provider configuration

### Admin-only diagnostics

- `/api/runtime/**`
- `/api/mysql/**`
- `/api/redis/**`
- `/api/jvm/**`
- `/api/vector-index/status`
- `/api/jobs/mq/status`

## Controls

1. CSRF: cookie-backed token plus `X-XSRF-TOKEN` request header.
2. Login throttling: IP + normalized email failure window; successful login clears failures.
3. Upload validation: filename extension, declared content type, configured maximum size and media container signature.
4. Diagnostics authorization: only configured admin emails receive `ROLE_ADMIN`.
5. Production startup guard: refuses placeholder provider secret, missing admin email, weak database password or non-HTTPS public domain.

## Black-Box Acceptance

1. POST without CSRF token -> `403`; normal browser workflow still works.
2. Repeated bad login attempts -> `429`; successful login after the window works.
3. Text/script renamed as `.mp4` -> `400`; a valid video container is accepted.
4. Normal user opens diagnostics -> `403`; configured admin receives `200`.
5. Production profile with placeholder secrets -> application startup fails with an explicit message.

## Release Defaults

- Local Docker defaults to the `docker` profile and keeps compatibility tools enabled.
- Public deployment must set `SPRING_PROFILES_ACTIVE=docker,production`.
- The production profile disables metadata-only compatibility uploads and user-controlled server-local cookie sources.
- The current login limiter is process-local because the documented Compose topology runs one API instance. Multi-instance deployment requires a Redis-backed limiter.

## Verification Record

- Backend: `26` tests passed, `0` failures.
- Frontend: TypeScript and Vite production build passed.
- Docker: application and public gateway Compose configuration validated.
- Docker runtime: API, Web, MySQL and Redis reported healthy; `/api/health` returned `UP`.
- HTTP black box: missing CSRF `403`, normal-user diagnostics `403`, admin diagnostics `200`, sixth bad login `429`, fake `.mp4` upload `400`.
- Browser black box: registration succeeded through the CSRF-enabled frontend; normal-user diagnostics stayed unavailable without breaking the workbench.
