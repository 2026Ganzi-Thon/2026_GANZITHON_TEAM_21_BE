# EmOri API 명세서

**계약 v0.3 · 2026-11-15 · 기본 경로 /api/v1**  
정책 기준 · [EmOri_PRD.md](PRD.md). 실제 배포 주소·ASMR 공급자·연령 확인 공급자는 미정이다. 최신 기획과 사용자 결정을 반영한 목표 계약이며 기존 BE 구현 및 FE 가짜 서버의 동작과 차이가 있다.

## 1. 공통 규칙

HTTPS JSON, camelCase, 인증은 `Authorization: Bearer <accessToken>`. 공개 API 외 인증 필수. ID는 문자열, 시각은 RFC 3339 UTC, 날짜·주차·일 한도는 Asia/Seoul. UUID 형식이라고 가정해 ID를 해석하지 않는다.

표의 `?`는 생략 가능, `|null`은 명시적 null 허용, `[T]`는 배열, `—`는 본문 없음이다. 모든 204 응답은 본문이 없다. 성공 응답은 별도 data 래핑 없이 표의 객체를 반환한다. 다른 사용자 비공개 리소스는 404. 입력의 미정의 필드·잘못된 enum·길이·범위는 422 INVALID_INPUT, 깨진 JSON은 400 INVALID_JSON이다.

**페이지** — `cursor?`, `limit?`(기본 20, 1~100), 응답 `Page<T>={items:[T],nextCursor:string|null}`. 기록·수신함은 created/received 시각과 ID 내림차순. 커서는 필터에 귀속되며 필터 변경 시 버린다. 친구·요청 목록은 생성 시각과 ID 내림차순. nextCursor가 null이 될 때까지 조회하며 12주·대표 색 계산을 첫 100개로 끝내지 않는다.

**멱등성** — ★ 요청에 `Idempotency-Key` UUID 필수. 사용자·메서드·경로·키 단위로 24시간 성공 결과 재사용. 다른 본문은 409 IDEMPOTENCY_CONFLICT, 처리 중은 409 REQUEST_IN_PROGRESS. 같은 사용자 동작의 네트워크 재시도는 **같은 키**를 쓴다. 완료 여부를 모른다는 이유로 새 키를 만들지 않는다. 새 동작에만 새 키를 쓴다. 비즈니스 중복 제약은 24시간 후에도 유지한다. 로그인·refresh는 ★ 대상이 아니다.

오류 형식은 아래와 같다. code로 분기하며 모르는 code는 message를 표시한다. field와 retryAt은 선택 필드다. 401은 refresh 1회 후 원 요청 재시도, 실패하면 세션 삭제·로그인 이동. refresh는 동시에 한 번만 실행한다.

```json
{"error":{"code":"ANON_SEND_LIMIT","message":"오늘 익명으로 보낼 수 있는 횟수를 모두 사용했어요.","retryAt":"2026-11-15T15:00:00Z"}}
```

## 2. 공통 스키마

