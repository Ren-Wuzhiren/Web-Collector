<#
.SYNOPSIS
在本地后端幂等创建普通 demo 账号和完整演示数据。

.DESCRIPTION
只允许连接本机 HTTP/HTTPS 后端，通过现有业务 API 创建或复用账号、集合、标签和链接。
脚本不会删除或改写已有链接资料，也不会输出密码或 token。需要 PowerShell 7。

.EXAMPLE
.\scripts\seed-demo-account.ps1
#>
#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$Username = 'demo',
    [string]$Email = 'demo@example.com',
    [ValidateRange(1, 3600)]
    [int]$JobTimeoutSeconds = 180,
    [ValidateRange(1, 60)]
    [int]$PollIntervalSeconds = 3
)

$ErrorActionPreference = 'Stop'
$script:ActiveDemoSession = $null

function Get-DemoPassword {
    $password = $env:WEBCOLLECTOR_DEMO_PASSWORD

    if ([string]::IsNullOrWhiteSpace($password)) {
        if (-not [Environment]::UserInteractive) {
            throw '缺少 WEBCOLLECTOR_DEMO_PASSWORD，非交互模式无法提示输入密码。'
        }

        $securePassword = Read-Host '请输入 demo 账号密码' -AsSecureString
        $password = ConvertFrom-SecureString $securePassword -AsPlainText
    }

    if ($password.Length -lt 8 -or $password.Length -gt 72) {
        throw 'demo 密码长度必须在 8 到 72 个字符之间。'
    }

    return $password
}

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

function Convert-ResponsePayload {
    param($Response)

    if ([string]::IsNullOrWhiteSpace($Response.Content)) {
        return $null
    }

    return $Response.Content | ConvertFrom-Json -Depth 20
}

function Invoke-DemoApi {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BaseUrl,
        [Parameter(Mandatory = $true)]
        [ValidateSet('GET', 'POST', 'PATCH', 'PUT', 'DELETE')]
        [string]$Method,
        [Parameter(Mandatory = $true)]
        [string]$Path,
        $Body = $null,
        [string]$Token = $null
    )

    $headers = @{
        Accept = 'application/json'
    }
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

    return [pscustomobject]@{
        StatusCode = [int]$response.StatusCode
        Payload = Convert-ResponsePayload $response
    }
}

function Get-ApiFailureMessage {
    param($Response)

    $message = $Response.Payload.message
    $code = $Response.Payload.code
    if ($message -and $code) {
        return "$code $message"
    }
    if ($message) {
        return $message
    }
    if ($code) {
        return $code
    }
    return "HTTP $($Response.StatusCode)"
}

function Assert-Ready {
    param([string]$BaseUrl)

    try {
        $response = Invoke-DemoApi -BaseUrl $BaseUrl -Method GET -Path '/health/ready'
    }
    catch {
        throw "后端不可用，MySQL/Redis 无法确认就绪：$($_.Exception.Message)"
    }

    $mysql = $response.Payload.data.mysql
    $redis = $response.Payload.data.redis
    if (-not $mysql) {
        $mysql = $response.Payload.details.mysql
    }
    if (-not $redis) {
        $redis = $response.Payload.details.redis
    }

    if (
        $response.StatusCode -ne 200 -or
        $mysql -ne 'UP' -or
        $redis -ne 'UP'
    ) {
        throw "MySQL/Redis 尚未就绪：HTTP $($response.StatusCode)，mysql=$($mysql ?? 'UNKNOWN')，redis=$($redis ?? 'UNKNOWN')。$((Get-ApiFailureMessage $response))"
    }
}

function Resolve-DemoAccount {
    param(
        [string]$BaseUrl,
        [string]$Username,
        [string]$Email,
        [string]$Password
    )

    $register = Invoke-DemoApi `
        -BaseUrl $BaseUrl `
        -Method POST `
        -Path '/auth/register' `
        -Body @{
            username = $Username
            email = $Email
            password = $Password
        }

    $accountMode = '新建'
    if ($register.StatusCode -eq 201) {
        $accountMode = '新建'
    }
    elseif ($register.StatusCode -eq 409) {
        $accountMode = '复用'
    }
    elseif ($register.StatusCode -eq 403 -and $register.Payload.code -eq 'REGISTRATION_DISABLED') {
        throw '当前环境未开放注册，无法创建 demo 账号；请使用 local profile 并开启 app.auth.registration-enabled。'
    }
    else {
        throw "创建 demo 账号失败：$(Get-ApiFailureMessage $register)"
    }

    $login = Invoke-DemoApi `
        -BaseUrl $BaseUrl `
        -Method POST `
        -Path '/auth/login' `
        -Body @{
            username = $Username
            password = $Password
        }

    if ($login.StatusCode -ne 200 -or -not $login.Payload.data.token) {
        if ($accountMode -eq '复用') {
            throw "账号 $Username 的注册冲突未能通过登录解析：$(Get-ApiFailureMessage $register)。请确认密码正确，且邮箱 $Email 未被其他账号占用；脚本没有修改任何演示数据。"
        }
        throw "demo 账号登录失败：$(Get-ApiFailureMessage $login)"
    }

    return [pscustomobject]@{
        Mode = $accountMode
        Token = $login.Payload.data.token
    }
}

