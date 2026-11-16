# 7단계 FE 연동 인계

2026-11-16에 [팀 21 FE `dev` 브랜치](https://github.com/2026Ganzi-Thon/2026_GANZITHON_TEAM_21_FE/tree/dev)를 확인했다. 아래는 현재 BE `/api/v1` 계약 기준이다. FE 저장소 코드는 이번 BE 브랜치에서 변경하지 않았다.

## FE에서 바꿀 항목

- `.env.example`의 `EXPO_PUBLIC_API_URL`은 기기에서 접근 가능한 호스트와 `/api/v1`을 포함해야 한다. 예: `http://<개발 PC LAN IP>:8080/api/v1`. 실제 배포는 HTTPS 주소가 정해진 뒤 입력한다. 현재 예시의 `http://localhost:8080`은 모바일 기기와 BE 경로 모두에 맞지 않는다.
- 현재 `src/api/client.ts`는 access token만 Axios 요청에 붙인다. 401에서는 refresh를 한 번만 실행하고 회전된 refresh/access token을 저장한 뒤 원 요청을 재시도해야 한다. 비동기 재시도에는 원래 `Idempotency-Key`를 유지한다.
- `src/constants/emotions.ts`의 색별 단어 묶음과 `src/app/(tabs)/send.tsx`의 색 선택은 `GET /catalog/emotions`에서 **단어 ID 1~2개**를 선택하는 흐름으로 바꾼다. 서로 다른 색만 혼합한다.
- FE `dev`의 `mockSounds`, `soundId`, `GET /catalog/sounds` TODO, 15초 제공 음원·복수 친구 선택·모의 발송은 현재 계약에 없다. ASMR 생성 상태·3트랙·실패 트랙 retry 화면과 친구 한 명 선택으로 교체한다. route 본문은 `SELF` / `FRIEND` + `friendId` / `ANONYMOUS`다.
- 수신함·기록의 모의 데이터는 `GET /inbox`, `GET /records`의 `nextCursor`가 null일 때까지 읽는다. 최초 수신은 `POST /receipts/{id}/open`, 이후는 `GET /receipts/{id}/replay`다. 취소는 `dispatchId`와 `targetId`를 사용하고 서버의 `canCancel` 및 409 결과를 따른다.

## 음악을 붙이는 호출 순서

1. `GET /catalog/emotions`에서 정확한 `emotionIds` 선택.
2. `POST /orbs`에 `{ "emotionIds": ["yellow_03", "purple_01"] }`와 새 UUID `Idempotency-Key`.
3. 음악을 선택한 경우 `POST /orbs/{orbId}/asmr-generations`에 선택적 `{ "description": "..." }`와 새 키. 응답 `Location`의 `GET /asmr-generations/{id}`를 약 3초 간격으로 조회한다. 실패 상태에서는 `POST /asmr-generations/{id}/retry`에 `{}`와 새 키. 음악 없이 진행할 때는 완료된 작업에 `DELETE /orbs/{orbId}/playlist`를 호출한다.
4. 음악 첨부는 `READY` 이후 `POST /orbs/{orbId}/dispatches`에 `{ "route": "FRIEND", "friendId": "..." }` 등과 새 키. 실패한 전송의 네트워크 재시도는 **같은 키**.
5. 수신 `open`·`replay`나 본인 `GET /orbs/{orbId}`의 `orb.playlist.tracks`를 `order` 순으로 재생한다. 수신 시 생성 API를 다시 호출하지 않는다.

공개 인증 API 외에는 `Authorization: Bearer <accessToken>` 필수다. ★ 요청의 `Idempotency-Key`는 UUID이며 같은 동작의 재시도에 재사용한다. `POST`의 빈 본문은 명세대로 `{}`를 보낸다. `GET /asmr-tracks/{id}/audio`는 JSON이 아닌 `audio/wav`다.

## Expo SDK 58 음원 인증

FE `dev`의 `expo-audio` 버전은 `~58.0.12`다. [Expo SDK 58 AudioSource](https://docs.expo.dev/versions/v58.0.0/sdk/audio/#audiosource)는 원격 `uri`와 요청 `headers`를 지원한다. 따라서 현재 Bearer 인증 계약을 바꿀 필요는 없다. FE의 `useOrbMusic(source?: number)`는 원격 `AudioSource` 객체와 순서 있는 트랙을 받도록 변경해야 한다.

```ts
const source = {
  uri: new URL(track.playbackUrl, apiBaseUrl).toString(),
  headers: { Authorization: `Bearer ${accessToken}` },
};
const player = useAudioPlayer(source);
```

`apiBaseUrl`은 `/api/v1`을 포함한 절대 URL이다. `playbackUrl`도 `/api/v1/...` 절대 경로이므로 `new URL(path, origin)`처럼 **호스트 원점**에 결합해야 한다. 위 예시는 `apiBaseUrl`에 경로가 있으면 `new URL`이 루트 경로를 우선한다. native player 요청은 Axios interceptor를 거치지 않으므로 재생 직전·트랙 교체 전에 만료된 access token을 갱신하고 새 headers로 source를 교체해야 한다. 토큰을 URL query에 넣거나 로그에 남기지 않는다.

기기에서 직접 재생이 실패한다면 [Expo FileSystem 다운로드 옵션](https://docs.expo.dev/versions/v58.0.0/sdk/filesystem/#downloadoptions)의 `headers`로 인증 다운로드한 뒤 로컬 `file://` URI를 `expo-audio`에 전달할 수 있다. 캐시 파일은 계정 전환·로그아웃 때 제거한다. 실제 Android/iOS 재생, Range 요청, 코덱·백그라운드 정지는 기기에서 아직 검증하지 않았다. HTTP 테스트 클라이언트에서는 Bearer 음원 200, 미인증 401, 무권한 404를 확인했다.

운영 기본 설정의 ASMR 생성은 실제 공급자 미연결로 503이다. 테스트 프로필의 무음 WAV는 시연·계약 검증 전용이며 운영 AI 결과로 표시하지 않는다.
