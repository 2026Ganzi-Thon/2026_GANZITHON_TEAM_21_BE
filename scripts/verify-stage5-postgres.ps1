param([string]$Image = 'postgres:18-alpine', [switch]$IncludeStage6)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 10)
$containerName = 'emori-stage5-' + $suffix
$databasePassword = [guid]::NewGuid().ToString('N')
$appProcess = $null
$containerStarted = $false
$oldDbUrl = $env:DB_URL
$oldDbUsername = $env:DB_USERNAME
$oldDbPassword = $env:DB_PASSWORD
$oldPgTest = $env:EMORI_POSTGRES_TEST
$oldStage3Url = $env:EMORI_STAGE3_TEST_DB_URL
$oldSpringProfile = $env:SPRING_PROFILES_ACTIVE
$oldStage4Url = $env:EMORI_STAGE4_TEST_DB_URL
$oldMidnightUrl = $env:EMORI_STAGE4_MIDNIGHT_DB_URL
$oldStage5Url = $env:EMORI_STAGE5_TEST_DB_URL
$oldStage6Url = $env:EMORI_STAGE6_TEST_DB_URL
$oldStage7Url = $env:EMORI_STAGE7_TEST_DB_URL
$oldFlywayLocations = $env:SPRING_FLYWAY_LOCATIONS
$oldAuthTestUrl = $env:EMORI_AUTH_TEST_DB_URL
$oldStage2TestUrl = $env:EMORI_STAGE2_TEST_DB_URL
$oldContextTestUrl = $env:EMORI_CONTEXT_TEST_DB_URL
$oldAsmrFixture = $env:EMORI_ASMR_TEST_GENERATOR
$oldAsmrWorker = $env:EMORI_ASMR_WORKER_ENABLED
$oldAsmrStorage = $env:EMORI_ASMR_STORAGE_ROOT
$oldIdempotencySecret = $env:EMORI_IDEMPOTENCY_SECRET
$oldHikariPoolSize = $env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE

function Get-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port }
    finally { $listener.Stop() }
}

