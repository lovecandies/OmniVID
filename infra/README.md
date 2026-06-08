# OmniVid Local Infrastructure

This folder contains the optional MySQL + Redis runtime for the backend.

## Start

```powershell
cd E:\video\infra
docker compose up -d
```

## Run API With Docker Profile

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

The default API profile still uses in-memory H2, so the project can run even before Docker Desktop is installed.
