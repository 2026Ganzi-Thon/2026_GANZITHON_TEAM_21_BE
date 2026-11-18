param([string]$Image = 'postgres:18-alpine')

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 10)
$containerName = 'emori-stage4-' + $suffix
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
$oldIdempotencySecret = $env:EMORI_IDEMPOTENCY_SECRET

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

    $stdout = Join-Path $project "build/stage4-postgres-$suffix.out.log"
    $stderr = Join-Path $project "build/stage4-postgres-$suffix.err.log"
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
    & .\gradlew.bat test --tests 'com.ganzi.emori.Stage2PostgresRaceTests'
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL concurrency test failed.' }
    $env:EMORI_STAGE3_TEST_DB_URL = $env:DB_URL
    $env:SPRING_PROFILES_ACTIVE = 'postgres'
    & .\gradlew.bat test --tests 'com.ganzi.emori.Stage3HttpTests'
    if ($LASTEXITCODE -ne 0) { throw 'Stage 3 PostgreSQL HTTP and concurrency tests failed.' }
    $env:EMORI_STAGE4_TEST_DB_URL = $env:DB_URL
    $env:EMORI_STAGE4_MIDNIGHT_DB_URL = $env:DB_URL
    & .\gradlew.bat test --tests 'com.ganzi.emori.Stage4HttpTests' --tests 'com.ganzi.emori.Stage4MidnightTests'
    if ($LASTEXITCODE -ne 0) { throw 'Stage 4 PostgreSQL HTTP and concurrency tests failed.' }

    $versions = docker exec $containerName psql -U emori -d emori -Atqc `
        'SELECT version FROM flyway_schema_history WHERE success=true ORDER BY installed_rank'
    if ($LASTEXITCODE -ne 0 -or (@($versions) -join ',') -ne '1,2,2.1,3,4') {
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
    Write-Host ('Flyway versions: ' + (@($versions) -join ', '))
    Write-Host 'PostgreSQL stage 2/3 indexes and stage 4 tables: OK'
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
    $env:EMORI_IDEMPOTENCY_SECRET = $oldIdempotencySecret
    Pop-Location
}
