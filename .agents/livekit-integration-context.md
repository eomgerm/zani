# ZANI LiveKit 통합 에이전트 기준

> 적용 범위: `S15P11A105` 저장소의 LiveKit 애플리케이션 연동 작업

## 1. 문서 지위

이 문서는 ZANI의 프론트엔드·백엔드·LiveKit·Egress 연동을 작업하는 에이전트가 먼저 읽어야 하는 최신 제품·기술 기준이다.

- EC2, UFW, SSH, 인증서, 권한 변경에는 기존 인프라 안전 규칙을 계속 적용한다.
- 백엔드 코드를 변경할 때는 루트 [`AGENTS.md`](../AGENTS.md)와 [`ddd-development-guide.md`](./ddd-development-guide.md)를 함께 따른다.
- 이 문서와 과거 Phase 문서의 LiveKit 애플리케이션 정책이 충돌하면 이 문서를 우선한다.
- 사용자의 별도 요청 없이 EC2, UFW, SSH, 인증서, 권한, Media Stack을 변경하지 않는다.
- 사용자의 사전 확인 없이 commit 또는 Merge Request를 생성하지 않는다.

팀 구현 문서는 다음 세 파일이다.

- [`livekit-overview.md`](./livekit-overview.md)
- [`livekit-frontend-guide.md`](./livekit-frontend-guide.md)
- [`livekit-backend-guide.md`](./livekit-backend-guide.md)

## 2. 고정된 시스템 경계

```text
브라우저 ↔ Spring Boot : 인증, 세션, 권한, 채팅, 손들기, 반응, 수업 상태
브라우저 ↔ LiveKit     : 카메라, 마이크, 화면 공유, 연결 상태
Spring Boot ↔ LiveKit  : Room, 토큰, 참가자 권한, 강제 제어, Webhook, Egress
Egress → 로컬 스토리지 : 허용된 원본 Track 저장
FFmpeg → 로컬 스토리지 : 종료 후 최종 MP4 합성
```

- 프론트엔드는 LiveKit API key/secret을 알 수 없다.
- 프론트엔드는 Room 이름, participant identity, 역할, grant를 결정하지 않는다.
- Spring Boot는 미디어를 중계하지 않는다.
- LiveKit DataPacket은 MVP에서 사용하지 않는다. `canPublishData=false`를 명시한다.
- 업무 이벤트는 Spring Boot WebSocket과 Redis를 통해 전달하고 필요한 항목은 DB에 저장한다.
- LiveKit Cloud, S3, MinIO는 운영에서 사용하지 않는다.
- 녹화 파일은 `/srv/zani/recordings`에 저장한다.

## 3. 현재 구현 상태

> **이 절이 LiveKit 연동 구현 상태를 서술하는 유일한 곳이다.** `livekit-overview.md`,
> `livekit-frontend-guide.md`, `livekit-backend-guide.md`는 상태를 자체 서술하지 않고
> 이 절을 가리킨다. 상태가 바뀌면 여기만 고친다.
>
> 확인 시점: 2026-07-31, `dev` 기준. **작업 전에 이 절이 최신인지 먼저 확인한다** — `dev`가
> 하루에도 여러 번 움직여 이 절이 금방 낡는다. 어긋난 것을 발견하면 여기를 고치고 넘어간다.

이 문서의 §4 이후는 **목표 계약**이다. 목표와 현재 구현이 다른 지점을 아래에 모았다. 이미 있는 것을 다시 만들지 않도록 작업 시작 전에 이 절을 먼저 읽는다.

### 프론트엔드 — 구현됨

- `livekit-client`, `@livekit/components-react` 의존성.
- `/media-token` 호출, `Room.connect`, SDK 재연결 이벤트 반영: `mediaTokenApi`, `liveKitRoom`, `RoomProvider`.
- Track publish·subscribe와 미디어 제어: `useRoomMediaControls`, `useRoomParticipants`, `useRoomReconnect`.
- 참가자·강의실 UI: `ParticipantGrid`, `ParticipantTile`, `RoomControlBar`, `RoomSidePanel`, `RoomScreen`.
- 실제 장치 점검: `features/media`의 `DevicePreview`, `deviceTest`, `browserSupport`, `prejoinResult`.
- presence heartbeat와 종료 임박 안내: `useSessionPresence`, `useSessionTimeWarning`.
- 참가도 관련: `AttentionCameraSource`, `CoachTipCard`, `AnalysisStatusNotice`, `CoachingStatusNotice`.

