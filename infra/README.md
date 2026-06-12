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

- API: `http://localhost:8080/api/runtime/status`
- Web: `http://localhost:5174`
- Runtime status reports MySQL, Redis, Qdrant and RocketMQ connected.

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
