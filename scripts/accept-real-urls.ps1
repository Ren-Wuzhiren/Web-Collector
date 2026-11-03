param(
    [Parameter(Mandatory = $true)]
    [string]$BaseUrl,
    [Parameter(Mandatory = $true)]
    [string]$Username,
    [Parameter(Mandatory = $true)]
    [string]$Password,
    [Parameter(Mandatory = $true)]
    [string]$UrlsFile,
    [string]$OutputFile = ".\acceptance-results.json"
)

$ErrorActionPreference = "Stop"
$base = $BaseUrl.TrimEnd("/")

$login = Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/login" `
    -ContentType "application/json" `
    -Body (@{ username = $Username; password = $Password } | ConvertTo-Json)
$token = $login.data.token
$headers = @{ Authorization = "Bearer $token" }
$collections = Invoke-RestMethod -Method Get -Uri "$base/api/v1/collections" -Headers $headers
$collectionId = $collections.data[0].id

$results = foreach ($url in Get-Content -LiteralPath $UrlsFile | Where-Object { $_.Trim() }) {
    $started = Get-Date
    try {
        $created = Invoke-RestMethod -Method Post -Uri "$base/api/v1/links" -Headers $headers `
            -ContentType "application/json" `
            -Body (@{
                url = $url.Trim()
                collectionId = $collectionId
                title = ""
                description = ""
                tagIds = @()
            } | ConvertTo-Json)
        $jobId = $created.data.job.id
        $job = $null
        do {
            Start-Sleep -Seconds 3
            $job = (Invoke-RestMethod -Method Get -Uri "$base/api/v1/jobs/$jobId" -Headers $headers).data
        } while ($job.status -in @("PENDING", "RUNNING"))
        [PSCustomObject]@{
            url = $url.Trim()
            status = $job.status
            elapsedSeconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
            error = $job.errorMessage
            assets = $job.assets.Count
        }
    } catch {
        [PSCustomObject]@{
            url = $url.Trim()
            status = "CLIENT_ERROR"
            elapsedSeconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
            error = $_.Exception.Message
            assets = 0
        }
    }
}

$results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OutputFile -Encoding utf8
$summary = [PSCustomObject]@{
    total = @($results).Count
    succeeded = @($results | Where-Object status -eq "SUCCEEDED").Count
    failed = @($results | Where-Object status -ne "SUCCEEDED").Count
    successRate = if (@($results).Count) { [math]::Round(100 * @($results | Where-Object status -eq "SUCCEEDED").Count / @($results).Count, 1) } else { 0 }
    medianSeconds = if (@($results).Count) { (@($results | Sort-Object elapsedSeconds)[[math]::Floor((@($results).Count - 1) / 2)]).elapsedSeconds } else { 0 }
}
$summary | ConvertTo-Json | Write-Host
