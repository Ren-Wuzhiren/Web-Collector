<#
.SYNOPSIS
在真实 Compose 栈上顺序执行 M9.1 三十个网页验收。

.DESCRIPTION
读取版本化 JSON 数据集，创建一次性账号，保存并轮询每个 URL，
验证至少一个资产可回读，最后输出不含凭据的 JSON 证据。
脚本只允许访问本机 HTTP/HTTPS 地址。

.EXAMPLE
.\scripts\accept-real-urls.ps1 `
  -BaseUrl http://127.0.0.1:18080 `
  -DatasetFile .\scripts\acceptance\m9-real-urls.json `
  -OutputFile .\data\acceptance\m9\results.json
#>
#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$DatasetFile = '.\scripts\acceptance\m9-real-urls.json',
    [string]$OutputFile = '.\data\acceptance\m9\results.json',
    [ValidateRange(1, 3600)]
    [int]$JobTimeoutSeconds = 300,
    [ValidateRange(1, 60)]
    [int]$PollIntervalSeconds = 3
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$script:ActiveSession = $null

function Assert-LoopbackBaseUrl {
    param([string]$Value)

    $parsed = $null
    if (
        -not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$parsed) -or
        $parsed.Scheme -notin @('http', 'https') -or
        -not $parsed.IsLoopback -or
        -not [string]::IsNullOrEmpty($parsed.UserInfo)
    ) {
        throw 'BaseUrl 仅允许本机 localhost、127.0.0.1 或 ::1 的 HTTP/HTTPS 地址。'
    }

    return $Value.TrimEnd('/')
}

function Import-AcceptanceDataset {
    param([string]$Path)

    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $dataset = Get-Content -LiteralPath $resolved -Raw -Encoding utf8 | ConvertFrom-Json
    if ($dataset.schemaVersion -ne 1) {
        throw "数据集 schemaVersion 必须为 1，实际为 $($dataset.schemaVersion)。"
    }

    $cases = @($dataset.cases)
    if ($cases.Count -ne 30) {
        throw "数据集必须恰好包含 30 项，实际为 $($cases.Count)。"
    }

    $ids = @($cases | ForEach-Object id)
    $urls = @($cases | ForEach-Object url)
    if (@($ids | Sort-Object -Unique).Count -ne 30) {
        throw '数据集 ID 必须唯一。'
    }
    if (@($urls | Sort-Object -Unique).Count -ne 30) {
        throw '数据集 URL 必须唯一。'
    }

    foreach ($case in $cases) {
        if (
            [string]::IsNullOrWhiteSpace($case.id) -or
            [string]::IsNullOrWhiteSpace($case.category) -or
            [string]::IsNullOrWhiteSpace($case.url)
        ) {
            throw '每个数据集项都必须包含 id、category 和 url。'
        }
        if ($case.expectedOutcome -notin @('CAPTURE_SUCCEEDED', 'CAPTURE_FAILED')) {
            throw "不支持的 expectedOutcome：$($case.expectedOutcome)"
        }
        $parsed = $null
        if (
            -not [Uri]::TryCreate($case.url, [UriKind]::Absolute, [ref]$parsed) -or
            $parsed.Scheme -notin @('http', 'https')
        ) {
            throw "URL 仅允许 HTTP/HTTPS：$($case.url)"
        }
    }

    return [pscustomobject]@{
        SchemaVersion = $dataset.schemaVersion
        Cases = $cases
    }
}

function Convert-ResponsePayload {
    param($Response)

    if (
        $null -eq $Response -or
        [string]::IsNullOrWhiteSpace([string]$Response.Content)
    ) {
        return $null
    }

    try {
        return [string]$Response.Content | ConvertFrom-Json -Depth 30
    }
    catch {
        return $null
    }
}

function Get-ApiFailureMessage {
    param($Response)

    $payload = Convert-ResponsePayload $Response
    $code = $payload.code
    $message = $payload.message
    if ($code -and $message) {
        return "$code $message"
    }
    if ($message) {
        return [string]$message
    }
    if ($code) {
        return [string]$code
    }
    return "HTTP $($Response.StatusCode)"
}

function Invoke-ApiRequest {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BaseUrl,
        [Parameter(Mandatory = $true)]
        [ValidateSet('GET', 'POST')]
        [string]$Method,
        [Parameter(Mandatory = $true)]
        [string]$Path,
        $Body = $null,
        [string]$Token = $null,
        [int]$MaxRateLimitRetries = 5
    )

    for ($attempt = 0; $attempt -le $MaxRateLimitRetries; $attempt++) {
        $headers = @{ Accept = 'application/json' }
        if ($Token) {
            $headers.Authorization = "Bearer $Token"
        }

        $request = @{
            Uri = "$BaseUrl/api/v1$Path"
            Method = $Method
            Headers = $headers
            SkipHttpErrorCheck = $true
            TimeoutSec = 30
        }
        if ($null -ne $Body) {
            $request.ContentType = 'application/json; charset=utf-8'
            $request.Body = $Body | ConvertTo-Json -Depth 20 -Compress
        }

        try {
            $response = Invoke-WebRequest @request
        }
        catch {
            throw "请求失败：$Method $Path；$($_.Exception.Message)"
        }

        if ($response.StatusCode -ne 429) {
            return $response
        }
        if ($attempt -eq $MaxRateLimitRetries) {
            return $response
        }

        $retryAfter = 1
        $headerValue = $response.Headers['Retry-After'] | Select-Object -First 1
        if ($headerValue) {
            [void][int]::TryParse([string]$headerValue, [ref]$retryAfter)
        }
        Start-Sleep -Seconds ([math]::Max(1, $retryAfter))
    }
}

function Assert-Ready {
    param([string]$BaseUrl)

    $response = Invoke-ApiRequest -BaseUrl $BaseUrl -Method GET -Path '/health/ready'
    $payload = Convert-ResponsePayload $response
    if (
        $response.StatusCode -ne 200 -or
        $payload.data.mysql -ne 'UP' -or
        $payload.data.redis -ne 'UP'
    ) {
        throw "后端依赖未就绪：$(Get-ApiFailureMessage $response)"
    }
}

function New-AcceptanceCredentials {
    $suffix = [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $timestamp = [DateTime]::UtcNow.ToString('yyyyMMddHHmmss')
    $username = "m9accept$timestamp$suffix"
    $passwordBytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(24)
    return [pscustomobject]@{
        Username = $username
        Email = "$username@example.com"
        Password = [Convert]::ToBase64String($passwordBytes)
    }
}

function New-AcceptanceSession {
    param([string]$BaseUrl)

    $credentials = New-AcceptanceCredentials
    $register = Invoke-ApiRequest `
        -BaseUrl $BaseUrl `
        -Method POST `
        -Path '/auth/register' `
        -Body @{
            username = $credentials.Username
            email = $credentials.Email
            password = $credentials.Password
        }
    if ($register.StatusCode -ne 201) {
        throw "一次性账号注册失败：$(Get-ApiFailureMessage $register)"
    }

    $login = Invoke-ApiRequest `
        -BaseUrl $BaseUrl `
        -Method POST `
        -Path '/auth/login' `
        -Body @{
            username = $credentials.Username
            password = $credentials.Password
        }
    $payload = Convert-ResponsePayload $login
    if ($login.StatusCode -ne 200 -or -not $payload.data.token) {
        throw "一次性账号登录失败：$(Get-ApiFailureMessage $login)"
    }

    $collections = Invoke-ApiRequest `
        -BaseUrl $BaseUrl `
        -Method GET `
        -Path '/collections' `
        -Token $payload.data.token
    $collectionPayload = Convert-ResponsePayload $collections
    if ($collections.StatusCode -ne 200) {
        throw "读取集合失败：$(Get-ApiFailureMessage $collections)"
    }
    $inbox = @($collectionPayload.data) |
        Where-Object { $_.isInbox -eq $true } |
        Select-Object -First 1
    if (-not $inbox) {
        throw "一次性账号 $($credentials.Username) 没有默认 Inbox。"
    }

    return [pscustomobject]@{
        Username = $credentials.Username
        Token = $payload.data.token
        InboxId = $inbox.id
    }
}

function Close-AcceptanceSession {
    if (-not $script:ActiveSession) {
        return
    }

    $session = $script:ActiveSession
    $script:ActiveSession = $null
    try {
        $logout = Invoke-ApiRequest `
            -BaseUrl $session.BaseUrl `
            -Method POST `
            -Path '/auth/logout' `
            -Token $session.Token
        if ($logout.StatusCode -ne 200) {
            Write-Warning "一次性账号退出失败：$(Get-ApiFailureMessage $logout)"
        }
    }
    catch {
        Write-Warning "一次性账号退出失败：$($_.Exception.Message)"
    }
}

function Wait-CaptureJob {
    param(
        [string]$BaseUrl,
        [string]$Token,
        [long]$JobId,
        [int]$TimeoutSeconds,
        [int]$PollIntervalSeconds
    )

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $job = $null

    while ([DateTime]::UtcNow -lt $deadline) {
        $response = Invoke-ApiRequest `
            -BaseUrl $BaseUrl `
            -Method GET `
            -Path "/jobs/$JobId" `
            -Token $Token
        if ($response.StatusCode -ne 200) {
            return [pscustomobject]@{
                Outcome = 'CLIENT_ERROR'
                Job = $null
                ElapsedMs = $stopwatch.ElapsedMilliseconds
                ErrorMessage = "读取任务失败：$(Get-ApiFailureMessage $response)"
            }
        }

        $job = (Convert-ResponsePayload $response).data
        if ($job.status -notin @('PENDING', 'RUNNING')) {
            return [pscustomobject]@{
                Outcome = $job.status
                Job = $job
                ElapsedMs = $stopwatch.ElapsedMilliseconds
                ErrorMessage = $job.errorMessage
            }
        }

        $remainingMs = [math]::Floor(($deadline - [DateTime]::UtcNow).TotalMilliseconds)
        if ($remainingMs -le 0) {
            break
        }
        $sleepMs = [math]::Min($PollIntervalSeconds * 1000, [int]$remainingMs)
        Start-Sleep -Milliseconds ([math]::Max(1, $sleepMs))
    }

    return [pscustomobject]@{
        Outcome = 'POLL_TIMEOUT'
        Job = $job
        ElapsedMs = $stopwatch.ElapsedMilliseconds
        ErrorMessage = "任务在 $TimeoutSeconds 秒内未完成"
    }
}

function Test-FirstAssetReadable {
    param(
        [string]$BaseUrl,
        [string]$Token,
        $Job
    )

    $assets = @($Job.assets)
    if ($assets.Count -eq 0) {
        return [pscustomobject]@{
            Readable = $false
            AssetId = $null
            ErrorMessage = '任务成功但没有资产'
        }
    }

    $asset = $assets |
        Where-Object { $_.format -eq 'HTML' } |
        Select-Object -First 1
    if (-not $asset) {
        $asset = $assets[0]
    }

    $response = Invoke-ApiRequest `
        -BaseUrl $BaseUrl `
        -Method GET `
        -Path "/assets/$($asset.id)" `
        -Token $Token
    $contentLength = 0
    if ($null -ne $response.RawContentLength) {
        $contentLength = [long]$response.RawContentLength
    }
    elseif ($null -ne $response.Content) {
        $contentLength = ([string]$response.Content).Length
    }
    $readable = $response.StatusCode -eq 200 -and $contentLength -gt 0
    return [pscustomobject]@{
        Readable = $readable
        AssetId = $asset.id
        ErrorMessage = if ($readable) {
            $null
        } else {
            "资产读取失败：$(Get-ApiFailureMessage $response)"
        }
    }
}

function Invoke-AcceptanceCase {
    param(
        [string]$BaseUrl,
        [string]$Token,
        [long]$InboxId,
        $Case,
        [int]$JobTimeoutSeconds,
        [int]$PollIntervalSeconds
    )

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $createResponse = $null
    $jobResult = $null
    $assetResult = $null

    try {
        $createResponse = Invoke-ApiRequest `
            -BaseUrl $BaseUrl `
            -Method POST `
            -Path '/links' `
            -Token $Token `
            -Body @{
                url = $Case.url
                collectionId = $InboxId
                title = ''
                description = ''
                tagIds = @()
            }
        if ($createResponse.StatusCode -ne 201) {
            throw "创建链接失败：$(Get-ApiFailureMessage $createResponse)"
        }

        $created = (Convert-ResponsePayload $createResponse).data
        $jobResult = Wait-CaptureJob `
            -BaseUrl $BaseUrl `
            -Token $Token `
            -JobId ([long]$created.job.id) `
            -TimeoutSeconds $JobTimeoutSeconds `
            -PollIntervalSeconds $PollIntervalSeconds

        if ($jobResult.Outcome -eq 'SUCCEEDED') {
            $assetResult = Test-FirstAssetReadable `
                -BaseUrl $BaseUrl `
                -Token $Token `
                -Job $jobResult.Job
        }

        $job = $jobResult.Job
        $hasVisibleError = -not [string]::IsNullOrWhiteSpace([string]$job.errorCode) -or
            -not [string]::IsNullOrWhiteSpace([string]$job.errorMessage)
        $matched = if ($Case.expectedOutcome -eq 'CAPTURE_SUCCEEDED') {
            $jobResult.Outcome -eq 'SUCCEEDED' -and $assetResult.Readable
        }
        else {
            $jobResult.Outcome -eq 'FAILED' -and $hasVisibleError
        }

        $actualOutcome = if ($jobResult.Outcome -eq 'SUCCEEDED' -and -not $assetResult.Readable) {
            'ASSET_UNREADABLE'
        }
        elseif ($jobResult.Outcome -eq 'FAILED') {
            'CAPTURE_FAILED'
        }
        else {
            $jobResult.Outcome
        }
        $errorMessage = if ($assetResult -and $assetResult.ErrorMessage) {
            $assetResult.ErrorMessage
        }
        elseif ($jobResult.ErrorMessage) {
            $jobResult.ErrorMessage
        }
        else {
            $null
        }
        $assets = if ($job) { @($job.assets) } else { @() }

        return [pscustomobject]@{
            id = $Case.id
            category = $Case.category
            url = $Case.url
            expectedOutcome = $Case.expectedOutcome
            actualOutcome = $actualOutcome
            matched = [bool]$matched
            createHttpStatus = [int]$createResponse.StatusCode
            linkId = $created.link.id
            jobId = $created.job.id
            jobStatus = if ($job) { $job.status } else { $null }
            errorCode = if ($job) { $job.errorCode } else { $null }
            errorMessage = $errorMessage
            elapsedMs = $stopwatch.ElapsedMilliseconds
            assetCount = $assets.Count
            assetFormats = @($assets | ForEach-Object { $_.format })
            assetReadable = if ($assetResult) { [bool]$assetResult.Readable } else { $null }
            assetId = if ($assetResult) { $assetResult.AssetId } else { $null }
        }
    }
    catch {
        return [pscustomobject]@{
            id = $Case.id
            category = $Case.category
            url = $Case.url
            expectedOutcome = $Case.expectedOutcome
            actualOutcome = 'CLIENT_ERROR'
            matched = $false
            createHttpStatus = if ($createResponse) { [int]$createResponse.StatusCode } else { $null }
            linkId = $null
            jobId = $null
            jobStatus = $null
            errorCode = $null
            errorMessage = $_.Exception.Message
            elapsedMs = $stopwatch.ElapsedMilliseconds
            assetCount = 0
            assetFormats = @()
            assetReadable = $false
            assetId = $null
        }
    }
}