fixture와 로컬 상태로만 동작한다는 과거 서술은 더 이상 맞지 않는다.

- 업무 WebSocket 토대와 공개 채팅: `domains/interaction`의 `sessionChannel`, `sessionEvent`, `useSessionChat`, `chatMessages`(티켓 63).

### 프론트엔드 — 없음

- 손들기·반응 UI(티켓 64, 브랜치 진행 중), 화면 공유 상태 표시(65), 강제 음소거(66).
- 학생 화면의 강사 유예 안내. `SessionPresenceNotice`에 문구는 있으나 학생이 그 상태를 받지 못한다(§9 구현 격차 참고).

### 백엔드 — 구현됨

- 엔드포인트 6개. `POST /api/v1/sessions`, `GET /api/v1/sessions`, `POST /api/v1/sessions/join`, `POST /api/v1/sessions/{sessionId}/media-token`, `POST /api/v1/sessions/{sessionId}/end`, `POST /api/v1/sessions/{sessionId}/presence`.
- LiveKit 토큰 발급. `LiveKitTokenAdapter`가 §8의 grant를 그대로 발급한다. `LiveKitProperties.tokenTtl` 기본값 10분, `LiveKitRoomNames`가 §4의 이름 규칙과 역파싱을 소유한다.
- Webhook 수신과 서명 검증. `RecordingWebhookController`, `LiveKitWebhookVerifierAdapter`(SDK `WebhookReceiver`), 이벤트 ID 기준 멱등 저장(Flyway V3).
- Track Egress 시작. `LiveKitTrackEgressAdapter`, 허용 정책 `RecordingTrackPolicy`, 출력 경로 `{basePath}/{sessionId}/raw/instructor|participants/{alias}/`.
- 녹화 outbox와 릴레이. `RecordingOutbox*`, `RecordingOrchestrator`, `RecordingOutboxRelayScheduler`(Flyway V2).
- 강사 `SessionParticipant`를 세션 생성과 **같은 트랜잭션**에서 만든다(`NewSessionSaver`). 이 행이 없으면 강사가 자기가 만든 방에서 403을 받아 카메라·마이크를 켤 수 없다.
- 업무 WebSocket 토대. `StompConfig`(`@EnableWebSocketMessageBroker`, 내장 `SimpleBroker`), `SimpSessionEventPublisher`, `SessionEventType`, `SendChatMessageService`, `ChatStompRoundTripIntegrationTest`(티켓 63). 봉투 계약은 §10.1 참고.
- 강사 메모 확정과 사후 처리 파이프라인. `SessionAnalysisStatus`(`NOT_STARTED`·`WAITING_FOR_NOTE`·`PROCESSING`·`COMPLETED`·`FAILED`), 30분 비활성 자동 확정, 사후 처리 작업 outbox(티켓 88·90·91·92, Flyway V6).
- Flyway V1~V6.

LiveKit 코드가 아예 없다는 과거 서술은 더 이상 맞지 않는다.

### 백엔드 — 없음

