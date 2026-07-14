# Deployment

OmniVid can run as a local development stack or a Docker Compose application.

## Local Full Stack

```powershell
cd E:\video
.\scripts\start-full-docker.ps1
```

Expected services:

- Web: `http://127.0.0.1:5174`
- API: `http://127.0.0.1:8080`
- Health: `http://127.0.0.1:8080/api/health`

## Infrastructure Only

```powershell
cd E:\video\infra
docker compose up -d
```

Then run API and Web locally:

```powershell
cd E:\video\apps\api
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=docker"

cd E:\video\apps\web
npm run dev -- --host 127.0.0.1
```

## Production-Oriented Profile

The production profile requires strong secrets and HTTPS settings. Create `infra/.env` from your deployment environment and set:

```dotenv
SPRING_PROFILES_ACTIVE=docker,production
SPRING_DATASOURCE_PASSWORD=<strong-random-password>
OMNIVID_PROVIDER_KEY_SECRET=<at-least-32-random-characters>
OMNIVID_ADMIN_EMAILS=admin@example.com
OMNIVID_DOMAIN=video.example.com
OMNIVID_PUBLIC_BASE_URL=https://video.example.com
OMNIVID_SESSION_COOKIE_SECURE=true
```

Run preflight before exposing the service:

```powershell
cd E:\video
.\scripts\public-preflight.ps1
```

Expected output:

```text
Public production preflight passed.
```

## CI Checks

```powershell
cd E:\video\apps\api
.\mvnw.cmd test

cd E:\video\apps\web
npm run build

cd E:\video
docker compose -f infra\docker-compose.yml --profile app config --quiet
```

