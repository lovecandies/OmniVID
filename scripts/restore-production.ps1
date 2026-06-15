param(
    [Parameter(Mandatory = $true)]
    [string]$BackupDir,
    [switch]$Apply,
    [switch]$RestoreEnv,
    [switch]$RestoreStorageFiles
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$infra = Join-Path $root "infra"
$compose = Join-Path $infra "docker-compose.yml"
$envPath = Join-Path $infra ".env"
$storageRoot = Join-Path $root "apps\api\storage"

$resolvedBackup = (Resolve-Path -LiteralPath $BackupDir).Path
$sqlPath = Join-Path $resolvedBackup "mysql-omnivid.sql"
$envBackup = Join-Path $resolvedBackup "infra.env.backup"
$storageArchive = Join-Path $resolvedBackup "storage-files.zip"

if (!(Test-Path $sqlPath)) {
    throw "Backup is missing mysql-omnivid.sql: $resolvedBackup"
}

Write-Host "Restore plan:"
Write-Host "  MySQL dump: $sqlPath"
Write-Host "  Restore env: $RestoreEnv"
Write-Host "  Restore storage files: $RestoreStorageFiles"

if (!$Apply) {
    Write-Host "Dry-run only. Re-run with -Apply to restore MySQL."
    return
}

docker compose -f $compose up -d mysql

Write-Host "Waiting for MySQL..."
$deadline = (Get-Date).AddSeconds(120)
do {
    Start-Sleep -Seconds 3
    $services = docker compose -f $compose ps --format json | ConvertFrom-Json
    $mysql = $services | Where-Object { $_.Service -eq "mysql" }
    if ($mysql.Health -eq "healthy") {
        break
    }
} while ((Get-Date) -lt $deadline)

if ($mysql.Health -ne "healthy") {
    docker compose -f $compose ps
    throw "MySQL did not become healthy in time."
}

Write-Host "Restoring MySQL dump..."
docker cp $sqlPath "omnivid-mysql:/tmp/omnivid-restore.sql"
if ($LASTEXITCODE -ne 0) {
    throw "docker cp failed while copying MySQL dump."
}
docker exec omnivid-mysql sh -c 'mysql -u omnivid -p"$MYSQL_PASSWORD" omnivid < /tmp/omnivid-restore.sql && rm -f /tmp/omnivid-restore.sql'
if ($LASTEXITCODE -ne 0) {
    throw "mysql restore command failed."
}

if ($RestoreEnv) {
    if (!(Test-Path $envBackup)) {
        throw "Backup does not contain infra.env.backup"
    }
    Copy-Item -LiteralPath $envBackup -Destination $envPath -Force
    Write-Host "Restored infra/.env"
}

if ($RestoreStorageFiles) {
    if (!(Test-Path $storageArchive)) {
        throw "Backup does not contain storage-files.zip"
    }
    New-Item -ItemType Directory -Force -Path $storageRoot | Out-Null
    Expand-Archive -LiteralPath $storageArchive -DestinationPath (Split-Path -Parent $storageRoot) -Force
    Write-Host "Restored storage archive"
}

Write-Host "Restore complete."
