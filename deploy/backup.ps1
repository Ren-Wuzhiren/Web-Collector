param(
    [string]$OutputRoot = ".\backups"
)

$ErrorActionPreference = "Stop"
$composeFile = Join-Path $PSScriptRoot "docker-compose.yml"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$target = Join-Path $OutputRoot $stamp
New-Item -ItemType Directory -Path $target -Force | Out-Null

$database = if ($env:MYSQL_DATABASE) { $env:MYSQL_DATABASE } else { "webcollector" }
$user = if ($env:MYSQL_USER) { $env:MYSQL_USER } else { "webcollector" }

docker compose -f $composeFile exec -T mysql sh -c `
    "mysqldump --single-transaction --routines --triggers -u '$user' -p`"`$MYSQL_PASSWORD`" '$database'" `
    | Set-Content -LiteralPath (Join-Path $target "mysql.sql") -Encoding utf8

docker compose -f $composeFile cp "backend:/data/assets" (Join-Path $target "assets")
docker compose -f $composeFile cp "backend:/data/capture-work" (Join-Path $target "capture-work")

Write-Host "Backup completed: $target"
