# API Notes

This document lists the main API surfaces. Exact request and response shapes are defined by the Spring controllers under `apps/api/src/main/java/com/omnivid/api`.

## Health And Runtime

- `GET /api/health`
- `GET /api/runtime/status`

## Auth And Account

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/logout`
- `GET /api/auth/me`
- `GET /api/account/session`

## Video And Upload

- `POST /api/videos/upload/chunks/sessions`
- `PUT /api/videos/upload/chunks/sessions/{sessionId}/parts/{partNumber}`
- `POST /api/videos/upload/chunks/sessions/{sessionId}/complete`
- `POST /api/videos/upload/file`
- `POST /api/videos/import/url`
- `GET /api/videos`
- `GET /api/videos/{videoId}`
- `GET /api/videos/{videoId}/media`

## Processing

- `GET /api/jobs/{jobId}`
- `GET /api/jobs/{jobId}/events`
- `POST /api/jobs/{jobId}/retry`

## Transcript And Summary

- `GET /api/videos/{videoId}/transcripts`
- `GET /api/videos/{videoId}/summaries`
- `POST /api/videos/{videoId}/transcripts/{segmentId}`
- `GET /api/videos/{videoId}/transcripts/versions`

## Agent And Knowledge Base

- `POST /api/videos/{videoId}/agent/ask`
- `POST /api/knowledge-bases`
- `GET /api/knowledge-bases`
- `POST /api/knowledge-bases/{knowledgeBaseId}/agent/ask`
- `POST /api/knowledge-bases/{knowledgeBaseId}/compare`

## Export

- `POST /api/videos/{videoId}/exports`

Supported formats:

- `MARKDOWN`
- `DOCX`
- `PPTX`