function Get-Median {
    param([long[]]$Values)

    if ($Values.Count -eq 0) {
        return 0
    }

    $sorted = @($Values | Sort-Object)
    $middle = [math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) {
        return [long]$sorted[$middle]
    }
    return [long][math]::Round(
        ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2,
        0
    )
}

function New-AcceptanceSummary {
    param($Results)

    $positive = @($Results | Where-Object expectedOutcome -eq 'CAPTURE_SUCCEEDED')
    $negative = @($Results | Where-Object expectedOutcome -eq 'CAPTURE_FAILED')
    $positiveSuccesses = @($positive | Where-Object matched)
    $negativeMatches = @($negative | Where-Object matched)
    $durations = @($positiveSuccesses | ForEach-Object { [long]$_.elapsedMs })
    $threeFormat = @(
        $positiveSuccesses | Where-Object {
            $_.assetFormats -contains 'HTML' -and
            $_.assetFormats -contains 'PDF' -and
            $_.assetFormats -contains 'SCREENSHOT'
        }
    )
    $successRate = if ($positive.Count -eq 0) {
        0
    }
    else {
        [math]::Round(100.0 * $positiveSuccesses.Count / $positive.Count, 1)
    }
    $threeFormatRate = if ($positiveSuccesses.Count -eq 0) {
        0
    }
    else {
        [math]::Round(100.0 * $threeFormat.Count / $positiveSuccesses.Count, 1)
    }
    $medianMs = Get-Median -Values $durations
    $expectedMatches = @($Results | Where-Object matched).Count
    $expectedMatchRate = if ($Results.Count -eq 0) {
        0
    }
    else {
        [math]::Round(100.0 * $expectedMatches / $Results.Count, 1)
    }
    $passed = (
        $successRate -ge 90 -and
        $medianMs -le 30000 -and
        $negativeMatches.Count -eq $negative.Count
    )

    return [ordered]@{
        total = $Results.Count
        positiveExpected = $positive.Count
        positiveSucceeded = $positiveSuccesses.Count
        positiveSuccessRate = $successRate
        positiveMedianMs = $medianMs
        positiveMedianSeconds = [math]::Round($medianMs / 1000.0, 1)
        threeFormatCount = $threeFormat.Count
        threeFormatRate = $threeFormatRate
        negativeExpected = $negative.Count
        negativeMatched = $negativeMatches.Count
        expectedMatchCount = $expectedMatches
        expectedMatchRate = $expectedMatchRate
        passed = [bool]$passed
    }
}

