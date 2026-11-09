<#
.SYNOPSIS
在指定 Docker Compose 项目中把现有账号提升为 ADMIN。

.DESCRIPTION
只执行 USER -> ADMIN 的受控数据库更新，不开放网页提权入口，也不读取或输出账号密码。

.EXAMPLE
.\scripts\promote-admin.ps1 `
  -Username admin `
  -ComposeFile .\deploy\docker-compose.yml `
  -EnvFile .\deploy\.env `
  -ProjectName webcollector
#>
#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_-]{2,31}$')]
    [string]$Username,

    [Parameter(Mandatory = $true)]
    [string]$ComposeFile,

    [Parameter(Mandatory = $true)]
    [string]$EnvFile,

    [ValidatePattern('^[a-z0-9][a-z0-9_-]*$')]
    [Parameter(Mandatory = $true)]
    [string]$ProjectName
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($Username)) {
    throw '必须指定 -Username。'
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw '找不到 docker 命令。'
}

foreach ($entry in @(
    @{ Path = $ComposeFile; Label = 'Compose 文件' },
    @{ Path = $EnvFile; Label = 'env 文件' }
)) {
    if (-not (Test-Path -LiteralPath $entry.Path -PathType Leaf)) {
        throw "找不到$($entry.Label)：$($entry.Path)"
    }
}

$resolvedComposeFile = (Resolve-Path -LiteralPath $ComposeFile).ProviderPath
$resolvedEnvFile = (Resolve-Path -LiteralPath $EnvFile).ProviderPath

function Invoke-AdminSql {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $output = & docker compose `
        -p $ProjectName `
        --env-file $resolvedEnvFile `
        -f $resolvedComposeFile `
        exec `
        -T `
        -e "SQL=$Sql" `
        mysql `
        sh `
        -lc 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --user="$MYSQL_USER" "$MYSQL_DATABASE" --batch --skip-column-names --execute="$SQL"' 2>&1

    if ($LASTEXITCODE -ne 0) {
        $details = (($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine).Trim()
        throw "所选 Compose 项目无法执行 MySQL，退出码 $LASTEXITCODE。`n$details"
    }

    return (($output | ForEach-Object { $_.ToString() }) -join "`n").Trim()
}

$safeUsername = $Username.Replace("'", "''")
$role = Invoke-AdminSql -Sql (
    "SELECT role FROM app_user WHERE username = '$safeUsername' LIMIT 1;"
)

if ([string]::IsNullOrWhiteSpace($role)) {
    throw "账号 $Username 在 Compose 项目 $ProjectName 中不存在。"
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
    throw "账号 $Username 提升失败，当前角色为 $updatedRole。"
}

Write-Host "账号 $Username 已在 Compose 项目 $ProjectName 中提升为 ADMIN；现有登录会话无需重新登录。"