function Get-ListData {
    param($Response, [string]$Label)

    if ($Response.StatusCode -ne 200) {
        throw "$Label 失败：$(Get-ApiFailureMessage $Response)"
    }

    return @($Response.Payload.data)
}

function Close-DemoSession {
    if (-not $script:ActiveDemoSession) {
        return
    }

    $session = $script:ActiveDemoSession
    $script:ActiveDemoSession = $null

    try {
        $logout = Invoke-DemoApi `
            -BaseUrl $session.BaseUrl `
            -Method POST `
            -Path '/auth/logout' `
            -Token $session.Token
        if ($logout.StatusCode -ne 200) {
            Write-Warning "demo 会话退出失败：$(Get-ApiFailureMessage $logout)"
        }
    }
    catch {
        Write-Warning "demo 会话退出失败：$($_.Exception.Message)"
    }
}

function Wait-DemoJob {
    param(
        [string]$BaseUrl,
        [string]$Token,
        [long]$JobId,
        [int]$TimeoutSeconds,
        [int]$PollIntervalSeconds,
        [string]$Url
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastStatus = 'UNKNOWN'

    do {
        $response = Invoke-DemoApi `
            -BaseUrl $BaseUrl `
            -Method GET `
            -Path "/jobs/$JobId" `
            -Token $Token

        if ($response.StatusCode -ne 200) {
            throw "读取任务失败：$(Get-ApiFailureMessage $response)"
        }

        $job = $response.Payload.data
        $lastStatus = $job.status
        if ($job.status -eq 'SUCCEEDED') {
            return $job
        }
        if ($job.status -eq 'FAILED') {
            $reason = $job.errorMessage
            if (-not $reason) {
                $reason = $job.errorCode
            }
            throw "任务 FAILED：$reason"
        }

        Start-Sleep -Seconds $PollIntervalSeconds
    } while ((Get-Date) -lt $deadline)

    throw "任务在 $TimeoutSeconds 秒内未完成，最后状态 $lastStatus"
}

function Test-DemoLinkReady {
    param(
        [string]$BaseUrl,
        [string]$Token,
        [long]$LinkId
    )

    $readable = Invoke-DemoApi `
        -BaseUrl $BaseUrl `
        -Method GET `
        -Path "/links/$LinkId/readable" `
        -Token $Token

    if (
        $readable.StatusCode -ne 200 -or
        [string]::IsNullOrWhiteSpace($readable.Payload.data.textContent)
    ) {
        return [pscustomobject]@{
            Ready = $false
            Reason = "可读正文不可用：$(Get-ApiFailureMessage $readable)"
        }
    }

    $assets = Invoke-DemoApi `
        -BaseUrl $BaseUrl `
        -Method GET `
        -Path "/assets?linkId=$LinkId" `
        -Token $Token

    if ($assets.StatusCode -ne 200) {
        return [pscustomobject]@{
            Ready = $false
            Reason = "资产清单不可用：$(Get-ApiFailureMessage $assets)"
        }
    }

    $formats = @($assets.Payload.data | ForEach-Object { $_.format } | Sort-Object -Unique)
    $required = @('HTML', 'PDF', 'SCREENSHOT')
    $missing = @($required | Where-Object { $_ -notin $formats })
    if ($missing.Count -gt 0) {
        return [pscustomobject]@{
            Ready = $false
            Reason = "缺少资产：$($missing -join ', ')"
        }
    }

    return [pscustomobject]@{
        Ready = $true
        Reason = ''
    }
}

function Invoke-DemoSeed {
    param(
        [string]$BaseUrl,
        [string]$Username,
        [string]$Email,
        [int]$JobTimeoutSeconds,
        [int]$PollIntervalSeconds
    )

    $BaseUrl = Assert-LoopbackBaseUrl $BaseUrl
    $Password = Get-DemoPassword
    Assert-Ready $BaseUrl

    $account = Resolve-DemoAccount `
        -BaseUrl $BaseUrl `
        -Username $Username `
        -Email $Email `
        -Password $Password
    $Token = $account.Token
    $script:ActiveDemoSession = [pscustomobject]@{
        BaseUrl = $BaseUrl
        Token = $Token
    }

    $collectionDefinitions = @(
        [pscustomobject]@{
            Name = '工程阅读'
            Description = 'Web 平台、后端框架与并发基础参考。'
        },
        [pscustomobject]@{
            Name = '协议档案'
            Description = 'HTTP 语义、安全与缓存资料。'
        }
    )
    $tagNames = @('Web', 'HTTP', '安全', 'Spring', 'Java', '性能')

    $collectionsResponse = Invoke-DemoApi `
        -BaseUrl $BaseUrl `
        -Method GET `
        -Path '/collections' `
        -Token $Token
    $collections = @(Get-ListData $collectionsResponse '读取集合')
    $inbox = $collections | Where-Object { $_.isInbox -eq $true } | Select-Object -First 1
    if (-not $inbox) {
        throw "账号 $Username 缺少默认 Inbox，无法继续初始化演示数据。"
    }

    $collectionCreated = 0
    $collectionReused = 0
    foreach ($definition in $collectionDefinitions) {
        $existing = $collections |
            Where-Object { $_.name -eq $definition.Name } |
            Select-Object -First 1
        if ($existing) {
            $collectionReused += 1
            continue
        }

        $created = Invoke-DemoApi `
            -BaseUrl $BaseUrl `
            -Method POST `
            -Path '/collections' `
            -Token $Token `
            -Body @{
                name = $definition.Name
                description = $definition.Description
            }
        if ($created.StatusCode -ne 201) {
            throw "创建集合 $($definition.Name) 失败：$(Get-ApiFailureMessage $created)"
        }
        $collections += $created.Payload.data
        $collectionCreated += 1
    }

    $tagsResponse = Invoke-DemoApi `
        -BaseUrl $BaseUrl `
        -Method GET `
        -Path '/tags' `
        -Token $Token
    $tags = @(Get-ListData $tagsResponse '读取标签')
    $tagCreated = 0
    $tagReused = 0
    foreach ($tagName in $tagNames) {
        $existing = $tags |
            Where-Object { $_.name -eq $tagName } |
            Select-Object -First 1
        if ($existing) {
            $tagReused += 1
            continue
        }

        $created = Invoke-DemoApi `
            -BaseUrl $BaseUrl `
            -Method POST `
            -Path '/tags' `
            -Token $Token `
            -Body @{ name = $tagName }
        if ($created.StatusCode -ne 201) {
            throw "创建标签 $tagName 失败：$(Get-ApiFailureMessage $created)"
        }
        $tags += $created.Payload.data
        $tagCreated += 1
    }

    $linksResponse = Invoke-DemoApi `
        -BaseUrl $BaseUrl `
        -Method GET `
        -Path '/links' `
        -Token $Token
    $links = @(Get-ListData $linksResponse '读取收藏')

    $linkDefinitions = @(
        [pscustomobject]@{
            Url = 'https://example.com'
            Title = 'Example Domain'
            Description = '用于验证最小网页抓取与证据生成。'
            Collection = 'Inbox'
            Tags = @('Web')
        },
        [pscustomobject]@{
            Url = 'https://developer.mozilla.org/en-US/docs/Web/Security'
            Title = 'Web security'
            Description = '浏览器同源策略、安全响应头与 Web 平台边界。'
            Collection = '协议档案'
            Tags = @('安全', 'Web')
        },
        [pscustomobject]@{
            Url = 'https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Cookies'
            Title = 'Using HTTP cookies'
            Description = 'Cookie 的作用域、生命周期与浏览器安全边界。'
            Collection = '协议档案'
            Tags = @('HTTP', 'Web', '安全')
        },
        [pscustomobject]@{
            Url = 'https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CORS'
            Title = 'Cross-Origin Resource Sharing'
            Description = '跨源资源共享的请求流程与响应头。'
            Collection = '工程阅读'
            Tags = @('HTTP', 'Web', '安全')
        },
        [pscustomobject]@{
            Url = 'https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Caching'
            Title = 'HTTP caching'
            Description = 'HTTP 缓存策略、验证与失效机制。'
            Collection = '协议档案'
            Tags = @('HTTP', '性能')
        },
        [pscustomobject]@{
            Url = 'https://docs.spring.io/spring-boot/reference/features/spring-application.html'
            Title = 'SpringApplication'
            Description = 'Spring Boot 应用启动与配置参考。'
            Collection = '工程阅读'
            Tags = @('Spring', 'Java')
        }
    )

    $results = [System.Collections.Generic.List[object]]::new()
    $linkCreated = 0
    $linkReused = 0
    foreach ($definition in $linkDefinitions) {
        $collection = $collections |
            Where-Object { $_.name -eq $definition.Collection } |
            Select-Object -First 1
        if (-not $collection) {
            throw "找不到演示集合 $($definition.Collection)。"
        }

        $tagIds = @(
            foreach ($tagName in $definition.Tags) {
                $tag = $tags |
                    Where-Object { $_.name -eq $tagName } |
                    Select-Object -First 1
                if (-not $tag) {
                    throw "找不到演示标签 $tagName。"
                }
                $tag.id
            }
        )

        try {
            $existing = $links |
                Where-Object { $_.originalUrl -ceq $definition.Url } |
                Select-Object -First 1

            if ($existing) {
                $link = $existing
                $mode = '复用'
                $linkReused += 1
            }
            else {
                $created = Invoke-DemoApi `
                    -BaseUrl $BaseUrl `
                    -Method POST `
                    -Path '/links' `
                    -Token $Token `
                    -Body @{
                        url = $definition.Url
                        collectionId = $collection.id
                        title = $definition.Title
                        description = $definition.Description
                        tagIds = $tagIds
                    }

                if ($created.StatusCode -eq 409) {
                    throw "链接已存在但无法按原始 URL 复用：$(Get-ApiFailureMessage $created)"
                }
                if ($created.StatusCode -ne 201) {
                    throw "创建链接失败：$(Get-ApiFailureMessage $created)"
                }

                $link = $created.Payload.data.link
                $links += $link
                $mode = '新建'
                $linkCreated += 1
                [void](Wait-DemoJob `
                    -BaseUrl $BaseUrl `
                    -Token $Token `
                    -JobId ([long]$created.Payload.data.job.id) `
                    -TimeoutSeconds $JobTimeoutSeconds `
                    -PollIntervalSeconds $PollIntervalSeconds `
                    -Url $definition.Url)
            }

            $ready = Test-DemoLinkReady `
                -BaseUrl $BaseUrl `
                -Token $Token `
                -LinkId ([long]$link.id)
            if (-not $ready.Ready) {
                throw $ready.Reason
            }

            $results.Add([pscustomobject]@{
                Status = 'SUCCEEDED'
                Mode = $mode
                Url = $definition.Url
                LinkId = $link.id
                Reason = ''
            })
            Write-Host "[$mode] SUCCEEDED  $($definition.Url)  link=$($link.id)"
        }
        catch {
            $results.Add([pscustomobject]@{
                Status = 'FAILED'
                Mode = '失败'
                Url = $definition.Url
                LinkId = $null
                Reason = $_.Exception.Message
            })
            Write-Host "[失败] FAILED     $($definition.Url)  $($_.Exception.Message)"
        }
    }

    $succeeded = @($results | Where-Object status -eq 'SUCCEEDED').Count
    $failed = @($results | Where-Object status -eq 'FAILED').Count

    Write-Host ''
    Write-Host 'demo 演示数据初始化结果'
    Write-Host "账号：$Username（$($account.Mode)）"
    Write-Host "集合：新建 $collectionCreated，复用 $collectionReused"
    Write-Host "标签：新建 $tagCreated，复用 $tagReused"
    Write-Host "链接：新建 $linkCreated，复用 $linkReused，成功 $succeeded，失败 $failed"

    if ($failed -gt 0) {
        Write-Host '以下链接未通过抓取或证据校验：'
        foreach ($result in $results | Where-Object status -eq 'FAILED') {
            Write-Host "- $($result.Url)：$($result.Reason)"
        }
        return 1
    }

    return 0
}

try {
    $exitCode = Invoke-DemoSeed `
        -BaseUrl $BaseUrl `
        -Username $Username `
        -Email $Email `
        -JobTimeoutSeconds $JobTimeoutSeconds `
        -PollIntervalSeconds $PollIntervalSeconds
    exit $exitCode
}
catch {
    [Console]::Error.WriteLine("错误：$($_.Exception.Message)")
    exit 1
}
finally {
    Close-DemoSession
}