- **`RoomServiceClient`를 쓰지 않는다.** `LiveKitMediaRoomAdapter`는 room 이름 계산과 자격증명 노출만 한다. 그래서 `CreateRoom`, `DeleteRoom`, `UpdateParticipant`, 트랙 mute가 전부 없다. §8·§9의 진행 중 공유 강제 중지와 §9 종료 절차의 Room 종료가 여기에 걸린다.
- `POST /{sessionId}/start`. §5가 필수 단계로 서술하지만 매핑이 없다. FE에는 `startSessionApi`가 이미 있어 호출하면 404다.
- 업무 이벤트 종류(§10). 토대는 있으나 `SessionEventType`에 `CHAT_MESSAGE` 하나뿐이다. 손들기·반응(64), 화면 공유 상태(65), 강제 음소거(66)가 값을 추가한다. `FORCE_MUTED`는 `RoomServiceClient`가 선행이다.
- 종료 트리거. 종료는 `SessionPresenceService.record()` 안에서만 호출되므로 **heartbeat가 도착해야** 일어난다. 그리고 유예 시작은 강사 role 참가자가 `connected == false`로 보고할 때만 열린다. FE에는 `beforeunload`·`pagehide`·`sendBeacon`이 없어 **창을 그냥 닫으면 유예가 시작조차 않고**, 전원이 이탈하면 종료를 트리거할 heartbeat도 없다. 남는 수단은 3시간 만료 스케줄러(`fixedDelay PT1M`)뿐이라 1시간에 끝난 수업이 2시간을 더 `LIVE`로 남는다. FRD `LIVE-009`·`LIVE-010`이 이걸 금지한다.
- 강사 유예 상태를 학생에게 전달하는 경로. §9 구현 격차 참고.
- 화면 공유 단일 활성 강제(§8·§9). 트랙 발행 통지는 받지만 단일성 검사가 없다.
- 세션 상태. `SessionStatus`는 `LIVE`, `ENDED` 두 값뿐이다. §9의 `ENDING`·`NOTE_PENDING`·`PROCESSING`과 내부 `PREPARING`이 없다. 세션 생성이 즉시 `LIVE`이고 `startedAt`을 즉시 기록하며, `expiresAt`은 `startedAt + 3시간`으로 계산한다.
- Webhook 처리 범위가 녹화 관련(트랙 발행·Egress 진행) 몇 종에 한정돼 있다. **참가자 입·퇴장과 room 시작·종료를 알려주는 이벤트를 받지 않는다.** 그래서 사후 자료 접근 자격이 실제 연결 성공을 근거로 판정되지 않는다 — `SessionJoinService`가 `POST /sessions/join` 시점에 곧바로 기록하므로, 초대 코드로 API만 호출한 사람도 참가자 행을 얻는다. FRD `ACCESS-002`가 이걸 금지한다. 다만 `firstJoinedAt`을 읽는 프로덕션 코드가 아직 없어 실제 피해는 나지 않았다.
- 초대 코드 관련 세 가지.
  - **상태 검사가 없다.** `SessionJoinService`가 세션 상태를 보지 않아 종료된 세션 코드로도 참가 행이 생긴다. 실제 차단은 `/media-token`이 `ENDED`를 거부하는 지점에서 일어난다. 준비 상태를 도입하면(§5) 준비 중에도 학생 join이 통과하므로 함께 막아야 한다.
  - **모호한 문자를 제외하지 않는다.** `InviteCodeGenerator.ALPHABET`이 `A-Z0-9` 36자 전체여서 `I`·`O`·`0`·`1`이 섞여 나온다. 강사가 코드를 구두나 화면으로 전달할 때 오입력을 만든다. 제외해도 기존 발급 코드와 호환된다 — 검증이 문자 집합이 아니라 DB 조회이기 때문이다.
  - **서버 정규화가 없다.** `JoinSessionRequest`가 `@Size(min=8,max=8)`만 걸어 표시형 9글자(`A7KM-2PQR`)를 받을 수 없다. 지금은 FE(`domain/inviteCode.ts`)가 하이픈·공백 제거와 링크 파싱까지 하고 있어 실사용에는 문제가 없다.
- `recording_files.storage_key`의 V1 COMMENT가 여전히 "비공개 S3 객체 키"다. 로컬 상대 경로 정책에 맞춘 후속 migration이 필요하다.

### 이 문서의 목표 계약에 없는 구현

문서보다 코드가 앞서 있는 부분이다. 관련 작업을 할 때 이 사실을 모르면 중복 구현이 된다.