Push-Location $project
try {
    docker version --format '{{.Server.Version}}' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker engine is not available.' }

    $containerId = docker run --rm -d --name $containerName `
        -e POSTGRES_DB=emori -e POSTGRES_USER=emori -e "POSTGRES_PASSWORD=$databasePassword" `
        -p '127.0.0.1::5432' $Image
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the PostgreSQL container.' }
    $containerStarted = $true
    Write-Host "PostgreSQL container: $containerName"

    $ready = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        docker exec $containerName pg_isready -U emori -d emori *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'PostgreSQL did not become ready within 90 seconds.' }

    $portLine = docker port $containerName 5432/tcp
    if ($LASTEXITCODE -ne 0 -or $portLine -notmatch ':(\d+)$') { throw 'Could not determine the PostgreSQL port.' }
    $databasePort = [int]$Matches[1]
    $appPort = Get-FreePort
    $env:DB_URL = "jdbc:postgresql://127.0.0.1:$databasePort/emori"
    $env:DB_USERNAME = 'emori'
    $env:DB_PASSWORD = $databasePassword
    $secretBytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($secretBytes)
    $env:EMORI_IDEMPOTENCY_SECRET = [Convert]::ToBase64String($secretBytes)

    & .\gradlew.bat bootJar
    if ($LASTEXITCODE -ne 0) { throw 'bootJar failed.' }
    $jar = Get-ChildItem (Join-Path $project 'build/libs') -Filter '*.jar' |
        Where-Object { $_.Name -notlike '*-plain.jar' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw 'Boot JAR not found.' }

    $stdout = Join-Path $project "build/stage5-postgres-$suffix.out.log"
    $stderr = Join-Path $project "build/stage5-postgres-$suffix.err.log"
    $appProcess = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jar.FullName,
        '--spring.profiles.active=postgres', "--server.port=$appPort") `
        -WorkingDirectory $project -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr

    $listening = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        if ($appProcess.HasExited) { throw "Application exited during startup. See $stdout and $stderr" }
        try {
            $socket = [System.Net.Sockets.TcpClient]::new()
            $socket.Connect('127.0.0.1', $appPort)
            $socket.Dispose()
            $listening = $true
            break
        } catch { Start-Sleep -Seconds 1 }
    }
    if (-not $listening) { throw "Application did not start within 90 seconds. See $stdout and $stderr" }

    $baseUrl = "http://127.0.0.1:$appPort/api/v1"
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-auth.ps1 -BaseUrl $baseUrl
    if ($LASTEXITCODE -ne 0) { throw 'Authentication HTTP smoke test failed.' }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-stage2.ps1 -BaseUrl $baseUrl
    if ($LASTEXITCODE -ne 0) { throw 'Stage 2 HTTP smoke test failed.' }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-stage3.ps1 -BaseUrl $baseUrl
    if ($LASTEXITCODE -ne 0) { throw 'Stage 3 HTTP smoke test failed.' }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-stage4-postgres.ps1 -BaseUrl $baseUrl -ContainerName $containerName
    if ($LASTEXITCODE -ne 0) { throw 'Stage 4 HTTP smoke test failed.' }

    $env:EMORI_POSTGRES_TEST = 'true'
    $env:EMORI_STAGE3_TEST_DB_URL = $env:DB_URL
    $env:SPRING_PROFILES_ACTIVE = 'postgres'
    $env:EMORI_STAGE4_TEST_DB_URL = $env:DB_URL
    $env:EMORI_STAGE4_MIDNIGHT_DB_URL = $env:DB_URL
    $env:EMORI_STAGE5_TEST_DB_URL = $env:DB_URL
    if (-not $IncludeStage6) {
        & .\gradlew.bat test --tests 'com.ganzi.emori.Stage2PostgresRaceTests'
        if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL concurrency test failed.' }
        & .\gradlew.bat test --tests 'com.ganzi.emori.Stage3HttpTests'
        if ($LASTEXITCODE -ne 0) { throw 'Stage 3 PostgreSQL HTTP and concurrency tests failed.' }
        & .\gradlew.bat test --tests 'com.ganzi.emori.Stage4HttpTests' --tests 'com.ganzi.emori.Stage4MidnightTests'
        if ($LASTEXITCODE -ne 0) { throw 'Stage 4 PostgreSQL HTTP and concurrency tests failed.' }
        & .\gradlew.bat test --tests 'com.ganzi.emori.Stage5HttpTests'
        if ($LASTEXITCODE -ne 0) { throw 'Stage 5 PostgreSQL tests failed.' }
    }
    if ($IncludeStage6) {
        # The full suite keeps several Spring contexts alive at once; bound each test pool.
        $env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = '3'
        $env:EMORI_STAGE6_TEST_DB_URL = $env:DB_URL
        $env:EMORI_STAGE7_TEST_DB_URL = $env:DB_URL
        $env:SPRING_FLYWAY_LOCATIONS = 'classpath:db/migration,classpath:db/postgres'
        $env:EMORI_AUTH_TEST_DB_URL = $env:DB_URL
        $env:EMORI_STAGE2_TEST_DB_URL = $env:DB_URL
        $env:EMORI_CONTEXT_TEST_DB_URL = $env:DB_URL
        & .\gradlew.bat test --rerun-tasks
        if ($LASTEXITCODE -ne 0) { throw 'Full PostgreSQL test suite failed.' }
    }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-stage5.ps1 -BaseUrl $baseUrl
    if ($LASTEXITCODE -ne 0) { throw 'Stage 5 HTTP smoke test failed.' }

    # Exercise a persisted encrypted anonymous-open response across two JVM processes.
    function Invoke-RestartCase($method, $path, $body, $token, $key) {
        $request = @{ Uri = $baseUrl + $path; Method = $method; UseBasicParsing = $true }
        if ($null -ne $body) {
            $request.ContentType = 'application/json'
            $request.Body = $body | ConvertTo-Json -Compress -Depth 8
        }
        $headers = @{}
        if ($token) { $headers.Authorization = 'Bearer ' + $token }
        if ($key) { $headers.'Idempotency-Key' = $key }
        if ($headers.Count -gt 0) { $request.Headers = $headers }
        $response = Invoke-WebRequest @request
        if ($response.Content) { return $response.Content | ConvertFrom-Json }
        return $null
    }
    $restartSuffix = [guid]::NewGuid().ToString('N').Substring(0, 8)
    $sender = Invoke-RestartCase 'POST' '/auth/signup' @{ loginId = 'rs_a_' + $restartSuffix; password = 'test-password-123' } $null $null
    $recipient = Invoke-RestartCase 'POST' '/auth/signup' @{ loginId = 'rs_b_' + $restartSuffix; password = 'test-password-123' } $null $null
    $senderId = $sender.user.id
    $recipientId = $recipient.user.id
    # Temporary PostgreSQL fixture: there is no public age-verification API yet.
    $fixtureSql = "UPDATE user_settings SET anonymous_enabled=false; UPDATE app_user SET adult_status='VERIFIED_19_PLUS',adult_checked_at=CURRENT_TIMESTAMP WHERE id IN ('$senderId','$recipientId')"
    docker exec $containerName psql -U emori -d emori -Atqc $fixtureSql | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not prepare restart test fixtures.' }
    Invoke-RestartCase 'PATCH' '/me/settings' @{ anonymousEnabled = $true } $sender.session.accessToken $null | Out-Null
    Invoke-RestartCase 'PATCH' '/me/settings' @{ anonymousEnabled = $true } $recipient.session.accessToken $null | Out-Null
    $draft = Invoke-RestartCase 'POST' '/orbs' @{ emotionIds = @('purple_01') } $sender.session.accessToken ([guid]::NewGuid().ToString())
    $sent = Invoke-RestartCase 'POST' ('/orbs/' + $draft.orb.id + '/dispatches') @{ route = 'ANONYMOUS' } $sender.session.accessToken ([guid]::NewGuid().ToString())
    if ($sent.dispatch.recipientCount -ne 1) { throw 'Restart test expected one anonymous recipient.' }
    $inbox = Invoke-RestartCase 'GET' '/inbox' $null $recipient.session.accessToken $null
    $receiptId = $inbox.items[0].id
    $openKey = [guid]::NewGuid().ToString()
    $openPath = '/receipts/' + $receiptId + '/open'
    $firstOpen = Invoke-RestartCase 'POST' $openPath @{} $recipient.session.accessToken $openKey
    if ($firstOpen.mode -ne 'FIRST' -or -not $firstOpen.actionToken) { throw 'Anonymous first open did not return an action token.' }
    $encrypted = docker exec $containerName psql -U emori -d emori -Atqc `
        "SELECT response_json LIKE 'enc:v1:%' FROM idempotency_request WHERE user_id='$recipientId' AND request_key='$openKey'"
    if ($LASTEXITCODE -ne 0 -or $encrypted -ne 't') { throw 'Encrypted idempotent response was not persisted.' }

    Stop-Process -Id $appProcess.Id -Force
    $appProcess.WaitForExit()
    $appProcess = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jar.FullName,
        '--spring.profiles.active=postgres', "--server.port=$appPort") `
        -WorkingDirectory $project -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $restarted = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        if ($appProcess.HasExited) { throw 'Application exited after restart.' }
        try {
            $socket = [System.Net.Sockets.TcpClient]::new()
            $socket.Connect('127.0.0.1', $appPort)
            $socket.Dispose()
            $restarted = $true
            break
        } catch { Start-Sleep -Seconds 1 }
    }
    if (-not $restarted) { throw 'Application did not become ready after restart.' }
    $replayedOpen = Invoke-RestartCase 'POST' $openPath @{} $recipient.session.accessToken $openKey
    if ($replayedOpen.mode -ne 'FIRST' -or $replayedOpen.actionToken -ne $firstOpen.actionToken) {
        throw 'Encrypted idempotent response changed after server restart.'
    }
    Write-Host 'Encrypted idempotency response after server restart: PASS'

    if ($IncludeStage6) {
        Stop-Process -Id $appProcess.Id -Force
        $appProcess.WaitForExit()
        $env:EMORI_ASMR_TEST_GENERATOR = 'true'
        $env:EMORI_ASMR_WORKER_ENABLED = 'false'
        $env:EMORI_ASMR_STORAGE_ROOT = Join-Path $project "build/stage6-restart-audio-$suffix"
        $asmrStdout = Join-Path $project "build/stage6-restart-$suffix.out.log"
        $asmrStderr = Join-Path $project "build/stage6-restart-$suffix.err.log"
        $appProcess = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jar.FullName,
            '--spring.profiles.active=asmr-test', "--server.port=$appPort") `
            -WorkingDirectory $project -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $asmrStdout -RedirectStandardError $asmrStderr
        for ($attempt = 0; $attempt -lt 90; $attempt++) {
            if ($appProcess.HasExited) { throw "ASMR fixture application exited. See $asmrStderr" }
            try { $socket = [System.Net.Sockets.TcpClient]::new(); $socket.Connect('127.0.0.1', $appPort); $socket.Dispose(); break }
            catch { Start-Sleep -Seconds 1 }
        }
        $asmrUser = Invoke-RestartCase 'POST' '/auth/signup' @{ loginId = 'asmr_' + $restartSuffix; password = 'test-password-123' } $null $null
        $asmrDraft = Invoke-RestartCase 'POST' '/orbs' @{ emotionIds = @('yellow_01') } $asmrUser.session.accessToken ([guid]::NewGuid().ToString())
        $asmrGeneration = Invoke-RestartCase 'POST' ('/orbs/' + $asmrDraft.orb.id + '/asmr-generations') @{} $asmrUser.session.accessToken ([guid]::NewGuid().ToString())
        if ($asmrGeneration.status -ne 'QUEUED') { throw 'ASMR task was not queued before restart.' }
        Stop-Process -Id $appProcess.Id -Force
        $appProcess.WaitForExit()
        $env:EMORI_ASMR_WORKER_ENABLED = 'true'
        $appProcess = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jar.FullName,
            '--spring.profiles.active=asmr-test', "--server.port=$appPort") `
            -WorkingDirectory $project -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $asmrStdout -RedirectStandardError $asmrStderr
        $asmrReady = $false
        for ($attempt = 0; $attempt -lt 90; $attempt++) {
            if ($appProcess.HasExited) { throw "ASMR worker application exited. See $asmrStderr" }
            try {
                $state = Invoke-RestartCase 'GET' ('/asmr-generations/' + $asmrGeneration.id) $null $asmrUser.session.accessToken $null
                if ($state.status -eq 'READY') { $asmrReady = $true; break }
                if ($state.status -eq 'FAILED' -or $state.status -eq 'PARTIAL_FAILED') { throw 'ASMR fixture generation failed after restart.' }
            } catch [System.Net.WebException] { }
            Start-Sleep -Seconds 1
        }
        if (-not $asmrReady -or $state.tracks.Count -ne 3) { throw 'ASMR queued tracks did not recover after restart.' }
        $playback = Invoke-WebRequest -Uri ($baseUrl + '/asmr-tracks/' + $state.tracks[0].id + '/audio') `
            -Headers @{ Authorization = 'Bearer ' + $asmrUser.session.accessToken } -UseBasicParsing
        if ($playback.StatusCode -ne 200 -or $playback.RawContentLength -le 44) { throw 'ASMR audio was not readable after restart.' }
        Write-Host 'ASMR queued task after server restart: PASS'
    }

    $versions = docker exec $containerName psql -U emori -d emori -Atqc `
        'SELECT version FROM flyway_schema_history WHERE success=true ORDER BY installed_rank'
    if ($LASTEXITCODE -ne 0 -or (@($versions) -join ',') -ne '1,2,2.1,3,4,5,6') {
        throw ('Unexpected Flyway history: ' + (@($versions) -join ', '))
    }
    $constraint = docker exec $containerName psql -U emori -d emori -Atqc `
        "SELECT conname FROM pg_constraint WHERE conname='app_user_login_format'"
    if ($LASTEXITCODE -ne 0 -or $constraint -ne 'app_user_login_format') { throw 'Login ID PostgreSQL constraint is missing.' }
    $indexes = docker exec $containerName psql -U emori -d emori -Atqc `
        "SELECT indexname FROM pg_indexes WHERE indexname IN ('friend_request_pending_pair_idx','emotion_record_own_dispatch_idx') ORDER BY indexname"
    if ($LASTEXITCODE -ne 0 -or @($indexes).Count -ne 2) { throw 'Stage 2 PostgreSQL indexes are missing.' }
    $stage3Indexes = docker exec $containerName psql -U emori -d emori -Atqc `
        "SELECT indexname FROM pg_indexes WHERE indexname IN ('receipt_unread_lookup_idx','emotion_record_owner_side_idx','emotion_record_dispatch_idx') ORDER BY indexname"
    if ($LASTEXITCODE -ne 0 -or @($stage3Indexes).Count -ne 3) { throw 'Stage 3 PostgreSQL indexes are missing.' }
    $stage4Tables = docker exec $containerName psql -U emori -d emori -Atqc `
        "SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename IN ('anon_daily_usage','anonymous_block','receipt_reaction','receipt_report') ORDER BY tablename"
    if ($LASTEXITCODE -ne 0 -or @($stage4Tables).Count -ne 4) { throw 'Stage 4 PostgreSQL tables are missing.' }
    $stage5Tables = docker exec $containerName psql -U emori -d emori -Atqc `
        "SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename IN ('care_notice','care_resource','device_registration','outbox_event') ORDER BY tablename"
    if ($LASTEXITCODE -ne 0 -or @($stage5Tables).Count -ne 4) { throw 'Stage 5 PostgreSQL tables are missing.' }
    $stage6Tables = docker exec $containerName psql -U emori -d emori -Atqc `
        "SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename IN ('asmr_generation','asmr_track','asmr_outbox_event') ORDER BY tablename"
    if ($LASTEXITCODE -ne 0 -or @($stage6Tables).Count -ne 3) { throw 'Stage 6 PostgreSQL tables are missing.' }
    Write-Host ('Flyway versions: ' + (@($versions) -join ', '))
    Write-Host 'PostgreSQL stage 2-6 schema: OK'
    Write-Host 'PostgreSQL HTTP verification: PASS'
} finally {
    if ($appProcess -and -not $appProcess.HasExited) { Stop-Process -Id $appProcess.Id -Force }
    if ($containerStarted) { docker stop $containerName | Out-Null }
    $env:DB_URL = $oldDbUrl
    $env:DB_USERNAME = $oldDbUsername
    $env:DB_PASSWORD = $oldDbPassword
    $env:EMORI_POSTGRES_TEST = $oldPgTest
    $env:EMORI_STAGE3_TEST_DB_URL = $oldStage3Url
    $env:SPRING_PROFILES_ACTIVE = $oldSpringProfile
    $env:EMORI_STAGE4_TEST_DB_URL = $oldStage4Url
    $env:EMORI_STAGE4_MIDNIGHT_DB_URL = $oldMidnightUrl
    $env:EMORI_STAGE5_TEST_DB_URL = $oldStage5Url
    $env:EMORI_STAGE6_TEST_DB_URL = $oldStage6Url
    $env:EMORI_STAGE7_TEST_DB_URL = $oldStage7Url
    $env:SPRING_FLYWAY_LOCATIONS = $oldFlywayLocations
    $env:EMORI_AUTH_TEST_DB_URL = $oldAuthTestUrl
    $env:EMORI_STAGE2_TEST_DB_URL = $oldStage2TestUrl
    $env:EMORI_CONTEXT_TEST_DB_URL = $oldContextTestUrl
    $env:EMORI_ASMR_TEST_GENERATOR = $oldAsmrFixture
    $env:EMORI_ASMR_WORKER_ENABLED = $oldAsmrWorker
    $env:EMORI_ASMR_STORAGE_ROOT = $oldAsmrStorage
    $env:EMORI_IDEMPOTENCY_SECRET = $oldIdempotencySecret
    $env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = $oldHikariPoolSize
    Pop-Location
}
