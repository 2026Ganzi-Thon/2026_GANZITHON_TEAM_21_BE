param([string]$BaseUrl = 'http://127.0.0.1:8080/api/v1')
$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 7)
$alice = 'stagea_' + $suffix
$bob = 'stageb_' + $suffix

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

$a=Invoke-Case 'signup-a' 'POST' '/auth/signup' @{loginId=$alice;password='test-password-123'} $null $null 201
$b=Invoke-Case 'signup-b' 'POST' '/auth/signup' @{loginId=$bob;password='test-password-456'} $null $null 201
$qr='ganzithon21://friend/' + $b.user.friendCode
$friendCode=$qr.Substring($qr.LastIndexOf('/')+1)
$request=Invoke-Case 'friend-request' 'POST' '/friend-requests' @{friendCode=$friendCode} $a.session.accessToken $null 201
Invoke-Case 'reverse-duplicate' 'POST' '/friend-requests' @{friendCode=$alice} $b.session.accessToken $null 409 | Out-Null
$decision=Invoke-Case 'friend-accept' 'PUT' ('/friend-requests/'+$request.id+'/decision') @{decision='ACCEPT'} $b.session.accessToken $null 200
if ($decision.status -ne 'ACCEPTED') { throw 'friend decision not accepted' }
$friends=Invoke-Case 'friends' 'GET' '/friends' $null $a.session.accessToken $null 200
if ($friends.items.Count -ne 1) { throw 'friend count mismatch' }

$singleKey=[guid]::NewGuid().ToString()
$singleBody=@{emotionIds=@('yellow_03')}
$single=Invoke-Case 'single-draft' 'POST' '/orbs' $singleBody $a.session.accessToken $singleKey 201
if ($single.recordId -ne $null -or $single.orb.emotions[0].id -ne 'yellow_03') { throw 'single draft mismatch' }
$singleReplay=Invoke-Case 'single-retry' 'POST' '/orbs' $singleBody $a.session.accessToken $singleKey 201
if ($singleReplay.orb.id -ne $single.orb.id) { throw 'idempotent draft changed' }
$singlePath='/orbs/'+$single.orb.id+'/dispatches'
$selfKey=[guid]::NewGuid().ToString()
$saved=Invoke-Case 'self-save' 'POST' $singlePath @{route='SELF'} $a.session.accessToken $selfKey 201
if ($saved.dispatch.status -ne 'SAVED' -or !$saved.recordId) { throw 'self save mismatch' }
$selfReplay=Invoke-Case 'self-retry' 'POST' $singlePath @{route='SELF'} $a.session.accessToken $selfKey 201
if ($selfReplay.recordId -ne $saved.recordId) { throw 'idempotent record changed' }
Invoke-Case 'other-key-duplicate' 'POST' $singlePath @{route='SELF'} $a.session.accessToken ([guid]::NewGuid().ToString()) 409 | Out-Null

$mixed=Invoke-Case 'mixed-draft' 'POST' '/orbs' @{emotionIds=@('blue_03','yellow_01')} $a.session.accessToken ([guid]::NewGuid().ToString()) 201
if ($mixed.orb.emotions.Count -ne 2 -or $mixed.orb.emotions[0].id -ne 'blue_03') { throw 'mixed emotions mismatch' }
$friendPath='/orbs/'+$mixed.orb.id+'/dispatches'
$friendBody=@{route='FRIEND';friendId=$b.user.id}
$sendKey=[guid]::NewGuid().ToString()
$sent=Invoke-Case 'friend-dispatch' 'POST' $friendPath $friendBody $a.session.accessToken $sendKey 201
if ($sent.dispatch.recipientCount -ne 1 -or $sent.dispatch.targets[0].counterpart.id -ne $b.user.id) { throw 'friend dispatch mismatch' }
$sentReplay=Invoke-Case 'friend-retry' 'POST' $friendPath $friendBody $a.session.accessToken $sendKey 201
if ($sentReplay.recordId -ne $sent.recordId) { throw 'friend record changed' }
Invoke-Case 'delete-friend' 'DELETE' ('/friends/'+$b.user.id) $null $a.session.accessToken $null 204 | Out-Null
$history=Invoke-Case 'dispatch-after-delete' 'GET' ('/dispatches/'+$sent.dispatch.id) $null $a.session.accessToken $null 200
if ($history.id -ne $sent.dispatch.id) { throw 'past dispatch missing' }
$later=Invoke-Case 'later-draft' 'POST' '/orbs' @{emotionIds=@('green_01')} $a.session.accessToken ([guid]::NewGuid().ToString()) 201
Invoke-Case 'send-after-delete' 'POST' ('/orbs/'+$later.orb.id+'/dispatches') $friendBody $a.session.accessToken ([guid]::NewGuid().ToString()) 409 | Out-Null