| 타입 | 필수 필드·의미 |
|---|---|
| Session | `{accessToken,refreshToken,expiresIn}`. expiresIn은 초 |
| UserSummary | `{id,nickname}`. 본인·친구 관계의 공개 요약 |
| Me | `{id,loginId,nickname,friendCode,adultStatus}`. friendCode=loginId, adultStatus=UNKNOWN / VERIFIED_19_PLUS / INELIGIBLE |
| Emotion | `{id,color,area,word}`. 선택된 단어 하나. 카탈로그 표 참조 |
| Track | `{id,order,title,durationMs,playbackUrl}`. 완료 트랙만, order=1~3. URL 만료 시 상세를 다시 조회 |
| Playlist | `{id,status,tracks:[Track]}`. 전송된 구슬에는 READY 플레이리스트만 포함 |
| Orb | `{id,kind,emotions:[Emotion],playlist:Playlist\|null,createdAt}`. kind=SINGLE / MIXED. 감정·정렬 순서·매핑 버전은 생성 당시 스냅샷 보존 |
| Dispatch | `{id,orbId,route,status,recipientCount,sentAt,cancelUntil,targets:[Target]}`. route=SELF / FRIEND / ANONYMOUS. status=SAVED / SENT / PARTIALLY_CANCELLED / CANCELLED |
| Target | `{id,counterpart:UserSummary\|null,status,openedAt,cancelledAt,canCancel}`. 상태 DELIVERED / CANCELLED. id는 해당 전송에만 유효한 대상 ID. 익명 counterpart=null |
| Receipt | `{id,route,sender:UserSummary\|null,receivedAt,openedAt,orb:Orb}`. 익명 sender=null. openedAt 미열람 null |
| Record | `{id,side,orb:Orb,route,recordedAt,receiptId:string\|null,dispatchId:string\|null,counterpart:UserSummary\|null}`. side=OWN / RECEIVED. SELF·익명 counterpart=null |
| RecordDetail | `{record:Record,dispatch:Dispatch\|null,reactions:[{code,count}]}`. dispatch는 OWN에서만. 익명 반응은 합계만 제공 |
| SingleView | `{receipt:Receipt,orb:Orb,mode,actions:[string],actionToken:string\|null}`. mode=FIRST / REPLAY. actions는 REACT / QUIET. 친구·재진입은 빈 배열 |

`orbId`는 콘텐츠, `dispatchId`는 경로 확정 1회, `targetId`는 발신자에게 보이는 전송 대상별 상태, `receiptId`는 수신자 사본, `recordId`는 본인 아카이브 항목이다. 익명 발신 응답에 상대 사용자 ID·receiptId·닉네임·설명 원문을 노출하지 않는다. 익명 받은 Orb의 id로 다른 사람의 기록을 조회할 수 없다.

## 3. 가입·로그인·프로필

학교 인증 API는 제외한다. loginId는 소문자 영문·숫자·밑줄 4~20자이며 대문자 입력은 소문자로 정규화, password는 8~72 UTF-8 바이트, nickname은 앞뒤 공백 제거 후 1~20자다(작업 기본값). 가입 시 nickname 생략은 loginId로 처리한다. 비밀번호는 해시 저장하며 응답에 포함하지 않는다.

| 메서드·경로 | 요청 | 성공·예외 |
|---|---|---|
| GET /auth/login-id-availability · 공개 | loginId 쿼리 | 200 `{loginId,available}`. 가입 트랜잭션에서도 중복 검사 |
| POST /auth/signup · 공개 | `{loginId,password,nickname?}` | 201 `{user:Me,session:Session}`. 409 LOGIN_ID_IN_USE |
| POST /auth/login · 공개 | `{loginId,password}` | 200 `{user:Me,session:Session}`. 401 INVALID_CREDENTIALS |
| POST /auth/refresh · 공개 | `{refreshToken}` | 200 Session. 토큰 회전·이전 토큰 무효화, 실패 401 INVALID_SESSION |
| POST /auth/logout | `{}` | 204. 현재 access 세션에 연결된 refresh 세션 무효화 및 해당 세션의 기기 푸시 등록 해제. 별도 DELETE도 가능 |
| GET /me | — | 200 Me |
| PUT /me/profile | `{nickname}` | 200 Me |

**연령 확인 미결정** — 신규 adultStatus=UNKNOWN. 기본 가입을 완료하되 익명 ON 요청은 403 AGE_REQUIRED. 확인 수단과 검증 정책 결정 후 별도 계약을 추가한다. 클라이언트에서 상태를 직접 승격시키는 엔드포인트는 없다. 이 미결정이 해소되기 전 익명 기능은 실제 신규 사용자 전체 흐름 검증이 불가능하다.

## 4. 친구

`FriendRequest={id,from:UserSummary,to:UserSummary,status,createdAt}`, status=PENDING / ACCEPTED / DECLINED. QR 페이로드는 `ganzithon21://friend/{friendCode}`이며 파싱한 코드만 요청 본문에 보낸다. 공개 아이디로 직접 요청하므로 별도 사용자 검색 목록 API는 제공하지 않는다.

