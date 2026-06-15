$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$infra = Join-Path $root "infra"
$envPath = Join-Path $infra ".env"
$compose = Join-Path $infra "docker-compose.yml"

function Read-DotEnv($path) {
    $values = @{}
    if (!(Test-Path $path)) {
        return $values
    }
    Get-Content $path | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq "" -or $line.StartsWith("#") -or !$line.Contains("=")) {
            return
        }
        $parts = $line.Split("=", 2)
        $values[$parts[0].Trim()] = $parts[1].Trim()
    }
    return $values
}

function Require-Value($values, $key) {
    if (!$values.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($values[$key])) {
        throw "Missing required production value: $key"
    }
    return $values[$key]
}

function Reject-Placeholder($key, $value) {
    $lower = $value.ToLowerInvariant()
    if ($lower.Contains("change-me") -or $lower.Contains("example.com") -or $lower.Contains("omnivid_pass") -or $lower.Contains("dev-provider-secret")) {
        throw "Production placeholder is still present: $key"
    }
}

if (!(Test-Path $envPath)) {
    throw "Missing infra\.env. Copy infra\.env.example to infra\.env and replace every placeholder first."
}

$values = Read-DotEnv $envPath
$profile = Require-Value $values "SPRING_PROFILES_ACTIVE"
if (!$profile.Contains("docker") -or !$profile.Contains("production")) {
    throw "SPRING_PROFILES_ACTIVE must include docker,production"
}

foreach ($key in @(
    "SPRING_DATASOURCE_PASSWORD",
    "OMNIVID_PROVIDER_KEY_SECRET",
    "OMNIVID_ADMIN_EMAILS",
    "OMNIVID_DOMAIN",
    "OMNIVID_PUBLIC_BASE_URL"
)) {
    Reject-Placeholder $key (Require-Value $values $key)
}

$secret = Require-Value $values "OMNIVID_PROVIDER_KEY_SECRET"
if ($secret.Length -lt 32) {
    throw "OMNIVID_PROVIDER_KEY_SECRET must be at least 32 characters."
}

$dbPassword = Require-Value $values "SPRING_DATASOURCE_PASSWORD"
if ($dbPassword.Length -lt 16) {
    throw "SPRING_DATASOURCE_PASSWORD must be at least 16 characters."
}

$publicBaseUrl = Require-Value $values "OMNIVID_PUBLIC_BASE_URL"
if (!$publicBaseUrl.StartsWith("https://")) {
    throw "OMNIVID_PUBLIC_BASE_URL must start with https://"
}

if ((Require-Value $values "OMNIVID_SESSION_COOKIE_SECURE").ToLowerInvariant() -ne "true") {
    throw "OMNIVID_SESSION_COOKIE_SECURE must be true in public production."
}

if ((Require-Value $values "OMNIVID_COMPATIBILITY_UPLOAD_ENABLED").ToLowerInvariant() -ne "false") {
    throw "OMNIVID_COMPATIBILITY_UPLOAD_ENABLED must be false in public production."
}

if ((Require-Value $values "OMNIVID_URL_IMPORT_ALLOW_LOCAL_COOKIE_SOURCE").ToLowerInvariant() -ne "false") {
    throw "OMNIVID_URL_IMPORT_ALLOW_LOCAL_COOKIE_SOURCE must be false in public production."
}

docker compose -f $compose --profile app --profile public config --quiet
Write-Host "Public production preflight passed."
