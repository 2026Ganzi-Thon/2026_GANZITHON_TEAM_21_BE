# EmOri backend

Java 21, Spring Boot 4.1.2, Gradle 9.8.2. API 계약은 [docs/API.md](docs/API.md), 정책은 [docs/PRD.md](docs/PRD.md), DB 설계는 [docs/DATABASE.md](docs/DATABASE.md)를 참조하세요.

## 로컬 실행

기본 실행은 `./gradlew.bat bootRun`입니다. 기본 DB는 `./data/emori`에 생성되는 H2 파일이며, `data/`는 Git에서 제외합니다. API 기본 주소는 `http://localhost:8080/api/v1`입니다. `./gradlew.bat test`로 테스트를 실행합니다.

서버가 실행 중일 때 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-auth.ps1`로 두 계정의 실제 HTTP 인증 흐름을 확인할 수 있습니다. 다른 포트라면 `-BaseUrl http://127.0.0.1:18081/api/v1`을 전달하세요. 테스트는 새 임의 계정을 생성하며 토큰을 출력하지 않습니다.

PostgreSQL을 사용할 때는 PostgreSQL 서버를 먼저 실행하고 환경변수를 설정한 뒤 `./gradlew.bat bootRun --args='--spring.profiles.active=postgres'`를 실행하세요. Docker 이미지를 만들거나 서버에 배포하는 설정은 아직 없습니다.

테스트용 PostgreSQL은 Docker 엔진이 실행된 환경에서 예를 들어 `docker run --rm --name emori-postgres -e POSTGRES_DB=emori -e POSTGRES_USER=emori -e POSTGRES_PASSWORD=$env:DB_PASSWORD -p 5432:5432 postgres:18`로 실행할 수 있습니다. 다른 터미널에서 `DB_URL=jdbc:postgresql://localhost:5432/emori`, `DB_USERNAME=emori`, `DB_PASSWORD`를 환경변수로 설정하고 postgres 프로필로 서버를 실행한 뒤 위 HTTP 검증 스크립트를 사용하세요. 이 명령은 테스트 DB용이며 실제 PostgreSQL 검증은 Docker 엔진과 이미지를 사용할 수 있을 때 수행해야 합니다.

| 환경변수 | PostgreSQL 프로필에서의 용도 | 기본값 |
|---|---|---|
| `DB_URL` | JDBC URL, 예: `jdbc:postgresql://localhost:5432/emori` | 필수 |
| `DB_USERNAME` | DB 계정 | 필수 |
| `DB_PASSWORD` | DB 비밀번호 | 필수 |
| `AUTH_ACCESS_SECONDS` | access 토큰 수명 | `900` |
| `AUTH_REFRESH_SECONDS` | refresh 토큰 수명 | `2592000` |

실제 DB 비밀번호와 토큰은 환경변수나 로컬 비밀 저장소에서 제공하세요. `.env` 파일은 Git에서 제외됩니다. 비밀번호는 PBKDF2-HMAC-SHA256으로 해시하고, 무작위 access·refresh 토큰은 SHA-256 해시만 DB에 보관합니다. refresh는 두 토큰을 모두 회전시키며 이전 토큰은 즉시 무효화합니다.

## 1단계 API

`GET /auth/login-id-availability?loginId=...`, `POST /auth/signup`, `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`, `GET /me`, `PUT /me/profile`을 `/api/v1` 아래에서 제공합니다. 인증 요청에는 `Authorization: Bearer <accessToken>`을 사용합니다. 회원가입 시 연령 상태는 `UNKNOWN`입니다.

H2는 빠른 로컬 확인용입니다. PostgreSQL의 스키마 제약과 동시 요청은 실제 PostgreSQL에서 별도로 검증해야 합니다.

## 2단계: 친구, 감정 초안, 기본 전송

실행 중인 서버에 대해 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-stage2.ps1`를 실행합니다. 포트를 바꾸었다면 `-BaseUrl http://127.0.0.1:18081/api/v1`을 붙입니다. 스크립트는 임의의 두 계정을 만들고 친구 요청·수락, 감정 초안, 자기 저장, 친구 전송, 멱등 재시도, 친구 삭제를 실제 HTTP로 확인합니다.

인증된 요청에는 `Authorization: Bearer <accessToken>`을 보냅니다. 다음 경로는 모두 `/api/v1` 아래에 있습니다.

| 기능 | 경로 |
|---|---|
| 친구 목록·요청 목록 | `GET /friends`, `GET /friend-requests?direction=INCOMING&limit=20&cursor=...` |
| 친구 요청·결정·삭제 | `POST /friend-requests`, `PUT /friend-requests/{id}/decision`, `DELETE /friends/{friendId}` |
| 감정·반응 목록 | `GET /catalog/emotions`, `GET /catalog/reactions` |
| 구슬 초안·조회 | `POST /orbs`, `GET /orbs/{orbId}` |
| 자기 저장·친구 전송·상태 | `POST /orbs/{orbId}/dispatches`, `GET /dispatches/{dispatchId}` |

