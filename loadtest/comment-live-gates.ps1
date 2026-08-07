param(
    [ValidateSet('cassandra-outage', 'cache', 'cache-extended')]
    [string]$Gate = 'cassandra-outage'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$baseUrl = 'http://localhost:8080'
$loginBody = @{
    identifierType = 'PHONE'
    identifier = '13900000001'
    password = 'Loadtest@123'
} | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/v1/auth/login" `
    -ContentType 'application/json' -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.token.accessToken)" }

function Wait-AppHealthy {
    $deadline = (Get-Date).AddMinutes(2)
    do {
        $status = wsl -d Ubuntu-22.04 -- docker inspect -f '{{.State.Health.Status}}' zhiguang-app 2>$null
        if ($status -eq 'healthy') {
            return
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw 'App did not become healthy'
}

function Clear-CommentCache {
    wsl -d Ubuntu-22.04 -- bash -lc `
        'docker exec zhiguang-redis redis-cli --scan --pattern "comment:*" | xargs -r docker exec zhiguang-redis redis-cli del >/dev/null; docker exec zhiguang-redis redis-cli --scan --pattern "zg:singleflight:*comment-page-head*" | xargs -r docker exec zhiguang-redis redis-cli del >/dev/null'
}

function Restart-App {
    wsl -d Ubuntu-22.04 -- docker restart zhiguang-app | Out-Null
    Wait-AppHealthy
}

function Wait-ContainerHealthy([string]$container) {
    $deadline = (Get-Date).AddMinutes(3)
    do {
        $status = wsl -d Ubuntu-22.04 -- docker inspect -f '{{.State.Health.Status}}' $container 2>$null
        if ($status -eq 'healthy') {
            return
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "$container did not become healthy"
}

function Get-MetricValue([string]$name, [string[]]$tags, [string]$statistic = 'COUNT') {
    $query = if ($tags.Count -gt 0) {
        '?' + (($tags | ForEach-Object { 'tag=' + [Uri]::EscapeDataString($_) }) -join '&')
    } else {
        ''
    }
    try {
        $metric = Invoke-RestMethod -Uri "$baseUrl/actuator/metrics/$name$query"
        $measurement = $metric.measurements | Where-Object statistic -eq $statistic | Select-Object -First 1
        if ($null -eq $measurement) {
            return 0
        }
        return [double]$measurement.value
    } catch {
        return 0
    }
}

if ($Gate -eq 'cassandra-outage') {
    $requestId = "cassandra-fault-$([Guid]::NewGuid())"
    try {
        wsl -d Ubuntu-22.04 -- docker stop -t 10 zhiguang-cassandra | Out-Null
        $body = @{
            postId = 2000001
            clientRequestId = $requestId
            body = 'cassandra outage live gate'
        } | ConvertTo-Json
        $submit = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/v1/posts/2000001/comments" `
            -Headers $headers -ContentType 'application/json' -Body $body
        $pendingId = $submit.pendingCommentId
        Start-Sleep -Seconds 8
        $during = Invoke-RestMethod -Uri "$baseUrl/api/v1/comments/$pendingId/status" -Headers $headers
        Write-Output "during=$($during.status) pendingId=$pendingId requestId=$requestId"
        if ($during.status -ne 'pending') {
            throw "Expected pending during Cassandra outage, got $($during.status)"
        }

        wsl -d Ubuntu-22.04 -- docker start zhiguang-cassandra | Out-Null
        $deadline = (Get-Date).AddMinutes(3)
        do {
            Start-Sleep -Seconds 2
            $after = Invoke-RestMethod -Uri "$baseUrl/api/v1/comments/$pendingId/status" -Headers $headers
        } while ($after.status -ne 'succeeded' -and (Get-Date) -lt $deadline)
        Write-Output "after=$($after.status) commentId=$($after.commentId)"
        if ($after.status -ne 'succeeded') {
            throw 'Cassandra recovery did not materialize comment'
        }
    } finally {
        wsl -d Ubuntu-22.04 -- docker start zhiguang-cassandra 2>$null | Out-Null
    }
}

if ($Gate -eq 'cache') {
    $postId = 2000001
    Clear-CommentCache
    Restart-App

    $cold = Invoke-RestMethod -Uri "$baseUrl/api/v1/posts/$postId/comments?limit=20" -Headers $headers
    if ($cold.items.Count -eq 0) {
        throw 'Cold L3 returned no seeded comments'
    }
    $mysqlAfterCold = Get-MetricValue 'comment.read.dependencies' @('dependency:mysql', 'result:success')
    $cassandraAfterCold = Get-MetricValue 'comment.read.dependencies' @('dependency:cassandra', 'result:success')
    Write-Output "cold_items=$($cold.items.Count) mysql=$mysqlAfterCold cassandra=$cassandraAfterCold"

    $null = Invoke-RestMethod -Uri "$baseUrl/api/v1/posts/$postId/comments?limit=20" -Headers $headers
    $l1Hits = Get-MetricValue 'comment.cache.requests' @('level:l1', 'result:hit')
    if ($l1Hits -lt 1) {
        throw 'Repeated read did not hit L1'
    }

    Restart-App
    $l2 = Invoke-RestMethod -Uri "$baseUrl/api/v1/posts/$postId/comments?limit=20" -Headers $headers
    $l2Hits = Get-MetricValue 'comment.cache.requests' @('level:l2', 'result:hit')
    if ($l2Hits -lt 1 -or $l2.items.Count -ne $cold.items.Count) {
        throw 'Read after restart did not hit a complete L2 page'
    }

    $commentId = [string]$l2.items[0].commentId
    $null = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/v1/comments/$commentId/like" -Headers $headers
    $aPage = Invoke-RestMethod -Uri "$baseUrl/api/v1/posts/$postId/comments?limit=20" -Headers $headers
    $loginBBody = @{
        identifierType = 'PHONE'
        identifier = '13900000002'
        password = 'Loadtest@123'
    } | ConvertTo-Json
    $loginB = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/v1/auth/login" `
        -ContentType 'application/json' -Body $loginBBody
    $headersB = @{ Authorization = "Bearer $($loginB.token.accessToken)" }
    $bPage = Invoke-RestMethod -Uri "$baseUrl/api/v1/posts/$postId/comments?limit=20" -Headers $headersB
    $aItem = $aPage.items | Where-Object commentId -eq $commentId | Select-Object -First 1
    $bItem = $bPage.items | Where-Object commentId -eq $commentId | Select-Object -First 1
    if (-not $aItem.liked -or $bItem.liked) {
        throw 'Shared page leaked user liked state'
    }
    $fragment = wsl -d Ubuntu-22.04 -- docker exec zhiguang-redis redis-cli get "comment:item:$commentId"
    if ($fragment -match '"liked"') {
        throw 'Redis fragment contains user liked state'
    }
    Write-Output "user_isolation=A:true,B:false fragment_has_liked=false commentId=$commentId"

    wsl -d Ubuntu-22.04 -- docker exec zhiguang-redis redis-cli del "comment:item:$commentId" | Out-Null
    Restart-App
    $fragmentReload = Invoke-RestMethod -Uri "$baseUrl/api/v1/posts/$postId/comments?limit=20" -Headers $headers
    if ($fragmentReload.items.Count -ne $cold.items.Count) {
        throw 'Missing Redis fragment returned a partial page'
    }

    $emptyPostId = 2999999
    $emptyFirst = Invoke-RestMethod -Uri "$baseUrl/api/v1/posts/$emptyPostId/comments?limit=20" -Headers $headers
    $emptySecond = Invoke-RestMethod -Uri "$baseUrl/api/v1/posts/$emptyPostId/comments?limit=20" -Headers $headers
    $emptyExists = wsl -d Ubuntu-22.04 -- docker exec zhiguang-redis redis-cli exists `
        "comment:idx:post:$emptyPostId`:head:20:empty"
    if ($emptyFirst.items.Count -ne 0 -or $emptySecond.items.Count -ne 0 -or $emptyExists -ne '1') {
        throw 'Empty-page protection did not cache a complete empty result'
    }

    Clear-CommentCache
    Restart-App
    $mysqlBeforeBurst = Get-MetricValue 'comment.read.dependencies' @('dependency:mysql', 'result:success')
    $client = [System.Net.Http.HttpClient]::new()
    $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new(
        'Bearer', $login.token.accessToken)
    $tasks = 1..100 | ForEach-Object {
        $client.GetAsync("$baseUrl/api/v1/posts/$postId/comments?limit=20")
    }
    $responses = $tasks | ForEach-Object { $_.GetAwaiter().GetResult() }
    if (($responses | Where-Object { -not $_.IsSuccessStatusCode }).Count -gt 0) {
        throw 'Concurrent singleflight requests did not all succeed'
    }
    $mysqlAfterBurst = Get-MetricValue 'comment.read.dependencies' @('dependency:mysql', 'result:success')
    $mysqlDelta = $mysqlAfterBurst - $mysqlBeforeBurst
    if ($mysqlDelta -ne 1) {
        throw "Concurrent cache miss executed MySQL loader $mysqlDelta times"
    }
    Write-Output "l1_hits=$l1Hits l2_hits=$l2Hits fragment_reload_items=$($fragmentReload.items.Count) empty_cached=true singleflight_mysql_delta=$mysqlDelta"
}

if ($Gate -eq 'cache-extended') {
    $postId = 2000001
    $head = Invoke-RestMethod -Uri "$baseUrl/api/v1/posts/$postId/comments?limit=20" -Headers $headers
    $keysBefore = @(wsl -d Ubuntu-22.04 -- docker exec zhiguang-redis redis-cli --scan `
        --pattern "comment:idx:post:$postId`:head:20*").Count
    $cursorTime = [Uri]::EscapeDataString([string]$head.nextCursorCreateTime)
    $cursorId = [Uri]::EscapeDataString([string]$head.nextCursorCommentId)
    $second = Invoke-RestMethod -Uri `
        "$baseUrl/api/v1/posts/$postId/comments?limit=20&cursorCreateTime=$cursorTime&cursorCommentId=$cursorId" `
        -Headers $headers
    $keysAfter = @(wsl -d Ubuntu-22.04 -- docker exec zhiguang-redis redis-cli --scan `
        --pattern "comment:idx:post:$postId`:head:20*").Count
    if ($second.items.Count -eq 0 -or $keysAfter -ne $keysBefore) {
        throw 'Cursor read created a new head cache key or returned no data'
    }

    $requestId = "cache-invalidation-$([Guid]::NewGuid())"
    $body = @{
        postId = $postId
        clientRequestId = $requestId
        body = 'cache invalidation live gate'
    } | ConvertTo-Json
    $submit = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/v1/posts/$postId/comments" `
        -Headers $headers -ContentType 'application/json' -Body $body
    $pendingId = $submit.pendingCommentId
    $deadline = (Get-Date).AddMinutes(2)
    do {
        Start-Sleep -Seconds 1
        $status = Invoke-RestMethod -Uri "$baseUrl/api/v1/comments/$pendingId/status" -Headers $headers
    } while ($status.status -ne 'succeeded' -and (Get-Date) -lt $deadline)
    if ($status.status -ne 'succeeded') {
        throw 'Created comment did not materialize'
    }
    $indexKey = "comment:idx:post:$postId`:head:20:ids"
    $deadline = (Get-Date).AddSeconds(15)
    do {
        $indexExists = wsl -d Ubuntu-22.04 -- docker exec zhiguang-redis redis-cli exists $indexKey
        if ($indexExists -eq '0') {
            break
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    if ($indexExists -ne '0') {
        throw 'Create event did not invalidate the post head index'
    }

    $warm = Invoke-RestMethod -Uri "$baseUrl/api/v1/posts/$postId/comments?limit=20" -Headers $headers
    try {
        wsl -d Ubuntu-22.04 -- docker stop -t 10 zhiguang-mysql zhiguang-cassandra | Out-Null
        $warmDuringOutage = Invoke-RestMethod -Uri "$baseUrl/api/v1/posts/$postId/comments?limit=20" -Headers $headers
        if ($warmDuringOutage.items.Count -ne $warm.items.Count) {
            throw 'Warm cache changed while MySQL and Cassandra were unavailable'
        }
        Clear-CommentCache
        Start-Sleep -Seconds 4
        $coldFailed = $false
        try {
            Invoke-RestMethod -Uri "$baseUrl/api/v1/posts/$postId/comments?limit=20" -Headers $headers | Out-Null
        } catch {
            $coldFailed = $true
        }
        if (-not $coldFailed) {
            throw 'Cold miss fabricated data while MySQL and Cassandra were unavailable'
        }
    } finally {
        wsl -d Ubuntu-22.04 -- docker start zhiguang-mysql zhiguang-cassandra | Out-Null
        Wait-ContainerHealthy 'zhiguang-mysql'
        Wait-ContainerHealthy 'zhiguang-cassandra'
        Wait-AppHealthy
    }

    $deleteDeadline = (Get-Date).AddMinutes(2)
    $deleteSucceeded = $false
    do {
        try {
            Invoke-RestMethod -Method Delete -Uri "$baseUrl/api/v1/comments/$pendingId" -Headers $headers | Out-Null
            $deleteSucceeded = $true
        } catch {
            Start-Sleep -Seconds 2
        }
    } while (-not $deleteSucceeded -and (Get-Date) -lt $deleteDeadline)
    if (-not $deleteSucceeded) {
        throw 'Owner delete did not recover after Cassandra became available'
    }
    Start-Sleep -Seconds 2
    $deleted = wsl -d Ubuntu-22.04 -- docker exec -i -e MYSQL_PWD=zhiguang123456 `
        zhiguang-mysql mysql -uzhiguang -N -B zhiguang -e `
        "SELECT CONCAT(status,':',(SELECT COUNT(*) FROM comment_outbox WHERE aggregate_id=$pendingId AND event_type='COMMENT_DELETED')) FROM comments WHERE comment_id=$pendingId;" `
        2>$null
    if ($deleted -ne '1:1') {
        throw "Owner delete did not atomically persist status and event: $deleted"
    }
    Write-Output "cursor_items=$($second.items.Count) cursor_head_keys_unchanged=true create_invalidated=true warm_outage_items=$($warmDuringOutage.items.Count) cold_outage_failed=true delete_status_event=$deleted"
}
