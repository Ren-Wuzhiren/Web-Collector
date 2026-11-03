param(
    [Parameter(Mandatory = $true)]
    [string]$BackupDirectory
)

$ErrorActionPreference = "Stop"
$composeFile = Join-Path $PSScriptRoot "docker-compose.yml"
$sql = Join-Path $BackupDirectory "mysql.sql"
if (-not (Test-Path -LiteralPath $sql)) {
    throw "Missing backup file: $sql"
}

$database = if ($env:MYSQL_DATABASE) { $env:MYSQL_DATABASE } else { "webcollector" }
$user = if ($env:MYSQL_USER) { $env:MYSQL_USER } else { "webcollector" }

Get-Content -LiteralPath $sql -Raw | docker compose -f $composeFile exec -T mysql `
    sh -c "mysql -u '$user' -p`"`$MYSQL_PASSWORD`" '$database'"

$assets = Join-Path $BackupDirectory "assets"
if (Test-Path -LiteralPath $assets) {
    docker compose -f $composeFile cp $assets "backend:/data/assets"
}

Write-Host "Restore completed. Restart backend before accepting new jobs."
