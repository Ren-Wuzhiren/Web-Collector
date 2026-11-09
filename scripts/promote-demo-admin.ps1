<#
.SYNOPSIS
把本地开发 Compose 数据库中的指定账号提升为 ADMIN。

.DESCRIPTION
兼容原有本地开发命令，内部调用通用 promote-admin.ps1。

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
$envFile = Join-Path $projectRoot 'deploy\local\.env'
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    $envFile = Join-Path $projectRoot 'deploy\local\.env.example'
}
$promoteScript = Join-Path $PSScriptRoot 'promote-admin.ps1'

if (-not (Test-Path $composeFile)) {
    throw "找不到本地 Compose 文件：$composeFile"
}
if (-not (Test-Path $promoteScript)) {
    throw "找不到通用提升脚本：$promoteScript"
}

& $promoteScript `
    -Username $Username `
    -ComposeFile $composeFile `
    -EnvFile $envFile `
    -ProjectName 'webcollector-local'