| 메서드·경로 | 요청 | 성공·예외 |
|---|---|---|
| GET /friends | cursor?,limit? | 200 Page<UserSummary> |
| POST /friend-requests | `{friendCode}` | 201 FriendRequest. 자기 자신 422, 없는 코드 404, 이미 친구·어느 방향이든 진행 중 요청 409 RELATION_EXISTS |
| GET /friend-requests | direction?=INCOMING / OUTGOING, cursor?,limit? | 200 Page<FriendRequest>. 기본 INCOMING, FE는 PENDING만 표시 |
| PUT /friend-requests/{id}/decision | `{decision:"ACCEPT" 또는 "DECLINE"}` | 200 FriendRequest. 받는 사람만. 같은 결정 재요청은 같은 상태, 상충 결정 409 REQUEST_CLOSED |
| DELETE /friends/{friendId} | — | 204. 양측 해제, 이미 없으면 204. 과거 기록 보존 |

## 5. 감정 카탈로그·초안 생성

| 메서드·경로 | 요청 | 성공·예외 |
|---|---|---|
| GET /catalog/emotions | — | 200 `{version:"1",items:[Emotion]}` |
| GET /catalog/reactions | — | 200 `{items:[{code,label,emoji}]}` |
| POST /orbs ★ | `{emotionIds:[string]}` | 201 `{orb:Orb,recordId:null}`. 1개 또는 서로 다른 색의 2개만 허용. colors·soundId 입력은 422 |
| GET /orbs/{orbId} | — | 200 `{orb:Orb,generation:Generation\|null}`. 작성자만. 수신자는 receipt 경로 사용 |
| DELETE /orbs/{orbId}/playlist | — | 204. 미전송 작성자만 첨부 해제. 생성 중이면 409 GENERATION_IN_PROGRESS. 완성 파일을 수신자에게 전송한 후 해제 불가 |

생성 시 초안이며 기록·케어에는 아직 반영하지 않는다. 전달 경로 확정 성공 시 Record 한 건을 만든다. recordId가 필요할 때는 아래 전송 응답의 recordId를 사용한다. 미리보기 단계에서 단어를 바꾸면 새 초안을 생성한다.

카탈로그 ID는 `소문자 색 코드_두 자리 순서`다. 예를 들어 purple_01=불안, blue_03=무기력. 각 Emotion은 단어별 하나이며 colors 배열·words[4] 객체는 v0.2 입력·저장 계약에서 사용하지 않는다.

| 색 코드 | area | _01 / _02 / _03 / _04의 word |
|---|---|---|
| YELLOW | 기쁨·기대 | 기쁨 / 기대 / 홀가분함 / 감사 |
| ORANGE | 활력·설렘 | 설렘 / 의욕 / 뿌듯함 / 들뜸 |
| PINK | 애정·다정함 | 애정 / 다정함 / 그리움 / 위로받고 싶음 |
| RED | 강한 감정 | 짜증 / 화남 / 억울함 / 벅참 |
| PURPLE | 복잡함·불안 | 불안 / 막막함 / 복잡함 / 생각이 많음 |
| BLUE | 가라앉음 | 가라앉음 / 외로움 / 무기력 / 지침 |
| GREEN | 안정·회복 | 편안함 / 안정 / 회복 중 / 평온 |

반응 기본 세트는 `HEART/마음 전하기/❤️`, `HUG/안아주기/🫂`, `CHEER/응원하기/🙌`이며 기획이 최종 문구를 변경하면 카탈로그를 갱신한다. FE는 emoji 자체가 아닌 code를 보낸다.

## 6. AI ASMR 생성

제공 음원 GET /catalog/sounds와 soundId 첨부를 이 계약에서 제거한다. 선택 감정으로 여러 ASMR을 생성해 구슬 하나의 플레이리스트에 묶는다. 음원 수 작업 기본값은 3개이며 사용자 결정으로 각 음원은 30000ms 이하, 무가사·무보컬이다. 3개 기준 플레이리스트 최대 총길이는 90000ms다. 서버는 완료 트랙의 durationMs가 1~30000 범위인지 검증한다. 실제 공급자 어댑터와 자격 증명은 서버에 둔다. 외부 생성 API의 모델·가격·실시간 가용성을 이 문서는 확정하지 않는다.

