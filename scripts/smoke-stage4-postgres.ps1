param([string]$BaseUrl = 'http://127.0.0.1:8080/api/v1', [Parameter(Mandatory=$true)][string]$ContainerName)
$ErrorActionPreference='Stop'
$base=$BaseUrl.TrimEnd('/')
$suffix=[guid]::NewGuid().ToString('N').Substring(0,8)
function Invoke-Case($name,$method,$path,$body,$token,$key,$expected) {
    $args=@{Uri=$base+$path;Method=$method;UseBasicParsing=$true}
    if($null -ne $body){$args.ContentType='application/json';$args.Body=($body|ConvertTo-Json -Compress -Depth 8)}
    $headers=@{}
    if($token){$headers.Authorization='Bearer '+$token}
    if($key){$headers.'Idempotency-Key'=$key}
    if($headers.Count -gt 0){$args.Headers=$headers}
    try {$r=Invoke-WebRequest @args;$status=[int]$r.StatusCode;$data=if($r.Content){$r.Content|ConvertFrom-Json}else{$null}}
    catch {$status=[int]$_.Exception.Response.StatusCode;$data=$null}
    if($status -ne $expected){throw "$name expected $expected, got $status"}
    Write-Host "$name=$status"
    return $data
}
$users=@()
foreach($prefix in @('a','b','c','d')) {
    $login='s4'+$prefix+'_'+$suffix
    $users+=Invoke-Case 'signup' 'POST' '/auth/signup' @{loginId=$login;password='test-password-123'} $null $null 201
}
$a=$users[0];$b=$users[1];$c=$users[2];$d=$users[3]
$at=$a.session.accessToken;$bt=$b.session.accessToken
Invoke-Case 'unknown-age' 'PATCH' '/me/settings' @{anonymousEnabled=$true} $at $null 403|Out-Null
# Test fixture only: no age-verification endpoint exists. Promote only these temporary accounts in this container.
$ids=@($a.user.id,$b.user.id,$c.user.id,$d.user.id)
$quoted=($ids|ForEach-Object {"'$_'"}) -join ','
$sql="UPDATE app_user SET adult_status='VERIFIED_19_PLUS',adult_checked_at=CURRENT_TIMESTAMP WHERE id IN ($quoted)"
docker exec $ContainerName psql -U emori -d emori -Atqc $sql | Out-Null
if($LASTEXITCODE -ne 0){throw 'Could not prepare adult test fixtures'}
foreach($user in $users){Invoke-Case 'anonymous-on' 'PATCH' '/me/settings' @{anonymousEnabled=$true} $user.session.accessToken $null 200|Out-Null}
$orb=Invoke-Case 'draft' 'POST' '/orbs' @{emotionIds=@('purple_01')} $at ([guid]::NewGuid().ToString()) 201
$sent=Invoke-Case 'anonymous-dispatch' 'POST' ('/orbs/'+$orb.orb.id+'/dispatches') @{route='ANONYMOUS'} $at ([guid]::NewGuid().ToString()) 201
if($sent.dispatch.recipientCount -ne 3){throw 'Expected three anonymous recipients'}
foreach($target in $sent.dispatch.targets){if($target.counterpart){throw 'Anonymous target identity leaked'}}
$response=$sent|ConvertTo-Json -Compress -Depth 8
foreach($id in $ids[1..3]){if($response.Contains($id)){throw 'Anonymous recipient ID leaked'}}
$inbox=Invoke-Case 'anonymous-inbox' 'GET' '/inbox' $null $bt $null 200
if($inbox.items[0].sender){throw 'Anonymous sender identity leaked'}
$receipt=$inbox.items[0].id
$opened=Invoke-Case 'anonymous-open' 'POST' ('/receipts/'+$receipt+'/open') @{} $bt ([guid]::NewGuid().ToString()) 200
if($opened.mode -ne 'FIRST' -or $opened.actions.Count -ne 2 -or !$opened.actionToken){throw 'Anonymous first view mismatch'}
Invoke-Case 'reaction' 'POST' ('/receipts/'+$receipt+'/reaction') @{actionToken=$opened.actionToken;code='HEART'} $bt ([guid]::NewGuid().ToString()) 201|Out-Null
Invoke-Case 'reaction-again' 'POST' ('/receipts/'+$receipt+'/reaction') @{actionToken=$opened.actionToken;code='HEART'} $bt ([guid]::NewGuid().ToString()) 409|Out-Null
Invoke-Case 'report' 'POST' ('/receipts/'+$receipt+'/reports') @{reasonCode='OTHER';detail='test report'} $bt ([guid]::NewGuid().ToString()) 201|Out-Null
Invoke-Case 'block' 'PUT' ('/receipts/'+$receipt+'/anonymous-block') @{} $bt $null 204|Out-Null
$current=Invoke-Case 'dispatch-state' 'GET' ('/dispatches/'+$sent.dispatch.id) $null $at $null 200
$targetId=($current.targets|Where-Object {$_.canCancel}|Select-Object -First 1).id
if(!$targetId){throw 'No unopened anonymous target available'}
$cancel=Invoke-Case 'individual-cancel' 'POST' ('/dispatches/'+$sent.dispatch.id+'/targets/'+$targetId+'/cancel') @{} $at ([guid]::NewGuid().ToString()) 200
if($cancel.dispatch.status -ne 'PARTIALLY_CANCELLED'){throw 'Partial cancel status mismatch'}
Write-Host 'Stage 4 PostgreSQL HTTP verification: PASS'