친구 요청 본문은 `{"friendCode":"other_login_id"}`입니다. QR은 앱에서 `ganzithon21://friend/{friendCode}`를 읽어 `friendCode`만 API에 보냅니다. 요청 결정은 `{"decision":"ACCEPT"}` 또는 `{"decision":"DECLINE"}`입니다. `POST /orbs`에는 `{"emotionIds":["yellow_03"]}` 또는 서로 다른 색의 두 감정 ID를 보냅니다. 성공 응답의 `recordId`는 `null`이며 이때 기록이 생기지 않습니다.

초안을 확정할 때 `Idempotency-Key: <UUID>` 헤더와 `{"route":"SELF"}` 또는 `{"route":"FRIEND","friendId":"<user UUID>"}` 본문을 보냅니다. `POST /orbs`에도 같은 헤더가 필수입니다. 같은 키·본문의 성공 요청은 24시간 같은 결과를 반환합니다. 다른 키로 같은 구슬을 다시 확정하면 `409 ALREADY_DISPATCHED`입니다. 친구 삭제 후 새 전송은 `409 FRIENDSHIP_CHANGED`이고 이미 만들어진 기록과 수신 사본은 유지됩니다.

Flyway `V2`는 H2와 PostgreSQL 공통 구조입니다. `postgres` 프로필에서만 적용하는 `V2_1`은 로그인 아이디 정규식 제약과 친구 요청·본인 기록의 부분 고유 인덱스를 추가합니다. H2에서는 로그인 아이디를 서비스 계층에서 검증하며, H2 테스트를 PostgreSQL 검증으로 간주하지 않습니다. 수신함·기록 조회·대상 취소는 3단계에 구현했고, 익명 전송은 4단계, ASMR 생성은 6단계 범위입니다.

Docker Desktop 엔진에 접근할 수 있는 Windows PowerShell에서는 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-stage2-postgres.ps1`로 임시 PostgreSQL 컨테이너를 띄우고 Flyway·인증·2단계 HTTP 흐름·친구 요청과 전송의 동시성 테스트를 검사할 수 있습니다. 스크립트는 고유한 컨테이너 이름과 임의의 DB 암호를 사용하고 종료 시 자신이 만든 컨테이너를 정리합니다. 실행 로그는 `build/`에 남습니다.

## 3단계: 수신·기록·대상별 취소

서버 실행 중 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/smoke-stage3.ps1`로 두 계정의 친구 전송, 수신함, 최초 열람, 재열람, 기록 삭제, 대상별 취소를 실제 HTTP로 확인합니다. PostgreSQL 전체 검증은 Docker 엔진이 실행 중일 때 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-stage3-postgres.ps1`을 실행합니다. 이 스크립트는 임시 PostgreSQL 컨테이너에 Flyway V1·V2·V2.1·V3을 적용하고 1~3단계 HTTP 및 경합 테스트를 수행한 뒤 컨테이너를 정리합니다.

모든 경로는 `/api/v1` 아래이며 Bearer access token이 필요합니다. `GET /inbox?unreadOnly=true&date=YYYY-MM-DD&limit=20&cursor=...`는 조회만 수행합니다. `POST /receipts/{id}/open`은 `Idempotency-Key: <UUID>`와 `{}`를 보내 최초 열람을 확정하며, 같은 키 재시도는 첫 응답을 돌려줍니다. `GET /receipts/{id}/replay`는 열람 후 재진입에 사용합니다. 친구 수신 구슬은 반응과 조용히 듣기를 사용할 수 없습니다.

`GET /records?side=OWN&from=YYYY-MM-DD&to=YYYY-MM-DD&limit=100&cursor=...` 또는 `date=YYYY-MM-DD`로 본인 기록을 페이지 끝까지 순회할 수 있습니다. 날짜는 KST 기준 양끝 포함이며 대표 색과 12주 기록장을 계산할 때 모든 페이지를 조회해야 합니다. `GET /records/{id}`는 상세, `DELETE /records/{id}`는 본인 OWN 기록 숨김입니다. `POST /dispatches/{dispatchId}/targets/{targetId}/cancel`은 멱등키와 `{}`를 보내며, 전송 후 5분 이내이고 해당 수신자가 미열람일 때만 성공합니다. 취소된 수신 사본은 수신함과 받은 기록에서 제외되지만 발신 기록에는 취소 상태가 남습니다.