| 메서드·경로 | 요청 | 성공·예외 |
|---|---|---|
| POST /orbs/{orbId}/asmr-generations ★ | `{description?:string}` | 202 Generation, Location은 /asmr-generations/{id}. 설명 최대 300자(작업 기본값). 미전송 작성자만 |
| GET /asmr-generations/{id} | — | 200 Generation. 생성 요청 작성자만. 진행 중 Retry-After: 3 |
| POST /asmr-generations/{id}/retry ★ | `{}` | 202 Generation. FAILED·PARTIAL_FAILED의 실패 트랙만 재시도. 성공 트랙 유지. 실행 중 409 GENERATION_IN_PROGRESS |

`Generation={id,orbId,status,trackCount:3,tracks:[GenerationTrack],createdAt,updatedAt}`. `GenerationTrack={id,order,status,title:string|null,durationMs:number|null,playbackUrl:string|null,errorCode:string|null}`. 완료 전 미정 필드는 null. 상태는 아래와 같다.

| 대상 | 상태 |
|---|---|
| Generation | QUEUED / PROCESSING / PARTIAL_FAILED / FAILED / READY |
| GenerationTrack | QUEUED / PROCESSING / FAILED / READY |

전부 성공하면 READY, 성공·실패가 섞이고 더 실행할 트랙이 없으면 PARTIAL_FAILED, 전부 실패면 FAILED. 일부 실행 중에는 PROCESSING. 공급자 타임아웃도 FAILED 트랙으로 종료시킨다. BE는 작업 재실행·콜백 중복에도 같은 트랙을 중복 생성·연결하지 않고 최종 결과를 한 번만 저장한다.

폴링은 약 3초 간격으로 진행하며 종료 상태에서 멈춘다. 같은 orb에는 하나의 생성 작업만 연결한다. 재생성은 새 초안, 실패 복구는 retry를 사용한다. 기존 생성이 있으면 409 GENERATION_EXISTS. 제공자 오류는 작업 errorCode=GENERATION_FAILED 또는 GENERATION_TIMEOUT으로 표현한다. 생성 요청 자체를 받지 못한 일시 장애는 503이다.

전송 시 이미 생성 요청한 음악이 READY여야 한다. 실패 상태에서도 첨부를 DELETE하면 음악 없이 전송 가능하다. 첨부 해제 후 옛 생성 결과가 다시 붙지 않도록 연결 상태를 확인한다. 전송된 플레이리스트의 트랙 ID·순서를 고정하며 URL 재발급으로 콘텐츠를 바꾸지 않는다. 크레딧·충전·결제 API는 이번 목표 계약에 포함하지 않는다.

## 7. 전달·발신 대상별 상태·취소

| 메서드·경로 | 요청 | 성공·예외 |
|---|---|---|
| POST /orbs/{orbId}/dispatches ★ | 아래 route별 본문 | 201 `{dispatch:Dispatch,recordId}`. 초안 작성자만, 이미 확정 409 ALREADY_DISPATCHED |
| GET /dispatches/{dispatchId} | — | 200 Dispatch. 발신자만 |
| POST /dispatches/{dispatchId}/targets/{targetId}/cancel ★ | `{}` | 200 `{dispatch:Dispatch}`. 해당 전송의 미열람·5분 이내 대상만. 409 CANCEL_NOT_ALLOWED |

| route | 요청 예시 | 동작 |
|---|---|---|
| SELF | `{"route":"SELF"}` | SAVED, recipientCount=0, targets=[], cancelUntil=null. OWN 기록 한 건, 자기 푸시 없음 |
| FRIEND | `{"route":"FRIEND","friendId":"u_2"}` | 현재 수락된 친구 한 명. friendIds 배열은 422. 관계 해제 경합은 409 FRIENDSHIP_CHANGED |
| ANONYMOUS | `{"route":"ANONYMOUS"}` | 조건 충족 대상 1~3명. 대상 직접 지정 불가. 0명이면 409 NO_MATCH, 본인·수신 한도 미차감 |

