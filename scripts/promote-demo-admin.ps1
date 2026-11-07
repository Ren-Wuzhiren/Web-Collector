<#
.SYNOPSIS
把本地 Compose 数据库中的指定演示账号提升为 ADMIN。

.DESCRIPTION
仅用于本地开发数据库，不开放网页提权入口，也不会输出数据库密码。

.EXAMPLE
.\scripts\promote-demo-admin.ps1
#>
#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_-]{2,31}$')]
    [string]$Username = 'demo'
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $projectRoot 'deploy\local\docker-compose.yml'

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw '找不到 docker 命令。'
}
if (-not (Test-Path $composeFile)) {
    throw "找不到本地 Compose 文件：$composeFile"
}

function Invoke-AdminSql {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $output = & docker compose `
        -f $composeFile `
        exec `
        -T `
        -e "SQL=$Sql" `
        mysql `
        sh `
        -lc 'mysql --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE" --batch --skip-column-names --execute="$SQL"'

    if ($LASTEXITCODE -ne 0) {
        throw "本地 MySQL 执行失败，退出码 $LASTEXITCODE"
    }

    return (($output -join "`n").Trim())
}

$safeUsername = $Username.Replace("'", "''")
$role = Invoke-AdminSql -Sql (
    "SELECT role FROM app_user WHERE username = '$safeUsername' LIMIT 1;"
)

if ([string]::IsNullOrWhiteSpace($role)) {
    throw "本地账号 $Username 不存在；请先运行 seed-demo-account.ps1。"
}
if ($role -eq 'ADMIN') {
    Write-Host "账号 $Username 已经是 ADMIN。"
    exit 0
}
if ($role -ne 'USER') {
    throw "账号 $Username 的角色值异常：$role"
}

[void](Invoke-AdminSql -Sql (
    "UPDATE app_user SET role = 'ADMIN', updated_at = CURRENT_TIMESTAMP(3) " +
    "WHERE username = '$safeUsername' AND role = 'USER';"
))

$updatedRole = Invoke-AdminSql -Sql (
    "SELECT role FROM app_user WHERE username = '$safeUsername' LIMIT 1;"
)
if ($updatedRole -ne 'ADMIN') {
    throw "账号 $Username 提升失败，当前角色为 $updatedRole"
}

Write-Host "账号 $Username 已提升为 ADMIN；现有登录会话无需重新登录。"
