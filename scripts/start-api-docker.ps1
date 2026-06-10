$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$infra = Join-Path $root "infra"
$api = Join-Path $root "apps\api"
$artifacts = Join-Path $root "artifacts"
$logPath = Join-Path $artifacts "api-docker-dev.log"

New-Item -ItemType Directory -Force -Path $artifacts | Out-Null

Write-Host "Starting Docker services..."
docker compose -f (Join-Path $infra "docker-compose.yml") up -d

Write-Host "Waiting for MySQL and Redis health checks..."
$deadline = (Get-Date).AddSeconds(120)
do {
    Start-Sleep -Seconds 3
    $services = docker compose -f (Join-Path $infra "docker-compose.yml") ps --format json | ConvertFrom-Json
    $mysql = $services | Where-Object { $_.Service -eq "mysql" }
    $redis = $services | Where-Object { $_.Service -eq "redis" }
    if ($mysql.Health -eq "healthy" -and $redis.Health -eq "healthy") {
        break
    }
} while ((Get-Date) -lt $deadline)

if ($mysql.Health -ne "healthy" -or $redis.Health -ne "healthy") {
    docker compose -f (Join-Path $infra "docker-compose.yml") ps
    throw "Docker MySQL/Redis did not become healthy in time."
}

$existing = Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue |
    Where-Object { $_.State -eq "Listen" } |
    Select-Object -First 1
if ($existing) {
    Write-Host "Stopping existing API process on 8080: $($existing.OwningProcess)"
    Stop-Process -Id $existing.OwningProcess -Force
    Start-Sleep -Seconds 2
}

Write-Host "Starting OmniVid API with docker profile..."
$command = @"
cd "$api"
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.jvmArguments=-Dspring.profiles.active=docker' *> "$logPath"
"@
Start-Process -FilePath powershell -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $command -WindowStyle Hidden

$deadline = (Get-Date).AddSeconds(90)
do {
    Start-Sleep -Seconds 3
    try {
        $runtime = Invoke-RestMethod "http://localhost:8080/api/runtime/status" -TimeoutSec 3
        if ($runtime.profile -eq "docker" -and $runtime.database.product -like "*MySQL*" -and $runtime.redis.connected) {
            Write-Host "API connected: profile=$($runtime.profile), db=$($runtime.database.product), redis=$($runtime.redis.connected), vector=$($runtime.llm.vectorStoreMode)"
            Write-Host "Log: $logPath"
            exit 0
        }
    } catch {
        # API is still starting.
    }
} while ((Get-Date) -lt $deadline)

Get-Content $logPath -Tail 120
throw "API did not start in Docker MySQL/Redis mode."