전송은 생성 초안·READY 음원 확인 후 발신 기록, 전송·대상별 수신 사본, 익명 한도 예약, 푸시 발송 작업을 트랜잭션으로 확정한다. 음악 생성이 미완료면 409 PLAYLIST_NOT_READY. 각 사본은 (전송, 수신자)당 하나다. 전송 실패 후 같은 orb로 재시도할 수 있다.

익명은 발신·수신 통합 ON이고 VERIFIED_19_PLUS인 사람만. 자기는 제외, 수신 3건 도달자·어느 방향으로든 익명 차단된 상대 제외, 서로 다른 최대 3명. 발신자 5회 초과는 429 ANON_SEND_LIMIT와 KST 다음 자정 retryAt. 취소 후 카운터 복원 없음. 설정 변경·차단과 경합하면 커밋 시 최신 상태를 직렬화해 검사한다.

Dispatch.recipientCount는 최초 전달 인원수로 유지한다. sentAt과 cancelUntil(5분 뒤)은 SELF 외 필수. 대상별 canCancel은 DELIVERED·미열람·서버 현재 시각 ≤ cancelUntil일 때 true. 상태와 취소 가능 여부는 별개다. 일부 대상 취소 시 PARTIALLY_CANCELLED, 전부 취소 시 CANCELLED. 대상 한 명의 열람·취소는 다른 대상에 영향을 주지 않는다.

취소와 열람은 대상별 동일 잠금 아래 먼저 확정된 작업을 적용한다. 취소하면 수신 사본·수신 기록을 목록에서 제거하고 이후 조회·열기 404. 발신 기록에는 상태를 남긴다. 전송된 푸시 탭은 확인할 수 없는 구슬로 안내하고 대기 푸시는 취소한다.

### 생성·전송 예시

`POST /orbs` → 201

```json
{"orb":{"id":"o_21","kind":"MIXED","emotions":[{"id":"yellow_03","color":"YELLOW","area":"기쁨·기대","word":"홀가분함"},{"id":"purple_01","color":"PURPLE","area":"복잡함·불안","word":"불안"}],"playlist":null,"createdAt":"2026-11-15T10:00:00Z"},"recordId":null}
```

요청 본문은 `{"emotionIds":["yellow_03","purple_01"]}`다. 이어 `POST /orbs/o_21/asmr-generations`에 `{"description":"긴장을 풀 수 있는 잔잔한 빗소리"}`를 보내고 READY까지 조회한다. 플레이리스트는 GET /orbs/o_21 또는 수신 응답에서 복원한다.

`POST /orbs/o_21/dispatches`에 `{"route":"FRIEND","friendId":"u_2"}` → 201

```json
{"dispatch":{"id":"d_21","orbId":"o_21","route":"FRIEND","status":"SENT","recipientCount":1,"sentAt":"2026-11-15T10:01:00Z","cancelUntil":"2026-11-15T10:06:00Z","targets":[{"id":"t_21","counterpart":{"id":"u_2","nickname":"친구"},"status":"DELIVERED","openedAt":null,"cancelledAt":null,"canCancel":true}]},"recordId":"r_21"}
```

## 8. 수신·반응·신고·익명 차단