function Invoke-Acceptance {
    param(
        [string]$BaseUrl,
        [string]$DatasetFile,
        [string]$OutputFile,
        [int]$JobTimeoutSeconds,
        [int]$PollIntervalSeconds
    )

    $BaseUrl = Assert-LoopbackBaseUrl $BaseUrl
    $dataset = Import-AcceptanceDataset $DatasetFile
    Assert-Ready $BaseUrl

    $startedAt = [DateTimeOffset]::UtcNow
    $session = New-AcceptanceSession $BaseUrl
    $script:ActiveSession = [pscustomobject]@{
        BaseUrl = $BaseUrl
        Token = $session.Token
    }

    $results = [System.Collections.Generic.List[object]]::new()
    foreach ($case in $dataset.Cases) {
        Write-Host "[$($case.id)] $($case.url)"
        $result = Invoke-AcceptanceCase `
            -BaseUrl $BaseUrl `
            -Token $session.Token `
            -InboxId ([long]$session.InboxId) `
            -Case $case `
            -JobTimeoutSeconds $JobTimeoutSeconds `
            -PollIntervalSeconds $PollIntervalSeconds
        $results.Add($result)
        Write-Host "  $($result.actualOutcome)  matched=$($result.matched)  $($result.elapsedMs)ms"
    }

    $summary = New-AcceptanceSummary $results.ToArray()
    $commit = 'unknown'
    try {
        $commit = (git rev-parse --short HEAD).Trim()
    }
    catch {
        # Commit is diagnostic metadata; its absence must not invalidate live results.
    }
    $report = [ordered]@{
        schemaVersion = 1
        run = [ordered]@{
            startedAt = $startedAt.ToString('o')
            finishedAt = [DateTimeOffset]::UtcNow.ToString('o')
            baseUrl = $BaseUrl
            datasetSchemaVersion = $dataset.SchemaVersion
            account = $session.Username
            commit = $commit
        }
        summary = $summary
        cases = $results.ToArray()
    }

    $parent = Split-Path -Parent ([IO.Path]::GetFullPath($OutputFile))
    if ($parent) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $OutputFile -Encoding utf8
    Write-Host ($summary | ConvertTo-Json -Depth 10 -Compress)

    if ($summary.passed) {
        return 0
    }
    return 1
}

try {
    $exitCode = Invoke-Acceptance `
        -BaseUrl $BaseUrl `
        -DatasetFile $DatasetFile `
        -OutputFile $OutputFile `
        -JobTimeoutSeconds $JobTimeoutSeconds `
        -PollIntervalSeconds $PollIntervalSeconds
    exit $exitCode
}
catch {
    [Console]::Error.WriteLine("错误：$($_.Exception.Message)")
    exit 1
}
finally {
    Close-AcceptanceSession
}
