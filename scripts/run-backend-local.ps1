<#
.SYNOPSIS
启动本地 MySQL、Redis 和 Spring Boot，并同步 Redis 密码。

.DESCRIPTION
使用 Docker Compose 中 Redis 容器的实际密码，仅覆盖当前 PowerShell
进程及其 Maven 子进程的环境变量。不会修改用户级环境变量或其他项目。

.EXAMPLE
.\scripts\run-backend-local.ps1

.EXAMPLE
.\scripts\run-backend-local.ps1 -CheckOnly
#>
#Requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$CheckOnly,
    [ValidateRange(1, 65535)]
    [int]$BackendPort = 8080
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $projectRoot 'deploy\local\docker-compose.yml'
$mysqlContainer = 'webcollector-mysql'
$redisContainer = 'webcollector-redis'

function Assert-Command {
    param([Parameter(Mandatory = $true)][string]$Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "找不到 $Name 命令。"
    }
}

function Get-ContainerState {
    param([Parameter(Mandatory = $true)][string]$Name)

    $value = & docker inspect $Name --format (
        '{{.State.Status}}|' +
        '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}|' +
        '{{.State.ExitCode}}'
    )

    if ($LASTEXITCODE -ne 0) {
        throw "无法读取容器 $Name 的状态。"
    }

    $parts = $value.Trim().Split('|')
    return [pscustomobject]@{
        Status = $parts[0]
        Health = $parts[1]
        ExitCode = $parts[2]
    }
}

function Wait-ForHealthyContainer {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [int]$TimeoutSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $state = Get-ContainerState -Name $Name
        if ($state.Status -eq 'running' -and $state.Health -eq 'healthy') {
            return $state
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    throw (
        "容器 $Name 在 $TimeoutSeconds 秒内未健康：" +
        "status=$($state.Status), health=$($state.Health), exitCode=$($state.ExitCode)。" +
        "请运行 docker logs --tail 100 $Name 查看日志。"
    )
}

function Get-RedisContainerPassword {
    $entries = & docker inspect $redisContainer --format '{{json .Config.Env}}'
    if ($LASTEXITCODE -ne 0) {
        throw "无法读取容器 $redisContainer 的环境变量。"
    }

    $passwordEntry = ($entries | ConvertFrom-Json) |
        Where-Object { $_ -like 'REDIS_PASSWORD=*' } |
        Select-Object -First 1
    if (-not $passwordEntry) {
        throw "容器 $redisContainer 没有 REDIS_PASSWORD 环境变量。"
    }

    $password = $passwordEntry.Substring('REDIS_PASSWORD='.Length)
    if ([string]::IsNullOrWhiteSpace($password)) {
        throw "容器 $redisContainer 的 REDIS_PASSWORD 为空。"
    }

    return $password
}

Assert-Command docker
Assert-Command mvn

if (-not (Test-Path $composeFile)) {
    throw "找不到本地 Compose 文件：$composeFile"
}

& docker compose -f $composeFile up -d mysql redis
if ($LASTEXITCODE -ne 0) {
    throw "本地 MySQL/Redis 启动失败，请检查 Docker Compose 输出。"
}

[void](Wait-ForHealthyContainer -Name $mysqlContainer)
[void](Wait-ForHealthyContainer -Name $redisContainer)

$redisPassword = Get-RedisContainerPassword
$env:REDIS_PASSWORD = $redisPassword
$env:SPRING_DATA_REDIS_PASSWORD = $redisPassword

$hadRedisCliAuth = Test-Path Env:REDISCLI_AUTH
$previousRedisCliAuth = $env:REDISCLI_AUTH
$env:REDISCLI_AUTH = $redisPassword
try {
    $ping = & docker exec -e REDISCLI_AUTH $redisContainer redis-cli ping
    if ($LASTEXITCODE -ne 0 -or ($ping -join '') -notmatch '^PONG$') {
        throw 'Redis 密码同步验证失败：容器没有返回 PONG。'
    }
}
finally {
    if ($hadRedisCliAuth) {
        $env:REDISCLI_AUTH = $previousRedisCliAuth
    }
    else {
        Remove-Item Env:REDISCLI_AUTH -ErrorAction SilentlyContinue
    }
}

$listener = Get-NetTCPConnection -State Listen -LocalPort $BackendPort -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($listener) {
    throw "端口 $BackendPort 已被 PID $($listener.OwningProcess) 占用，请先停止旧后端。"
}

Write-Host 'MySQL: healthy'
Write-Host 'Redis: healthy'
Write-Host 'Redis credential: synchronized from webcollector-redis'
Write-Host "Backend port: $BackendPort (available)"

if ($CheckOnly) {
    Write-Host 'Check-only completed'
    exit 0
}

Write-Host 'Starting Spring Boot...'
$env:SERVER_PORT = [string]$BackendPort
Push-Location $projectRoot
try {
    & mvn -pl backend spring-boot:run
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
