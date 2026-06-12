$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

Push-Location (Join-Path $root "apps\api")
try {
    .\mvnw.cmd test
} finally {
    Pop-Location
}

Push-Location (Join-Path $root "apps\web")
try {
    npm.cmd ci
    npm.cmd run build
} finally {
    Pop-Location
}

docker compose -f (Join-Path $root "infra\docker-compose.yml") --profile app config --quiet

Write-Host "Local CI checks passed."
