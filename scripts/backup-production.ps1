param(
    [string]$OutputRoot,
    [switch]$IncludeStorageFiles,
    [switch]$HashStorageFiles
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $root "backups"
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupDir = Join-Path $OutputRoot "omnivid-$timestamp"
$storageRoot = Join-Path $root "apps\api\storage"
$infraEnv = Join-Path $root "infra\.env"

New-Item -ItemType Directory -Force -Path $backupDir | Out-Null

Write-Host "Writing MySQL backup..."
$sqlPath = Join-Path $backupDir "mysql-omnivid.sql"
docker exec omnivid-mysql sh -c 'mysqldump -u omnivid -p"$MYSQL_PASSWORD" --single-transaction --routines --triggers --no-tablespaces omnivid' > $sqlPath
if ($LASTEXITCODE -ne 0) {
    throw "MySQL backup failed. Make sure Docker is running and omnivid-mysql is healthy."
}

Write-Host "Backing up production environment configuration..."
if (Test-Path $infraEnv) {
    Copy-Item -LiteralPath $infraEnv -Destination (Join-Path $backupDir "infra.env.backup") -Force
} else {
    "infra/.env was not present on this machine." | Set-Content -Path (Join-Path $backupDir "infra.env.missing.txt") -Encoding UTF8
}

Write-Host "Writing video storage metadata manifest..."
$manifestPath = Join-Path $backupDir "storage-manifest.json"
$files = @()
if (Test-Path $storageRoot) {
    $files = Get-ChildItem -LiteralPath $storageRoot -File -Recurse | ForEach-Object {
        $fullPath = $_.FullName
        $relative = if ($fullPath.StartsWith($storageRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            $fullPath.Substring($storageRoot.Length).TrimStart("\", "/")
        } else {
            $_.Name
        }
        $item = [ordered]@{
            path = $relative
            sizeBytes = $_.Length
            lastWriteUtc = $_.LastWriteTimeUtc.ToString("o")
        }
        if ($HashStorageFiles) {
            $item.sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash
        }
        [pscustomobject]$item
    }
}
$files | ConvertTo-Json -Depth 4 | Set-Content -Path $manifestPath -Encoding UTF8

if ($IncludeStorageFiles -and (Test-Path $storageRoot)) {
    Write-Host "Creating storage archive. This can be large for GB-level videos..."
    Compress-Archive -LiteralPath $storageRoot -DestinationPath (Join-Path $backupDir "storage-files.zip") -Force
}

$metadata = [ordered]@{
    createdAt = (Get-Date).ToUniversalTime().ToString("o")
    mysqlDump = "mysql-omnivid.sql"
    envBackup = if (Test-Path $infraEnv) { "infra.env.backup" } else { "infra.env.missing.txt" }
    storageManifest = "storage-manifest.json"
    storageArchive = if ($IncludeStorageFiles -and (Test-Path $storageRoot)) { "storage-files.zip" } else { $null }
}
$metadata | ConvertTo-Json -Depth 4 | Set-Content -Path (Join-Path $backupDir "backup-metadata.json") -Encoding UTF8

Write-Output "Backup complete: $backupDir"