| 메서드·경로 | 요청 | 성공·예외 |
|---|---|---|
| GET /inbox | unreadOnly?:boolean,date?,cursor?,limit? | 200 Page<Receipt>. orb 포함, 조회만으로 열람 처리하지 않음 |
| POST /receipts/{id}/open ★ | `{}` | 200 SingleView. 최초 1회 openedAt 확정. 새 키로 두 번째 호출 시 REPLAY |
| GET /receipts/{id}/replay | — | 200 SingleView. 미열람은 409 NOT_OPENED, 취소·권한 없음 404 |
| POST /receipts/{id}/reaction ★ | `{actionToken,code}` | 201 `{code,reactedAt}`. 익명 최초 화면만, 사본당 반응 하나 |
| POST /receipts/{id}/finish | `{actionToken}` | 204. 익명 최초 화면 조용히 듣기·닫기·이탈에서 호출. 이미 종료된 내 토큰도 204 |
| POST /receipts/{id}/reports ★ | `{reasonCode,detail?}` | 201 `{reportId,status:"RECEIVED"}`. 익명 사본 본인만. 중복 409 ALREADY_REPORTED |
| PUT /receipts/{id}/anonymous-block | `{}` | 204. 해당 익명 발신자 차단. 중복도 204. 받은 사용자만, 친구 구슬은 403 ACTION_NOT_ALLOWED |

reasonCode는 INAPPROPRIATE / UNWANTED_REPEATED / HARASSMENT_THREAT / OTHER. detail은 OTHER일 때만 선택 입력(1~500자), 다른 사유에서 입력하면 422. 신고·차단은 별개이고 자동으로 함께 처리하지 않는다. 신고 사유·상세는 발신자에게 노출하지 않는다. 반복 신고·차단 확인에는 아직 보유한 본인 사본을 사용한다.

FIRST+ANONYMOUS일 때만 actions=[REACT,QUIET]와 토큰을 발급한다. FIRST+FRIEND 및 REPLAY는 actions=[], token=null. 토큰은 사용자·사본에 묶고 10분, 반응·finish·재진입 시 종료. 소비되면 409 ACTION_CLOSED. 친구 반응을 직접 요청하면 403 ACTION_NOT_ALLOWED. 멱등 응답 재사용으로 토큰이 다시 활성화되지 않는다.

기록장에서 미열람 구슬은 open으로 처음 열고, 열린 구슬만 replay를 쓴다. 상세 팝업 조회는 열람으로 세지 않는다. replay는 반응·수신·푸시를 생성하지 않는다. 음악 재생은 playlist.tracks를 순서대로 사용하며 FE가 회전·재생 단일 세션·배경 정지를 관리한다.

## 9. 기록·대표 색

| 메서드·경로 | 요청 | 성공·예외 |
|---|---|---|
| GET /records | side?=OWN / RECEIVED, from?,to?,date?,cursor?,limit? | 200 Page<Record>. 기간은 KST 날짜 양끝 포함, date와 from/to 동시 지정은 422 |
| GET /records/{id} | — | 200 RecordDetail. 본인 사본만 |
| DELETE /records/{id} | — | 204. 본인 OWN만 숨김·집계 제외, 받은 기록은 403 ACTION_NOT_ALLOWED |

OWN recordedAt은 경로 확정일, RECEIVED는 수신일. 취소된 수신 기록은 미노출. 발신 기록은 취소 상태 확인 가능. 친구 한 명 전송에서만 OWN counterpart=받는 친구, RECEIVED는 sender, 익명·SELF는 null. OWN 기록 숨김은 수신 사본과 플레이리스트 파일을 삭제하지 않는다. 이미 숨긴 내 기록의 DELETE는 204, 다른 사람 기록은 404다.

FE는 이번 주 포함 최근 12주를 월요일 시작 KST로 묶고 cursor를 끝까지 읽는다. 대표 색은 이번 주 OWN 기록 중 색별 빈도, 혼합은 각각 +1. 삭제·전체 취소 제외, 동률은 그 색이 마지막으로 나타난 기록 시각, 같으면 emotions 배열 순서 우선. 결과가 없으면 무채색 빈 구슬. 이 규칙을 내 감정·프로필에 공통 적용한다.

## 10. 설정·휴식·푸시

`Settings={anonymousEnabled,pushEnabled,rest:Rest}`, `Rest={mode,startsAt,endsAt,active}`, mode=OFF / MANUAL / SCHEDULED. OFF의 시각은 null. 시간은 서버가 결정·검증하며 알림 설정을 휴식 해제로 덮어쓰지 않는다.

