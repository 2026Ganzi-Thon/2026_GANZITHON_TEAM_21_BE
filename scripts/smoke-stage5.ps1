param([string]$BaseUrl='http://127.0.0.1:8080/api/v1')
$ErrorActionPreference='Stop'
$base=$BaseUrl.TrimEnd('/')
$suffix=[guid]::NewGuid().ToString('N').Substring(0,8)
function Invoke-Case($name,$method,$path,$body,$token,$key,$expected){
    $args=@{Uri=$base+$path;Method=$method;UseBasicParsing=$true}
    if($null -ne $body){$args.ContentType='application/json';$args.Body=($body|ConvertTo-Json -Compress -Depth 8)}
    $headers=@{}
    if($token){$headers.Authorization='Bearer '+$token}
    if($key){$headers.'Idempotency-Key'=$key}
    if($headers.Count -gt 0){$args.Headers=$headers}
    try{$r=Invoke-WebRequest @args;$status=[int]$r.StatusCode;$data=if($r.Content){$r.Content|ConvertFrom-Json}else{$null}}
    catch{$status=[int]$_.Exception.Response.StatusCode;$data=$null}
    if($status -ne $expected){throw "$name expected $expected, got $status"}
    Write-Host "$name=$status"
    return $data
}
$a=Invoke-Case 'signup-a' 'POST' '/auth/signup' @{loginId=('s5a_'+$suffix);password='test-password-123'} $null $null 201
$b=Invoke-Case 'signup-b' 'POST' '/auth/signup' @{loginId=('s5b_'+$suffix);password='test-password-123'} $null $null 201
$at=$a.session.accessToken;$bt=$b.session.accessToken
$installation=[guid]::NewGuid().ToString()
Invoke-Case 'device-register' 'PUT' ('/me/devices/'+$installation) @{platform='ANDROID';deliveryToken='ExponentPushToken[abcdefghijklmnopqrst]'} $bt $null 204|Out-Null
$rest=Invoke-Case 'rest-manual' 'PUT' '/me/rest' @{mode='MANUAL'} $bt $null 200
if(!$rest.active){throw 'Manual rest must be active'}
$request=Invoke-Case 'friend-request' 'POST' '/friend-requests' @{friendCode=$b.user.friendCode} $at $null 201
Invoke-Case 'friend-accept' 'PUT' ('/friend-requests/'+$request.id+'/decision') @{decision='ACCEPT'} $bt $null 200|Out-Null
$orb=Invoke-Case 'draft' 'POST' '/orbs' @{emotionIds=@('purple_01')} $at ([guid]::NewGuid().ToString()) 201
Invoke-Case 'friend-dispatch-during-rest' 'POST' ('/orbs/'+$orb.orb.id+'/dispatches') @{route='FRIEND';friendId=$b.user.id} $at ([guid]::NewGuid().ToString()) 201|Out-Null
$inbox=Invoke-Case 'inbox-during-rest' 'GET' '/inbox' $null $bt $null 200
if($inbox.items.Count -ne 1){throw 'Resting recipient did not retain receipt'}
$care=Invoke-Case 'care-unqualified' 'GET' '/me/care' $null $at $null 200
if($care.level -ne 0 -or $care.notice){throw 'Unexpected care notice'}
Invoke-Case 'rest-off' 'PUT' '/me/rest' @{mode='OFF'} $bt $null 200|Out-Null
Invoke-Case 'device-delete' 'DELETE' ('/me/devices/'+$installation) $null $bt $null 204|Out-Null
Write-Host 'Stage 5 PostgreSQL HTTP verification: PASS'