- **presence heartbeat.** 강사 5분 유예의 트리거가 §9가 서술한 Webhook 기반 이탈 감지가 아니라 **heartbeat**다. `SessionPresenceService`가 Redis TTL로 관리하며, presence TTL은 30초, 강사 유예는 5분이다. 별도 스케줄러가 없고 **유예 만료 뒤 도착한 heartbeat가 종료를 트리거**한다. 참가자에게는 `ReconnectStatus`(`CONNECTED`, `RECONNECTED`, `GRACE_PERIOD`, `DISCONNECTED`, `SESSION_ENDED`)를 돌려준다. 5분이라는 값만 문서와 같고 메커니즘이 다르다.
- **코칭용 오디오 Egress.** 강사 마이크 하나에 아카이브용 파일 Egress와 코칭용 WebSocket Egress **두 개**를 시작한다. 파일 출력과 WebSocket 출력은 proto 상 `oneof`라 한 Egress가 둘 다 낼 수 없기 때문이다. 수신 주소는 자격증명을 포함하므로 `audioclip`의 `AudioStreamEndpointPort`가 소유하고 로그에 남기지 않는다. 관련 코드는 `RedisAudioStreamEgressRegistry`, `AudioStreamEgressRequest`다.
- **Egress 호출 타임아웃 불변식.** `LiveKitTrackEgressAdapter.CALL_TIMEOUT`(60초)은 `RecordingOrchestrator.CLAIM_LEASE`(2분)보다 **반드시 작아야 한다.** 호출이 lease보다 오래 매달리면 진행 중인 작업이 만료 처리되어 다른 인스턴스가 같은 트랙에 Egress를 중복 시작한다. 두 값 중 하나를 바꿀 때 이 관계를 먼저 확인한다.
- `Session.limitedMode`, `SessionAnalysisStatus`, `SessionExpiryScheduler`.
- 녹화 실행 상태는 `RecordingStatus`(`STARTING`, `RECORDING`, `COMPLETE`, `PARTIAL`, `FAILED`)다.

기존 코드를 목표 계약에 맞추는 작업은 별도 구현 티켓에서 수행한다.

## 4. 식별자와 이름

### Room 이름

```text
zani-{environment}-session-{sessionId}
```

예: `zani-dev-session-01982F...`

- 백엔드만 생성한다.
- 프론트엔드는 `/media-token` 응답의 값을 그대로 사용한다.
- 제목, 초대 코드, 사용자 이름을 포함하지 않는다.

### 참가자 identity

```text
p-{sessionParticipantId}
```

현재 코드의 개념명도 `SessionParticipant`다. identity는 `p-{sessionParticipantId}`이며 ID는 기존 `TsidGenerator`로 참가 관계 생성 시 한 번만 만든다.

- 재접속과 토큰 재발급에도 동일한 identity를 사용한다.
- 프론트엔드 입력을 받지 않는다.
- 사용자 ID, 이메일, 역할을 identity에 포함하지 않는다.

### 실시간 이름과 녹화 alias

```text
displayName    : ZANI 프로필 표시 이름
recordingAlias : instructor | student-001 | student-002 | ...
```

- alias는 세션별로 한 번 배정하고 DB에 보존한다.
- 녹화 파일, manifest, Whisper와 GMS 입력에는 alias만 사용한다.
- 실제 사용자와 alias의 연결은 권한이 제한된 DB에서만 관리한다.

## 5. 세션 생성과 입장

### 강사

1. 브라우저·마이크·카메라를 사전 검사한다.
2. `POST /api/v1/sessions`로 `PREPARING` 세션, 강사 참가 관계, LiveKit Room을 만든다.
3. `POST /api/v1/sessions/{sessionId}/media-token`으로 강사 토큰을 받는다.
4. LiveKit에 연결하고 마이크와 가능한 카메라 Track을 게시한다.
5. `POST /api/v1/sessions/{sessionId}/start`를 호출한다.
6. 백엔드는 강사 미디어와 Egress 시작을 확인한 후 `LIVE`로 전환한다.
7. 이때 `startedAt`, `recordingStartedAt`을 기록하고 초대 링크·코드를 활성화한다.

강사 카메라 실패는 제한 모드로 허용하지만 마이크 또는 미디어 연결 실패는 시작을 차단한다.

### 학생

1. 초대 링크·코드와 로그인 계정으로 진행 중 세션을 확인한다.
2. 최신 데스크톱 Chrome, 카메라 실제 입력, 마이크 실제 입력, 녹화·분석 고지를 모두 확인한다.
3. 테스트 실패 시 입장을 차단한다.
4. `POST /api/v1/sessions/join`으로 참가 관계를 생성하거나 재사용한다.
5. `/media-token`으로 학생 토큰을 발급받는다.
6. **실제 미디어 연결이 성공한 사실을 서버가 확인한 시점에** `firstJoinedAt`과 사후 자료 접근 자격을 확정한다. 클라이언트가 스스로 "연결됐다"고 알리는 것을 근거로 삼지 않는다 — 그게 자격 부여 조건이므로 위조할 동기가 정확히 거기 있다.

