$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$compose = Join-Path $root "infra\docker-compose.yml"

function Stop-DevProcessOnPort($port) {
    $listeners = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue |
        Where-Object { $_.State -eq "Listen" }
    foreach ($listener in $listeners) {
        $process = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
        if ($process -and $process.ProcessName -notmatch "docker|com.docker|Docker Desktop") {
            Write-Host "Stopping local dev process on port ${port}: $($process.ProcessName)#$($process.Id)"
            Stop-Process -Id $process.Id -Force
        }
    }
}

Stop-DevProcessOnPort 8080
Stop-DevProcessOnPort 5174

Write-Host "Building and starting full OmniVid stack..."
docker compose -f $compose --profile app up -d --build

Write-Host "Waiting for API health..."
$deadline = (Get-Date).AddSeconds(180)
do {
    Start-Sleep -Seconds 5
    try {
        $health = Invoke-RestMethod "http://localhost:8080/api/health" -TimeoutSec 5
        if ($health.status -eq "UP") {
            Write-Host "API health ready: $($health.status)"
            break
        }
    } catch {
        # Containers are still starting.
    }
} while ((Get-Date) -lt $deadline)

if ((Get-Date) -ge $deadline) {
    docker compose -f $compose --profile app ps
    docker logs omnivid-api --tail 120
    throw "Full Docker stack did not become ready in time."
}

Write-Host "Waiting for web..."
$deadline = (Get-Date).AddSeconds(60)
do {
    Start-Sleep -Seconds 2
    try {
        $status = (Invoke-WebRequest "http://localhost:5174" -UseBasicParsing -TimeoutSec 5).StatusCode
        if ($status -eq 200) {
            Write-Host "Web ready: http://localhost:5174"
            exit 0
        }
    } catch {
        # Web is still starting.
    }
} while ((Get-Date) -lt $deadline)

docker compose -f $compose --profile app ps
throw "Web did not become ready in time."
