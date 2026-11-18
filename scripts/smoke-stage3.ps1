param([string]$BaseUrl = 'http://127.0.0.1:8080/api/v1')
$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 8)
function Invoke-Case($name, $method, $path, $body, $token, $key, $expected) {
    $args = @{ Uri=$base + $path; Method=$method; UseBasicParsing=$true }
    if ($null -ne $body) { $args.ContentType='application/json'; $args.Body=($body | ConvertTo-Json -Compress -Depth 8) }
    $headers = @{}
    if ($token) { $headers.Authorization='Bearer ' + $token }
    if ($key) { $headers.'Idempotency-Key'=$key }
    if ($headers.Count -gt 0) { $args.Headers=$headers }
    try {
        $response=Invoke-WebRequest @args
        $status=[int]$response.StatusCode
        $data=if($response.Content){$response.Content | ConvertFrom-Json}else{$null}
    } catch {
        $status=[int]$_.Exception.Response.StatusCode
        $data=$null
    }
    if ($status -ne $expected) { throw "$name expected $expected, got $status" }
    Write-Host "$name=$status"
    return $data
}
$a=Invoke-Case 'signup-a' 'POST' '/auth/signup' @{loginId=('s3a_'+$suffix);password='test-password-123'} $null $null 201
$b=Invoke-Case 'signup-b' 'POST' '/auth/signup' @{loginId=('s3b_'+$suffix);password='test-password-123'} $null $null 201
$at=$a.session.accessToken; $bt=$b.session.accessToken
$request=Invoke-Case 'friend-request' 'POST' '/friend-requests' @{friendCode=$b.user.friendCode} $at $null 201
Invoke-Case 'friend-accept' 'PUT' ('/friend-requests/'+$request.id+'/decision') @{decision='ACCEPT'} $bt $null 200 | Out-Null
function Send-Friend {
    $orb=Invoke-Case 'draft' 'POST' '/orbs' @{emotionIds=@('yellow_01')} $at ([guid]::NewGuid().ToString()) 201
    return Invoke-Case 'dispatch' 'POST' ('/orbs/'+$orb.orb.id+'/dispatches') @{route='FRIEND';friendId=$b.user.id} $at ([guid]::NewGuid().ToString()) 201
}
$sent=Send-Friend
$inbox=Invoke-Case 'inbox' 'GET' '/inbox?unreadOnly=true' $null $bt $null 200
if ($inbox.items.Count -ne 1 -or $inbox.items[0].openedAt) { throw 'Inbox read marked receipt opened' }
$receipt=$inbox.items[0].id
Invoke-Case 'replay-before-open' 'GET' ('/receipts/'+$receipt+'/replay') $null $bt $null 409 | Out-Null
$key=[guid]::NewGuid().ToString()
$open=Invoke-Case 'first-open' 'POST' ('/receipts/'+$receipt+'/open') @{} $bt $key 200
if ($open.mode -ne 'FIRST' -or $open.actions.Count -ne 0) { throw 'First open mismatch' }
$retry=Invoke-Case 'first-open-retry' 'POST' ('/receipts/'+$receipt+'/open') @{} $bt $key 200
if ($retry.mode -ne 'FIRST') { throw 'Idempotency mismatch' }
$replay=Invoke-Case 'replay' 'GET' ('/receipts/'+$receipt+'/replay') $null $bt $null 200
if ($replay.mode -ne 'REPLAY') { throw 'Replay mismatch' }
Invoke-Case 'friend-reaction-denied' 'POST' ('/receipts/'+$receipt+'/reaction') @{actionToken='x';code='HEART'} $bt ([guid]::NewGuid().ToString()) 403 | Out-Null
Invoke-Case 'open-cancel-denied' 'POST' ('/dispatches/'+$sent.dispatch.id+'/targets/'+$sent.dispatch.targets[0].id+'/cancel') @{} $at ([guid]::NewGuid().ToString()) 409 | Out-Null
Invoke-Case 'delete-own-record' 'DELETE' ('/records/'+$sent.recordId) $null $at $null 204 | Out-Null
Invoke-Case 'received-records' 'GET' '/records?side=RECEIVED' $null $bt $null 200 | Out-Null
$later=Send-Friend
$cancel=Invoke-Case 'cancel-unopened' 'POST' ('/dispatches/'+$later.dispatch.id+'/targets/'+$later.dispatch.targets[0].id+'/cancel') @{} $at ([guid]::NewGuid().ToString()) 200
if ($cancel.dispatch.status -ne 'CANCELLED') { throw 'Cancel state mismatch' }
$remaining=Invoke-Case 'inbox-after-cancel' 'GET' '/inbox?unreadOnly=true' $null $bt $null 200
if ($remaining.items.Count -ne 0) { throw 'Cancelled receipt remained in inbox' }
Write-Host 'Stage 3 HTTP verification: PASS'