API만 호출하고 실제 미디어 연결에 성공하지 않은 사용자는 사후 자료 접근 자격을 얻지 않는다.

## 6. 초대와 정원

- 정규화된 초대 코드는 모호한 문자를 제외한 대문자 영문·숫자 8자리다.
- 저장 예: `A7KM2PQR`, 표시 예: `A7KM-2PQR`.
- 로그인 사용자만 사용할 수 있다.
- `LIVE` 상태에서만 유효하며 종료 즉시 만료한다.
- 학생은 강사 승인 없이 입장한다.
- 같은 `(sessionId, userId)` 참가 관계는 하나만 존재한다.
- 동시 30명까지가 성능 보장 대상이다(FRD §8.3 · `NFR-PERF-001`).
- **입장 인원 하드 캡을 두지 않는다.** 정원 초과를 API에서 차단하지 않고 LiveKit Room의 `max_participants`도 설정하지 않는다. 31명 이상은 동작을 보장하지 않을 뿐 막지 않는다.

한때 하드 캡 30명과 `409 SESSION_CAPACITY_REACHED` 차단으로 정해졌다가 되돌린 결정이다(2026-07-31). 그 오류 코드는 만들지 않으므로 응답 계약에 넣지 않는다.

## 7. 미디어 토큰

```http
POST /api/v1/sessions/{sessionId}/media-token
Authorization: Bearer {ZANI_ACCESS_TOKEN}
```

요청 본문은 비운다. 백엔드는 사용자 JWT와 DB에서 역할·identity·이름·grant를 결정한다.

현재 응답이다(`MediaTokenResponse`). 프로젝트 공통 `ApiResponse<T>` 봉투의 `data`에 담긴다.

```json
{
  "liveKitUrl": "wss://i15a105.p.ssafy.io",
  "accessToken": "<redacted>",
  "roomName": "zani-dev-session-123",
  "participantIdentity": "p-456",
  "expiresAt": "2026-07-24T12:30:00Z",
  "sessionExpiresAt": "2026-07-24T15:00:00Z",
  "sessionTitle": "React 상태관리 심화"
}
```

- `participant` 중첩 객체가 아니라 `participantIdentity` 평면 필드다.
- **`role`은 이 응답에 없다.** 백엔드가 LiveKit 토큰의 metadata JSON(`{"role":"INSTRUCTOR"}`)에 실어 보내고, FE는 `participant.metadata`에서 읽는다. LiveKit 서버 SDK 0.14.0에 `setAttributes`가 없어 metadata를 쓴다. metadata가 없거나 JSON이 깨져도 화면이 죽지 않도록 FE는 `student`로 폴백한다.
- `displayName`도 이 응답에 없다. 토큰의 `name`으로만 전달되며, 조회할 이름이 없으면 `"참가자"`가 들어간다.
- `recordingAlias`는 응답에 없고 `SessionParticipant`에 필드 자체가 없다. 녹화 alias는 `RecordingAlias`가 녹화 도메인에서 만든다.
- `sessionId`는 응답에 없다. 요청 경로에 이미 있다.
- `sessionExpiresAt`(= `startedAt + 3시간`)과 `sessionTitle`은 강의실이 진입 시 이 응답만 받기 때문에 함께 내린다. `sessionTitle`은 필수 검증 대상이 아니다.
- TTL은 10분이다.
- 브라우저 메모리에만 보관한다.
- 완전 재입장 시 같은 identity로 새 토큰을 발급한다.
- 종료된 세션(`ENDED`)에는 발급하지 않는다.
- `egressId`는 응답하지 않는다.

멤버십을 세션 조회보다 **먼저** 확인한다. 비멤버에게 세션의 존재나 종료 상태를 노출하지 않기 위한 순서이므로 뒤집지 않는다.

## 8. 권한

두 역할이 같은 grant를 받는다. 화면 공유에 역할 제한을 두지 않기로 확정했다(2026-07-30).