| 메서드·경로 | 요청 | 성공·예외 |
|---|---|---|
| GET /me/settings | — | 200 Settings |
| PATCH /me/settings | `{anonymousEnabled?,pushEnabled?}` | 200 Settings. 한 필드 이상. anonymousEnabled=true는 403 AGE_REQUIRED 가능 |
| PUT /me/rest | `{mode:"OFF"}` 또는 `{mode:"MANUAL"}` 또는 `{mode:"SCHEDULED",startsAt,endsAt}` | 200 Rest. MANUAL 즉시 시작·직접 OFF, 예약은 미래 KST 날짜 경계·종료>시작 검사 |
| PUT /me/devices/{installationId} | `{platform,deliveryToken}` | 204. platform=IOS / ANDROID, deliveryToken=Expo Push Token. 설치별 계정 귀속·교체 |
| DELETE /me/devices/{installationId} | — | 204. 본인 설치만 해제 |

예약은 FE가 시작일 00:00 KST와 마지막 날 다음 날 00:00 KST를 UTC로 변환해 보낸다. 예를 들어 11월 16~17일은 startsAt=2026-11-15T15:00:00Z, endsAt=2026-11-17T15:00:00Z다. 구간은 시작 포함·종료 제외. 과거가 된 시작일로 재시도하는 경우 기존 저장된 동일 구간이면 정상 반환한다.

수신 사본 저장 후 푸시 발송. 발송 직전 취소·휴식·pushEnabled를 재검사하며 휴식 중에는 사본 저장만 하고 종료 뒤 밀린 푸시를 보내지 않는다. 시스템 권한 거절·토큰 실패는 구슬 전송을 실패시키지 않는다. 발신자는 휴식 상태·푸시 성공 여부를 볼 수 없다.

푸시 예시는 `{"title":"EmOri","body":"새로운 감정구슬이 도착했어요.","data":{"receiptId":"i_21"}}`. 민감한 정보·ASMR 설명 없음. 현재 FE의 포그라운드 15초 폴링은 중복 receiptId로 배너를 반복 노출하지 않으며 최초 목록을 새 도착으로 처리하지 않는다. 푸시 실제 지원은 Expo 빌드·자격 증명 검증 필요다.

## 11. 케어 콘텐츠·안내 이력

| 메서드·경로 | 요청 | 성공·예외 |
|---|---|---|
| GET /me/care | — | 200 Care. 조건 미충족도 200 |
| GET /me/care/resources/{id} | — | 200 `{id,title,body,url:string\|null}`. 기획 검수된 고정 자료만 |
| PUT /me/care/notices/{noticeId}/acknowledgement | `{action:"VIEWED" 또는 "DISMISSED"}` | 204. 내 단계 안내 완료, 중복은 204. 해당 카드의 최초 조건 충족 이후 발급한 ID만 허용 |

`Care={asOfDate,timezone,daysIn7,daysIn14,level,notice:CareNotice|null}`, `CareNotice={id,level,title,body,resources:[{id,title}]}`. level은 현재 조건 수준 0 / 1 / 2이며 notice는 이번에 노출할 미완료 안내다. 이미 확인·닫은 단계면 level이 1 또는 2여도 notice=null. FE는 level만으로 반복 카드를 만들면 안 된다.

본인 OWN 확정 기록에서 purple_01, purple_02, blue_02, blue_03, blue_04를 검사한다. 오늘 포함 7일 중 4일, 14일 중 10일 조건이며 날짜 중복 제거·혼합 하나 이상 포함이면 1일. 수신·초안·삭제·전체 취소 기록 제외. 2단계 우선, 각 단계 확인·닫기 완료 시 평생 재노출 없음(MVP). 2단계를 완료한 뒤 낮은 1단계를 뒤늦게 보여주지 않는 것은 작업 기본값이다.

