param([string]$BaseUrl = 'http://127.0.0.1:8080/api/v1')
$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')
$suffix = [guid]::NewGuid().ToString('N').Substring(0,8)
$alice = 'livea_' + $suffix
$bob = 'liveb_' + $suffix

function Invoke-Case($name, $method, $path, $body, $token, $expected) {
    $args = @{ Uri = $base + $path; Method = $method; UseBasicParsing = $true }
    if ($null -ne $body) { $args.ContentType = 'application/json'; $args.Body = ($body | ConvertTo-Json -Compress) }
    if ($token) { $args.Headers = @{ Authorization = 'Bearer ' + $token } }
    try {
        $response = Invoke-WebRequest @args
        $status = [int]$response.StatusCode
        $data = if ($response.Content) { $response.Content | ConvertFrom-Json } else { $null }
    } catch {
        $status = [int]$_.Exception.Response.StatusCode
        $data = $null
    }
    if ($status -ne $expected) { throw "$name expected $expected, got $status" }
    Write-Host "$name=$status"
    return $data
}

$a = Invoke-Case 'signup-a' 'POST' '/auth/signup' @{ loginId=$alice; password='test-password-123' } $null 201
$b = Invoke-Case 'signup-b' 'POST' '/auth/signup' @{ loginId=$bob; password='test-password-456' } $null 201
Invoke-Case 'me' 'GET' '/me' $null $a.session.accessToken 200 | Out-Null
Invoke-Case 'duplicate' 'POST' '/auth/signup' @{ loginId=$alice.ToUpperInvariant(); password='test-password-123' } $null 409 | Out-Null
Invoke-Case 'bad-input' 'POST' '/auth/signup' @{ loginId='bad!'; password='short' } $null 422 | Out-Null
Invoke-Case 'bad-login' 'POST' '/auth/login' @{ loginId=$bob; password='wrong-password' } $null 401 | Out-Null
$login = Invoke-Case 'login' 'POST' '/auth/login' @{ loginId=$bob; password='test-password-456' } $null 200
$rotated = Invoke-Case 'refresh' 'POST' '/auth/refresh' @{ refreshToken=$login.session.refreshToken } $null 200
Invoke-Case 'old-access' 'GET' '/me' $null $login.session.accessToken 401 | Out-Null
Invoke-Case 'old-refresh' 'POST' '/auth/refresh' @{ refreshToken=$login.session.refreshToken } $null 401 | Out-Null
Invoke-Case 'logout' 'POST' '/auth/logout' @{} $rotated.accessToken 204 | Out-Null
Invoke-Case 'logged-out' 'GET' '/me' $null $rotated.accessToken 401 | Out-Null
Invoke-Case 'revoked-refresh' 'POST' '/auth/refresh' @{ refreshToken=$rotated.refreshToken } $null 401 | Out-Null
Invoke-Case 'unknown-field' 'POST' '/auth/signup' @{ loginId='validname'; password='test-password-123'; extra=1 } $null 422 | Out-Null
Invoke-Case 'missing-field' 'POST' '/auth/signup' @{ loginId='validname' } $null 422 | Out-Null
try {
    Invoke-WebRequest -Uri ($base+'/auth/signup') -Method Post -Body '{bad json' -ContentType 'application/json' -UseBasicParsing | Out-Null
    throw 'malformed expected 400'
} catch {
    if ([int]$_.Exception.Response.StatusCode -ne 400) { throw }
    Write-Host 'malformed=400'
}
try {
    Invoke-WebRequest -Uri ($base+'/me') -Method Get -Headers @{ Registration='Bearer bad' } -UseBasicParsing | Out-Null
    throw 'wrong-header expected 401'
} catch {
    if ([int]$_.Exception.Response.StatusCode -ne 401) { throw }
    Write-Host 'wrong-header=401'
}