```text
roomJoin=true
canSubscribe=true
canPublish=true
canPublishSources=[CAMERA, MICROPHONE, SCREEN_SHARE, SCREEN_SHARE_AUDIO]
canPublishData=false
```

- `canPublishSources`는 문서에서 protobuf enum 이름(대문자)으로 적지만, JWT 클레임은 `TrackSource`의 **소문자** 표기를 문자열로 받는다. 서버가 그 문자열과 정확히 비교하므로 대문자로 실으면 publish가 조용히 전부 막힌다.
- `SCREEN_SHARE`와 `SCREEN_SHARE_AUDIO`는 항상 함께 부여한다.
- 학생 화면 공유에 강사 승인을 요구하지 않는다. 요청·승인·거절·회수 플로우는 두지 않는다.
- **한 번에 하나의 화면만 활성화한다. 이 제약은 토큰이 아니라 서버의 활성 공유 상태로 강제한다.** 토큰 단계에서 역할이나 권한을 갈라놓으면 학생이 공유를 시작할 수 없어 구현이 불가능하다.
- **선착순이다.** 이미 공유 중인 사람이 있으면 나중에 시작하려는 쪽을 거부한다. 나중 쪽이 기존 공유를 밀어내지 않는다. 충돌이 감지되면 **나중에 들어온 트랙**을 정리한다.
- 강사만은 예외로 진행 중인 학생 공유를 **중지**시킬 수 있다(FRD §10.2·§10.5). 중지는 자기 공유를 시작하는 것과 별개 동작이며, 강사도 학생 공유가 살아 있는 동안에는 바로 공유를 시작할 수 없다.
- 발급된 JWT는 폐기할 수 없다. 진행 중인 공유를 멈추려면 연결된 참가자를 `UpdateParticipant`로 낮추거나 `RoomService`로 트랙을 mute·제거해야 한다. 토큰 TTL(10분) 안에는 재연결로 권한이 되살아나므로 참가자가 다시 연결되는 시점에도 제약을 다시 적용해야 한다.
- 향후 DataPacket 또는 공유 정책을 변경하려면 프론트 UI와 백엔드 grant를 함께 변경한다.

## 9. 장치, 연결, 종료

### 장치

- 학생: 카메라와 마이크 테스트 모두 필수.
- 강사: 마이크와 LiveKit 연결 필수, 카메라 실패 시 제한 모드 가능.
- 입장 전 Track은 로컬 미리보기만 하고 서버로 게시하지 않는다.
- 테스트 통과 후 카메라·마이크를 끈 상태로 입장할 수 있다.

### 재연결

- LiveKit SDK 자동 재연결을 먼저 사용한다.
- `RECONNECTING` 중 `Room.connect`를 중복 호출하지 않는다.
- 최종 실패 후에만 새 `/media-token`으로 전체 재입장을 최대 3회 시도한다.
- 재시도 간격은 1초, 2초, 4초다.
- 학생은 실패 시 재입장 화면으로 이동한다.
- 강사 이탈부터 5분 타이머를 시작하고 같은 identity가 복귀하면 취소한다. 이탈 감지는 presence가 담당한다.
- 재연결 구간의 학생 분석 상태는 `측정 불가`다.

**강사 유예와 종료 안내는 presence 응답이 참가자 전원에게 전달한다.** 학생도 강사 유예가 진행 중임을 알아야 하므로(FRD §10.6), heartbeat 응답의 재연결 상태는 자기 연결 상태만이 아니라 **해당 시점의 강사 유예 여부**를 담는다.

> ⚠️ **구현 격차(2026-07-31).** 현재 `SessionPresenceService.applyPresence`는 유예 상태를 `role == INSTRUCTOR` 조건 안에서만 돌려준다. 그래서 유예 상태값은 **강사 본인에게만** 가고 학생은 받지 못한다. `SessionPresenceNotice`가 가진 "강사 연결이 끊겼습니다" 문구는 학생에게 보여줄 말인데 학생이 그 상태를 받을 수 없어 영원히 그려지지 않는다. 역할 조건을 풀어 학생 응답에도 유예 여부를 담는 후속 작업이 필요하다.

### 종료

종료 조건은 강사 명시 종료, 시작 후 3시간, 강사 비정상 이탈 후 5분 미복귀다.

