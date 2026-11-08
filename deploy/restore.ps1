[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectName,

    [Parameter(Mandatory = $true)]
    [string]$BackupDirectory,

    [string]$ComposeFile = (Join-Path $PSScriptRoot "docker-compose.yml"),

    [string]$EnvFile = (Join-Path $PSScriptRoot ".env")
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

function Resolve-ExistingDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
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

function Assert-BackupManifest {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BackupRoot,
        [Parameter(Mandatory = $true)]
        [object]$Manifest
    )

    if ($Manifest.schemaVersion -ne 1) {
        throw "Unsupported backup manifest schema: $($Manifest.schemaVersion)"
    }
    if ($Manifest.projectName -ne $ProjectName) {
        throw "Backup belongs to project '$($Manifest.projectName)', not '$ProjectName'."
    }

    $expectedPaths = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    $backupPrefix = $BackupRoot.TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    ) + [System.IO.Path]::DirectorySeparatorChar

    foreach ($entry in @($Manifest.files)) {
        if ([string]::IsNullOrWhiteSpace([string]$entry.path)) {
            throw "Backup manifest contains an empty file path."
        }
        $relativePath = ([string]$entry.path).Replace(
            "/",
            [System.IO.Path]::DirectorySeparatorChar
        )
        if ([System.IO.Path]::IsPathRooted($relativePath) -or
            $relativePath.Split([System.IO.Path]::DirectorySeparatorChar) -contains "..") {
            throw "Backup manifest contains an unsafe file path: $($entry.path)"
        }

        $fullPath = [System.IO.Path]::GetFullPath(
            (Join-Path $BackupRoot $relativePath)
        )
        if (-not $fullPath.StartsWith(
                $backupPrefix,
                [System.StringComparison]::OrdinalIgnoreCase
            )) {
            throw "Backup manifest path escapes the backup directory: $($entry.path)"
        }
        if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
            throw "Backup file is missing: $($entry.path)"
        }

        $actualSize = (Get-Item -LiteralPath $fullPath).Length
        if ($actualSize -ne [long]$entry.sizeBytes) {
            throw "Backup file size mismatch: $($entry.path)"
        }
        $actualHash = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne ([string]$entry.sha256).ToLowerInvariant()) {
            throw "Backup file SHA-256 mismatch: $($entry.path)"
        }
        $expectedPaths.Add($relativePath) | Out-Null
    }

    $actualPaths = @(
        Get-ChildItem -LiteralPath $BackupRoot -Recurse -File |
            Where-Object { $_.Name -ne "manifest.json" } |
            ForEach-Object {
                [System.IO.Path]::GetRelativePath($BackupRoot, $_.FullName)
            }
    )
    foreach ($actualPath in $actualPaths) {
        if (-not $expectedPaths.Contains($actualPath)) {
            throw "Backup contains a file missing from manifest.json: $actualPath"
        }
    }
}

$resolvedComposeFile = Resolve-ExistingFile -Path $ComposeFile -Label "Compose file"
$resolvedEnvFile = Resolve-ExistingFile -Path $EnvFile -Label "Environment file"
$resolvedBackupDirectory = Resolve-ExistingDirectory `
    -Path $BackupDirectory -Label "Backup directory"

$sqlPath = Join-Path $resolvedBackupDirectory "mysql.sql"
$assetsPath = Join-Path $resolvedBackupDirectory "assets"
$captureWorkPath = Join-Path $resolvedBackupDirectory "capture-work"
$manifestPath = Join-Path $resolvedBackupDirectory "manifest.json"

Resolve-ExistingFile -Path $sqlPath -Label "MySQL dump" | Out-Null
Resolve-ExistingDirectory -Path $assetsPath -Label "Assets directory" | Out-Null
Resolve-ExistingDirectory -Path $captureWorkPath -Label "Capture-work directory" | Out-Null
Resolve-ExistingFile -Path $manifestPath -Label "Backup manifest" | Out-Null

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
Assert-BackupManifest -BackupRoot $resolvedBackupDirectory -Manifest $manifest

$runningServices = @(
    Invoke-Compose -ComposeArguments @("ps", "--status", "running", "--services")
)
foreach ($requiredService in @("mysql", "backend")) {
    if ($runningServices -notcontains $requiredService) {
        throw "Required Compose service is not running: $requiredService"
    }
}

$token = (Get-Date -Format "yyyyMMdd-HHmmss-fff") + "-" +
    [Guid]::NewGuid().ToString("N").Substring(0, 8)
$stagingRoot = "/data/.m8-restore-$token"
$containerSql = "/tmp/webcollector-restore-$token.sql"
$backendStopped = $false
$sqlCopied = $false

try {
    $createStagingScript = 'mkdir -p "' + $stagingRoot + '"'
    Invoke-Compose -ComposeArguments @(
        "exec", "-T", "backend", "sh", "-lc", $createStagingScript
    ) | Out-Null
    Invoke-Compose -ComposeArguments @(
        "cp", $assetsPath, "backend:$stagingRoot/assets"
    ) | Out-Null
    Invoke-Compose -ComposeArguments @(
        "cp", $captureWorkPath, "backend:$stagingRoot/capture-work"
    ) | Out-Null
    Invoke-Compose -ComposeArguments @(
        "cp", $sqlPath, "mysql:$containerSql"
    ) | Out-Null
    $sqlCopied = $true

    Invoke-Compose -ComposeArguments @("stop", "backend") | Out-Null
    $backendStopped = $true

    $importScript = 'mysql --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" ' +
        '"$MYSQL_DATABASE" < "' + $containerSql + '"'
    Invoke-Compose -ComposeArguments @(
        "exec", "-T", "mysql", "sh", "-lc", $importScript
    ) | Out-Null

    $swapScript = "set -eu; " +
        "rm -rf '$stagingRoot/assets.next' '$stagingRoot/capture-work.next'; " +
        "mv '$stagingRoot/assets' '$stagingRoot/assets.next'; " +
        "mv '$stagingRoot/capture-work' '$stagingRoot/capture-work.next'; " +
        "rm -rf /data/assets /data/capture-work; " +
        "mv '$stagingRoot/assets.next' /data/assets; " +
        "mv '$stagingRoot/capture-work.next' /data/capture-work; " +
        "rm -rf '$stagingRoot'"
    Invoke-Compose -ComposeArguments @(
        "run", "-T", "--rm", "--no-deps", "--entrypoint", "sh",
        "backend", "-lc", $swapScript
    ) | Out-Null

    Invoke-Compose -ComposeArguments @("start", "backend") | Out-Null
    $backendStopped = $false
    Write-Host "Restore completed: $resolvedBackupDirectory"
}
catch {
    $message = $_.Exception.Message
    if ($backendStopped) {
        throw "$message`nRestore did not complete. Backend remains stopped to prevent partial writes."
    }
    throw
}
finally {
    if ($sqlCopied) {
        try {
            Invoke-Compose -ComposeArguments @(
                "exec", "-T", "mysql", "rm", "-f", $containerSql
            ) | Out-Null
        }
        catch {
            Write-Warning "Could not remove temporary restore SQL ${containerSql}: $($_.Exception.Message)"
        }
    }
}
