$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$branch = 'feat/emori-backend-20261116-2a6d'
$repository = 'https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_21_BE.git'

function Assert-GitSuccess([string]$step) {
    if ($LASTEXITCODE -ne 0) { throw "$step failed; inspect the Git output above." }
}

function Assert-SafeIndex {
    $paths = @(git diff --cached --name-only)
    Assert-GitSuccess 'Reading staged paths'
    if ($paths.Count -eq 0) { throw 'Nothing is staged for this commit.' }
    foreach ($path in $paths) {
        if ($path -match '(^|/)(\.env(?:\.|$)|data/|build/|\.gradle/|\.idea/|secrets/)' -or
            $path -match '(\.mv\.db|\.trace\.db|\.log)$') {
            throw "Sensitive or generated path is staged: $path"
        }
    }
    git grep --cached -I -E -q '(ghp_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[0-9A-Z]{16}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----)'
    if ($LASTEXITCODE -eq 0) { throw 'A staged file resembles a credential; no commit was made.' }
    if ($LASTEXITCODE -ne 1) { throw 'Could not complete the staged credential scan.' }
    # The supplied PRD and API documents contain intentional original whitespace.
    # Keep their bytes intact; the path and credential checks above guard publication.
    Write-Host ('Staged files: ' + $paths.Count)
}

Push-Location $project
try {
    if ((git branch --show-current) -ne $branch) { throw "Expected work branch $branch." }
    $origin = git remote get-url origin 2>$null
    if ($LASTEXITCODE -ne 0) {
        git remote add origin $repository
        Assert-GitSuccess 'Adding origin'
    } elseif ($origin -ne $repository) {
        throw "origin points to a different repository: $origin"
    }
    if (-not (git config user.name) -or -not (git config user.email)) {
        throw 'Configure Git user.name and user.email before committing.'
    }

    $baseline = @(
        '.gitattributes', '.gitignore', 'build.gradle', 'settings.gradle',
        'gradlew', 'gradlew.bat', 'gradle/wrapper',
        'docs/PRD.md', 'docs/API.md', 'docs/DATABASE.md',
        'src/main/java/com/ganzi/emori/EmOriApplication.java',
        'src/main/java/com/ganzi/emori/api/ApiException.java',
        'src/main/java/com/ganzi/emori/api/ApiErrors.java',
        'src/main/java/com/ganzi/emori/auth',
        'src/main/resources/application.yaml',
        'src/main/resources/db/migration/V1__accounts_and_sessions.sql',
        'src/test/java/com/ganzi/emori/AuthHttpTests.java',
        'src/test/java/com/ganzi/emori/EmOriApplicationTests.java',
        'scripts/smoke-auth.ps1'
    )
    git add -- $baseline
    Assert-GitSuccess 'Staging authentication baseline'
    Assert-SafeIndex
    git commit -m 'feat: implement authentication'
    Assert-GitSuccess 'Authentication commit'
    Write-Host ('Authentication commit: ' + (git rev-parse HEAD))

    $stage2 = @(
        'README.md', 'scripts/smoke-stage2.ps1',
        'scripts/verify-stage2-postgres.ps1', 'scripts/publish-stage2.ps1',
        'src/main/java/com/ganzi/emori/api/DbSupport.java',
        'src/main/java/com/ganzi/emori/api/Idempotency.java',
        'src/main/java/com/ganzi/emori/friend',
        'src/main/java/com/ganzi/emori/orb',
        'src/main/resources/db/migration/V2__friends_orbs_dispatches.sql',
        'src/main/resources/db/postgres/V2_1__login_id_format.sql',
        'src/test/java/com/ganzi/emori/Stage2HttpTests.java',
        'src/test/java/com/ganzi/emori/Stage2PostgresRaceTests.java'
    )
    git add -- $stage2
    Assert-GitSuccess 'Staging stage 2'
    Assert-SafeIndex
    git commit -m 'feat: implement friends and emotion dispatch'
    Assert-GitSuccess 'Stage 2 commit'
    Write-Host ('Stage 2 commit: ' + (git rev-parse HEAD))

    git push --set-upstream origin $branch
    if ($LASTEXITCODE -ne 0) {
        Write-Error 'Push failed. Local commits are preserved. After resolving credentials or network access, retry: git push --set-upstream origin feat/emori-backend-20261116-2a6d'
    }
    Write-Host "Push succeeded: origin/$branch"
    git status --short
} finally {
    Pop-Location
}
