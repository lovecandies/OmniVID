# OmniVid API

Spring Boot backend for OmniVid, responsible for upload orchestration, video processing jobs, ASR transcripts, summaries, exports, RAG retrieval and runtime diagnostics.

## Run

```powershell
.\mvnw.cmd spring-boot:run
```

Default URL:

```text
http://localhost:8080
```

The default profile uses an in-memory H2 database for quick local checks. For the normal workbench flow, use the Docker-backed launcher from the repository root:

```powershell
.\scripts\start-api-docker.ps1
```

This starts Docker MySQL, Redis and Qdrant, then runs the API with the `docker` profile.

## Run With MySQL + Redis

```powershell
cd E:\video\infra
docker compose up -d

cd E:\video\apps\api
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=docker"
```

## FFmpeg

For real file upload with audio extraction, point the API to an ffmpeg binary:

```powershell
$env:OMNIVID_FFMPEG_PATH = "E:\video\tools\ffmpeg\runtime\ffmpeg-n7.1-latest-win64-gpl-shared-7.1\bin\ffmpeg.exe"
.\mvnw.cmd spring-boot:run
```

## Smoke Test

```powershell
Invoke-RestMethod http://localhost:8080/api/health

curl.exe -F "file=@E:\video\tools\ffmpeg\omnivid-ffmpeg-smoke.mp4" http://localhost:8080/api/videos/upload/file
```

Expected result: the API creates a video asset, a processing job and audio artifacts under the configured storage directory.

## Main Endpoints

- `GET /api/health`
- `GET /api/runtime/status`
- `POST /api/videos/upload/file`
- `POST /api/videos/upload/chunks/sessions`
- `POST /api/videos/import/url`
- `GET /api/videos`
- `GET /api/videos/{videoId}`
- `GET /api/videos/{videoId}/transcripts`
- `GET /api/videos/{videoId}/summaries`
- `GET /api/jobs/{jobId}`
- `POST /api/videos/{videoId}/agent/ask`
- `POST /api/videos/{videoId}/exports`

## Tests

```powershell
.\mvnw.cmd test
```
