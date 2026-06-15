# syntax=docker/dockerfile:1.7

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace/apps/api
COPY apps/api/pom.xml .
COPY apps/api/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl ffmpeg python3 python3-pip \
    && python3 -m pip install --no-cache-dir yt-dlp \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /data/omnivid/storage
COPY --from=build /workspace/apps/api/target/*.jar /app/omnivid-api.jar

ENV SPRING_PROFILES_ACTIVE=docker \
    OMNIVID_STORAGE_ROOT=/data/omnivid/storage \
    OMNIVID_FFMPEG_PATH=/usr/bin/ffmpeg \
    OMNIVID_YTDLP_PATH=/usr/local/bin/yt-dlp

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=10 \
    CMD curl -fsS http://localhost:8080/api/health >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/omnivid-api.jar"]