```text
ENDING 전환
→ 신규 토큰·입장 차단
→ 참가자 종료 안내
→ Egress 종료 요청
→ LiveKit Room 종료
→ NOTE_PENDING 전환
→ 30분 메모 비활성 타이머
→ PROCESSING
```

Egress 최종 파일을 기다리느라 Room 종료를 지연하지 않는다.

## 10. 클라이언트 이벤트 경계

LiveKit 이벤트는 미디어 상태만 처리한다.

```text
ParticipantConnected / ParticipantDisconnected
TrackPublished / TrackUnpublished
TrackSubscribed / TrackUnsubscribed
TrackMuted / TrackUnmuted
ActiveSpeakersChanged
Reconnecting / Reconnected / Disconnected
ConnectionQualityChanged
ParticipantPermissionsChanged
MediaDevicesChanged
```

Spring Boot WebSocket은 업무 이벤트를 처리한다.

```text
CHAT_MESSAGE
HAND_RAISED / HAND_LOWERED
REACTION
SCREEN_SHARE_STARTED / SCREEN_SHARE_STOPPED
FORCE_MUTED
```

**강사 이탈·복귀와 세션 종료 안내는 WebSocket이 아니라 presence 응답이 담당한다.** heartbeat 응답의 재연결 상태에 실려 나가므로 같은 정보를 두 경로로 보내지 않는다. 자세한 것은 §9 재연결을 본다.

**강제 퇴장 기능은 없다**(FRD §10.5). 그에 해당하는 이벤트도 두지 않는다.

프론트 이벤트는 화면 표현용이며 서버의 입장·권한·녹화 사실을 확정하는 근거로 사용하지 않는다.

### 10.1 이벤트 봉투

티켓 63이 확정한 계약이다. 새 이벤트 종류를 더할 때 이 구조를 바꾸지 않는다.

```text
핸드셰이크        /ws
세션 주제         /topic/sessions/{sessionId}   주제 하나로 전부 내려오고 type 으로 갈라 처리한다
클라이언트 → 서버  /app
전송 거절 통지     /user/queue/errors            보낸 사람에게만
```

```text
eventId           서버 부여. 중복 제거 기준이며 값 자체가 발생 순서를 담는다
clientEventId     내가 보낸 것이면 내가 만든 값이 그대로 돌아온다
type              위 목록 중 하나
sender            identity · displayName · role
occurredOffsetMs  수업 시작 기준 발생 시각(ms)
deliveredAt
payload           종류별 내용
```

- `sender.identity`는 **LiveKit participant identity와 같은 값**(`p-{sessionParticipantId}`)이다. 참가자 목록과 업무 이벤트를 잇는 키를 하나로 유지한다. 이메일 등 개인 식별 정보는 담지 않는다.
- `occurredOffsetMs`는 **리포트 타임라인과 같은 축**이다. 손들기·질문을 사후 집중 흐름에 겹쳐 놓을 수 있어야 하므로 벽시계 시각이 아니라 수업 시작 기준 오프셋을 쓴다.
- 브로커는 내장 `SimpleBroker`다. 구독 정보를 프로세스 메모리에 두므로 **인스턴스를 늘리면 인스턴스 간 메시지가 오가지 않는다.** 다중화 시 외부 브로커가 필요하다.

## 11. 서버 Webhook

LiveKit 은 room·참가자·트랙·Egress 사건을 서버로 알린다. 백엔드는 그것을 받아 상태를 갱신한다.

**어떤 이벤트를 어디까지 처리할지는 이 문서가 정하지 않는다.** 목록을 여기 적으면 LiveKit 이 보내는
종류가 바뀌거나 처리 범위가 늘 때마다 낡는다. 처리 범위는 코드가, 무엇을 보장해야 하는지는 FRD 가
정본이다.

문서가 요구하는 것은 결과뿐이다.

- 서명 검증 전에 업무 처리를 하지 않는다. 검증 실패는 거부하고 상태를 반영하지 않는다(FRD `NFR-SEC-008`).
- 같은 이벤트를 두 번 반영하지 않는다(FRD `RECORD-008`).
- Egress·Ingress·Agent 같은 시스템 참가자는 인원과 출석 집계에서 제외한다.
- Webhook 은 전달이 보장되지 않는다. 누락을 전제로 상태를 보정할 경로가 있어야 한다.

