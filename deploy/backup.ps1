[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectName,

    [string]$ComposeFile = (Join-Path $PSScriptRoot "docker-compose.yml"),

    [string]$EnvFile = (Join-Path $PSScriptRoot ".env"),

    [string]$OutputRoot = (Join-Path $PSScriptRoot "backups")
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Resolve-ExistingFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label does not exist: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).ProviderPath
}

function Invoke-Compose {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$ComposeArguments
    )

    $output = & docker compose -p $ProjectName --env-file $resolvedEnvFile `
        -f $resolvedComposeFile @ComposeArguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        $details = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
        throw "docker compose failed with exit code $exitCode.`n$details"
    }
    return @($output)
}

function Get-Sha256Hex {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

$resolvedComposeFile = Resolve-ExistingFile -Path $ComposeFile -Label "Compose file"
$resolvedEnvFile = Resolve-ExistingFile -Path $EnvFile -Label "Environment file"
$resolvedOutputRoot = [System.IO.Path]::GetFullPath($OutputRoot)

if (Test-Path -LiteralPath $resolvedOutputRoot) {
    if (-not (Test-Path -LiteralPath $resolvedOutputRoot -PathType Container)) {
        throw "Backup output root is not a directory: $resolvedOutputRoot"
    }
}
else {
    New-Item -ItemType Directory -Path $resolvedOutputRoot -Force | Out-Null
}

$runningServices = @(
    Invoke-Compose -ComposeArguments @("ps", "--status", "running", "--services")
)
foreach ($requiredService in @("mysql", "backend")) {
    if ($runningServices -notcontains $requiredService) {
        throw "Required Compose service is not running: $requiredService"
    }
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss-fff"
$target = Join-Path $resolvedOutputRoot $stamp
if (Test-Path -LiteralPath $target) {
    throw "Backup target already exists; refusing to overwrite: $target"
}
$containerDump = "/tmp/webcollector-backup-$stamp.sql"
$targetCreated = $false
$dumpCreated = $false

try {
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    $targetCreated = $true

    $dumpScript = 'umask 077; mysqldump --single-transaction --routines --triggers ' +
        '--user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE" > "' +
        $containerDump + '"'
    Invoke-Compose -ComposeArguments @(
        "exec", "-T", "mysql", "sh", "-lc", $dumpScript
    ) | Out-Null
    $dumpCreated = $true

    Invoke-Compose -ComposeArguments @(
        "cp", "mysql:$containerDump", (Join-Path $target "mysql.sql")
    ) | Out-Null
    Invoke-Compose -ComposeArguments @(
        "cp", "backend:/data/assets", (Join-Path $target "assets")
    ) | Out-Null
    Invoke-Compose -ComposeArguments @(
        "cp", "backend:/data/capture-work", (Join-Path $target "capture-work")
    ) | Out-Null

    $manifestFiles = @(
        Get-ChildItem -LiteralPath $target -Recurse -File |
            Sort-Object FullName |
            ForEach-Object {
                [ordered]@{
                    path = [System.IO.Path]::GetRelativePath($target, $_.FullName).Replace("\", "/")
                    sizeBytes = $_.Length
                    sha256 = Get-Sha256Hex -Path $_.FullName
                }
            }
    )
    $manifest = [ordered]@{
        schemaVersion = 1
        projectName = $ProjectName
        createdAt = (Get-Date).ToUniversalTime().ToString("o")
        files = $manifestFiles
    }
    $manifestPath = Join-Path $target "manifest.json"
    $utf8WithoutBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText(
        $manifestPath,
        (($manifest | ConvertTo-Json -Depth 8) + [Environment]::NewLine),
        $utf8WithoutBom
    )

    Write-Host "Backup completed: $target"
}
catch {
    if ($targetCreated -and (Test-Path -LiteralPath $target)) {
        Remove-Item -LiteralPath $target -Recurse -Force -ErrorAction SilentlyContinue
    }
    throw
}
finally {
    if ($dumpCreated) {
        try {
            Invoke-Compose -ComposeArguments @(
                "exec", "-T", "mysql", "rm", "-f", $containerDump
            ) | Out-Null
        }
        catch {
            Write-Warning "Could not remove temporary MySQL dump ${containerDump}: $($_.Exception.Message)"
        }
    }
}
