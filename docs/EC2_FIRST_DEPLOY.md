# EC2 첫 수동 배포 (기능 브랜치)

대상: Ubuntu EC2 한 대, `feat/stage3-inbox-records-cancel` 브랜치. 앱·PostgreSQL·Caddy를 같은 Docker Compose 프로젝트에서 실행한다. DB와 음원 파일, Caddy 인증서는 각각 Docker named volume에 보존된다. 앱 8080은 EC2의 loopback에만 열리고, PostgreSQL 5432는 호스트에 공개하지 않는다. 공개 주소는 `https://api.gaebalmani.shop/api/v1`이다.

## 배포 전 준비

1. `api.gaebalmani.shop`의 A 레코드를 EC2의 고정 공인 IP(Elastic IP 권장)로 지정한다. AAAA 레코드가 있다면 해당 IPv6도 같은 서버로 연결되게 한다.
2. EC2 보안 그룹 인바운드는 SSH 22를 본인 IP로 제한하고 TCP 80·443을 공개한다. UDP 443은 Caddy의 HTTP/3용으로 선택 개방한다. 5432와 8080은 공개하지 않는다.
3. EC2에 저장소 읽기 권한을 준비한다. 공개 저장소라면 아래 HTTPS clone을 사용한다. 비공개 저장소라면 읽기 전용 GitHub deploy key를 EC2에 설정하고 clone 주소를 `git@github.com:2026Ganzi-Thon/2026_GANZITHON_TEAM_21_BE.git`로 바꾼다.
4. EC2에서 Docker Engine과 Compose plugin을 사용할 수 있어야 한다. 아래 설치 예시는 Ubuntu용이다. 다른 배포판은 해당 설치 절차를 따른다.

## 1. 내 PC에서 EC2 접속

PowerShell에서 키 경로와 공인 IP를 바꾼다.

```powershell
ssh -i "C:\path\to\emori-ec2.pem" ubuntu@EC2_PUBLIC_IP
```

## 2. EC2에서 Docker 준비 (이미 설치됐다면 버전 확인만)

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl git openssl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo docker version
sudo docker compose version
```

Docker 공식 Ubuntu 설치 절차: https://docs.docker.com/engine/install/ubuntu/ . 기존 Docker 패키지가 설치돼 충돌한다면 공식 문서의 충돌 패키지 제거 단계를 먼저 확인한다.

## 3. 기능 브랜치와 `.env` 준비

```bash
cd ~
git clone --branch feat/stage3-inbox-records-cancel --single-branch https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_21_BE.git EmOri
cd EmOri
git branch --show-current
git rev-parse --short HEAD
umask 077
DB_PASSWORD="$(openssl rand -hex 32)"
EMORI_IDEMPOTENCY_SECRET="$(openssl rand -base64 32)"
cat > .env <<EOF
POSTGRES_DB=emori
DB_URL=jdbc:postgresql://db:5432/emori
DB_USERNAME=emori
DB_PASSWORD=$DB_PASSWORD
EMORI_IDEMPOTENCY_SECRET=$EMORI_IDEMPOTENCY_SECRET
AUTH_ACCESS_SECONDS=900
AUTH_REFRESH_SECONDS=2592000
PUSH_WORKER_ENABLED=false
EXPO_ACCESS_TOKEN=
APP_LOCAL_PORT=8080
HTTP_PORT=80
HTTPS_PORT=443
EOF
chmod 600 .env
unset DB_PASSWORD EMORI_IDEMPOTENCY_SECRET
```

`.env.example`에 같은 변수 목록이 있다. `.env`를 Git에 추가하지 않는다. `EMORI_IDEMPOTENCY_SECRET`은 32바이트 Base64 키이며 재시작·재배포 사이에 유지해야 한다. 앱 인증은 JWT가 아닌 opaque access/refresh 토큰과 서버 세션 방식이므로 별도 JWT 키는 사용하지 않는다. DB 볼륨이 이미 만들어진 뒤 비밀번호를 바꾸면 PostgreSQL 내부 계정 비밀번호도 별도로 변경해야 한다.

## 4. Compose 시작과 확인

도메인 DNS가 EC2를 가리키고 TCP 80·443이 열려 있어야 Caddy가 공인 인증서를 자동 발급한다. Caddy 데이터 볼륨은 인증서 갱신에도 사용된다. Docker와 Caddy가 이미 80·443 포트를 쓰고 있다면 기존 프로세스를 먼저 확인한다.

```bash
sudo docker compose config --quiet
sudo docker compose up -d --build
sudo docker compose ps
sudo docker compose logs --tail=100 app
sudo docker compose logs --tail=100 proxy
curl -fsS http://127.0.0.1:8080/health
sudo docker compose exec -T db psql -U emori -d emori -Atc 'SELECT version,success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1'
curl -fsS https://api.gaebalmani.shop/health
curl -i https://api.gaebalmani.shop/api/v1/catalog/sounds
```

정상 결과는 `/health`의 `{"status":"UP"}`, Flyway `7|t`, 그리고 인증 없이 호출한 카탈로그의 `401`이다. 마지막 `401`은 `/api/v1` 경로가 HTTPS 프록시를 통과해 앱에 도달했다는 확인이다. 운영 음원이 아직 없다면 인증 후 카탈로그는 `{"items":[]}`를 반환하며 앱은 정상 기동한다. 인증된 음원 등록 방법은 [FE 연동 인계](FE_INTEGRATION.md)를 따른다. Caddy 자동 HTTPS는 도메인과 공개 80·443에 의존한다: https://caddyserver.com/docs/automatic-https .

## 운영 주의

- `sudo docker compose down`은 컨테이너만 내린다. `down -v`는 DB·음원·인증서 볼륨을 지우므로 실행하지 않는다.
- `.env`, DB 볼륨, 음원 볼륨은 별도로 백업한다. 앱 이미지 재빌드는 이 데이터를 백업하지 않는다.
- 첫 EC2 검증 전에는 브랜치를 병합하거나 GitHub Actions 배포를 켜지 않는다.
# 2026-11-18 추가: 제공 음원 등록

팀이 전달한 `EmOri_샘플음악_WAV_백엔드용.zip`의 WAV 7곡은 Git 저장소에 넣지 않고 EC2의 `/home/ubuntu/emori-samples`에 압축을 풀어 등록한다. 제목·색은 ZIP의 목록.txt를 따른다. 앱 컨테이너가 사용하는 `asmr_data` 볼륨에 파일이 복사되며 DB `sound_catalog`에 1~15000ms 검증 후 등록된다. 예를 들어 파랑 음원은 아래처럼 등록한다. 재실행하면 새 ID가 추가되므로 등록 전에 카탈로그에 같은 제목·색이 없는지 확인한다.

```bash
cd /home/ubuntu/EmOri
sudo docker compose run --rm --no-deps -v /home/ubuntu/emori-samples/sample-blue.wav:/tmp/import.wav:ro app --spring.profiles.active=postgres,sound-import --spring.main.web-application-type=none --emori.sound-import.file=/tmp/import.wav '--emori.sound-import.title=잔잔한 파도' --emori.sound-import.color=BLUE
```

`GET /api/v1/catalog/sounds`에는 활성 상태이고 저장 파일이 있는 음원만 나타난다. Bearer 토큰이 필요하다.