무엇을 근거로 사후 자료 접근 자격을 주는지는 §5 를, 세션이 제때 종료되어야 한다는 요구는 §9 를 본다.

## 12. 녹화와 로컬 저장

RoomComposite Egress를 기본 녹화기로 사용하지 않는다. 허용된 Track만 Track Egress로 저장한 뒤 FFmpeg로 합성한다.

| Track | 원본 저장 | 최종 MP4 |
| --- | --- | --- |
| 강사 카메라 | 포함 | 포함 |
| 강사 마이크 | 포함 | 포함 |
| 강사 화면·화면 오디오 | 포함 | 포함 |
| 학생 마이크 | 익명 개별 저장 | 전체 음성으로 혼합 |
| 학생 화면·화면 오디오 | 포함 | 활성 구간 포함 |
| 학생 카메라 | 절대 저장하지 않음 | 제외 |

```text
/srv/zani/recordings/{sessionId}/
├── raw/instructor/
├── raw/participants/student-001/
├── manifest/tracks.json
├── transcript/
└── final/lecture.mp4
```

- S3 사용안은 폐기됐다.
- 파일명과 manifest에는 실제 학생 이름·이메일·사용자 ID를 넣지 않는다.
- 학생 마이크는 alias별 원본 파일을 유지하고 최종 MP4 생성 시에만 혼합한다.
- 화면 공유가 없으면 강사 카메라가 주 화면이다.
- 화면 공유가 있으면 공유 화면이 주 화면이고 강사 카메라는 우측 하단 보조 화면이다.
- 백엔드가 권한을 검사한 후 Nginx 내부 파일 전달 방식으로 제공한다.
- 90일 후 자동 삭제하며 디스크 사용량을 감시한다.
- EC2 초기화·디스크 장애 시 복구할 수 없다는 제약을 문서화한다.

## 13. FRD에서 변경된 정책

FRD 자체를 변경해야 하는 최종 결정은 하나다.

1. Amazon S3 저장 폐기 → EC2 `/srv/zani/recordings` 로컬 저장.

정원은 이 목록에서 빠졌다. 한때 "31명 이상 베스트 에포트 폐기 → 하드 캡 30명"으로 정했다가 되돌렸고, FRD §8.3의 "하드 캡을 두지 않는다"가 그대로 유효하다(§6 참고).

Track Egress 후 FFmpeg, Spring WebSocket, 익명 alias는 FRD 결과를 달성하기 위한 구현 구체화이며 제품 기능 충돌이 아니다. 준비 상태(내부 `PREPARING`)는 FRD §8.1에 요구사항으로 들어갔으므로 더 이상 구현 구체화가 아니다.

## 14. 구현 시 주의

- OpenAPI 계약을 먼저 갱신하고 FE 타입을 다시 생성한다.
- 현재 `SessionParticipant.firstJoinedAt`은 API 입장 시점에 non-null이다. 목표는 LiveKit 첫 연결 시점이므로 새 Flyway migration과 모델 변경을 함께 검토한다.
- 현재 `Session.startedAt`은 생성 시 non-null이다. `PREPARING`을 도입하면 실제 시작까지 nullable 또는 별도 시각 모델이 필요하다.
- 강사 `SessionParticipant`는 이미 세션 생성과 같은 트랜잭션에서 만든다(`NewSessionSaver`). 저장을 쪼개서 세션만 있고 멤버십이 없는 상태를 만들지 않는다.
- 공유된 V1 migration을 직접 수정하지 말고 후속 버전 migration으로 상태·참가자·로컬 저장 경로 변경을 적용한다.
- LiveKit SDK DTO와 예외가 application/domain에 누출되지 않도록 Port/Adapter를 사용한다.
- 녹화·Webhook·파일은 기술 상태지만 세션 상태 전이는 Session Aggregate 행동을 거쳐야 한다.
- 학생 카메라 Egress 요청을 만들지 않는 규칙은 테스트로 고정한다.
- 비밀값, 토큰, 실제 녹화 파일을 Git 또는 일반 로그에 기록하지 않는다.
