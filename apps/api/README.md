# OmniVid API

Spring Boot backend for the OmniVid career-oriented Java + AI Agent project.

## Run

```powershell
.\mvnw.cmd spring-boot:run
```

Default URL:

```text
http://localhost:8080
```

The dev profile uses an in-memory H2 database with MySQL-compatible mode. Redis and MySQL dependencies are present for the later production profile, but the MVP can run without local Docker.

For the normal OmniVid workbench flow, prefer the Docker-backed launcher from the repository root:

```powershell
.\scripts\start-api-docker.ps1
```

This starts Docker MySQL, Redis and Qdrant, then runs the API with the `docker` profile so the video library uses persistent MySQL data instead of the H2 in-memory database.

For real file upload with audio extraction, point the API to the project-local FFmpeg binary:

```powershell
$env:OMNIVID_FFMPEG_PATH = "E:\video\tools\ffmpeg\runtime\ffmpeg-n7.1-latest-win64-gpl-shared-7.1\bin\ffmpeg.exe"
.\mvnw.cmd spring-boot:run
```

## Run With MySQL + Redis

Start infrastructure:

```powershell
cd E:\video\infra
docker compose up -d
```

Run the API with the Docker profile:

```powershell
cd E:\video\apps\api
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=docker"
```

The `docker` profile switches:

- Datasource from H2 to MySQL `omnivid`.
- Dedupe lock from local memory to Redis `SET key value NX PX ttl`.
- Schema initialization remains enabled so the MVP tables are created automatically.

## Smoke Test

```powershell
Invoke-RestMethod http://localhost:8080/api/health

$body = '{"originalName":"omnivid-demo.mp4","md5":"8f2a000000000000000000000000c91e","durationMs":512000}'
Invoke-RestMethod -Uri 'http://localhost:8080/api/videos/upload/complete' -Method Post -ContentType 'application/json' -Body $body
Invoke-RestMethod -Uri 'http://localhost:8080/api/videos/1'
Invoke-RestMethod -Uri 'http://localhost:8080/api/videos/1/agent/ask' -Method Post -ContentType 'application/json' -Body '{"question":"Redis 和 MySQL 的边界怎么讲？"}'
```

Real upload smoke test:

```powershell
curl.exe -F "file=@E:\video\tools\ffmpeg\omnivid-ffmpeg-smoke.mp4" http://localhost:8080/api/videos/upload/file
```

Expected: `currentStep=AUDIO_EXTRACTED_AND_LOCAL_DAG_DONE`, and `storage/videos/{md5}/audio.wav` exists.

## MVP Endpoints

- `GET /api/health`
- `POST /api/videos/upload/complete`
- `POST /api/videos/upload/file`
- `GET /api/videos`
- `GET /api/videos/{videoId}`
- `GET /api/videos/{videoId}/transcripts`
- `GET /api/videos/{videoId}/summaries`
- `GET /api/jobs/{jobId}`
- `POST /api/videos/{videoId}/agent/ask`

## Interview Hooks Implemented

- `video_asset.uk_video_md5`: MySQL unique-index dedupe fallback.
- `processing_job.version`: optimistic-lock style state transition boundary.
- `transcript_segment(video_id, start_ms)`: timeline query index.
- H2 MySQL mode: local black-box demo without Docker.
- `DedupeLockService`: local implementation by default, Redis implementation in `docker` profile.
- FFmpeg subprocess extraction: uploaded video creates `audio.wav`, with process timeout and log capture.
- Agent response citations: answer includes `videoId`, `startMs`, `endMs`, and display citation.