카드 선택으로 상세에 진입하면 VIEWED, 카드 닫기는 DISMISSED. 잠깐 홈에 카드가 보였다는 이유만으로 완료 처리하지 않는다. 동시 기기의 acknowledgement도 한 번만 저장한다. 기록 삭제는 집계만 재계산하며 안내 완료 이력은 초기화하지 않는다. 콘텐츠 최종 문구·링크는 기획 제공 필요이며 임의 상담기관 연락처를 생성하지 않는다.

```json
{"asOfDate":"2026-11-15","timezone":"Asia/Seoul","daysIn7":4,"daysIn14":6,"level":1,"notice":{"id":"notice_1","level":1,"title":"최근 마음을 돌아보세요","body":"기획 검수 전 예시 문구입니다.","resources":[{"id":"care_reflection","title":"일상 자기돌봄 안내"}]}}
```

## 12. FE 변경·미결정·검증

### FE 계약에서 변경할 항목

- 가입은 loginId·password·nickname 유지, 학교 인증 제외. 연령 확인은 공급 방식 결정까지 UNKNOWN·익명 제한으로 처리.
- 감정 색만 선택하던 휠에 단어 선택을 추가. POST /orbs는 emotionIds, 반환 recordId는 초안에서 null.
- 제공 음원 카탈로그·soundId 대신 비동기 생성·생성 상태 화면·실패 트랙 재시도·playlist.tracks 순차 재생 연동.
- friendIds 배열을 friendId 한 개로 변경. 전송 응답은 dispatch 래핑과 recordId를 사용.
- 전송 단위 cancel을 대상별 cancel로 변경. 상태 목록은 GET /dispatches/{id}.
- 신고 사유 선택·기타 상세·익명 차단 추가. 케어 notice와 acknowledgement 연동.
- 기록 상세 반응 합계와 푸시 토큰 등록 추가. cursor 완주 및 기간 필터 적용.
- 그룹·즐겨찾기·결제·크레딧 UI는 이번 계약 연동 범위에서 보류.

### 검증 시나리오

| 시나리오 | 정상 결과 |
|---|---|
| 동일 생성·전송 요청 타임아웃 후 같은 키 재시도 | orb·dispatch·기록·한도 차감 중복 없음 |
| 3개 ASMR 중 1개 실패 후 retry | 성공 2개 파일 유지, 실패만 재생성, READY 후 고정 순서 전송 |
| 음악 미완료 전송·첨부 해제 | 전송 409, 생성 실행 종료 후 첨부 해제하면 무음 전송 가능 |
| 익명 두 명 중 한 명 열람, 다른 한 명 취소 | 열람 사본 유지, 다른 사본만 회수, PARTIALLY_CANCELLED |
| 차단·익명 OFF·매칭 동시 요청 | 전송 커밋 시 검증, 익명 ID 노출 없이 대상 제외 |
| 휴식 중 전송, 알림 권한 거절 | 수신함 저장, 푸시 없음. 휴식 종료 시 과거 알림 재발송 없음 |
| 친구 수신 reaction, 과거 익명 reaction | 403 ACTION_NOT_ALLOWED 또는 409 ACTION_CLOSED |
| 케어 하루 반복·혼합·삭제·확인 | 날짜별 1일, 삭제 후 재계산, 확인한 단계 재노출 없음 |
| 100건 초과 기록의 12주 보기 | 모든 cursor 조회, 주차·대표 색 계산에서 누락 없음 |

**남은 결정** — ASMR 공급자·비용·품질, 연령 확인 수단, 케어 고정 콘텐츠, HTTPS 주소, 푸시 빌드 검증. 구슬당 3개, 반응 3종·토큰 10분, 빈 구슬·동률 기준 등은 PRD에 표시한 작업 기본값이다.

**변경 이력** — 0.1(11/14) 최초 제안 → 0.2(11/15) 최신 기획 우선·FE 보완 통합, 사용자 결정의 단어 선택과 AI ASMR 플레이리스트 반영.

**0.3 변경** — 사용자 결정으로 개별 ASMR 최대 30000ms, 3개 기준 플레이리스트 최대 90000ms로 변경.
