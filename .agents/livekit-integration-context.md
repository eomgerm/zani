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
> 확인 시점: 2026-07-30.

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

### 프론트엔드 — 없음

- 업무 WebSocket(§10의 이벤트 9종). STOMP·SockJS 클라이언트가 없고 이벤트 문자열도 저장소에 없다. 채팅·손들기·반응 UI도 없다.

### 백엔드 — 구현됨

- 엔드포인트 6개. `POST /api/v1/sessions`, `GET /api/v1/sessions`, `POST /api/v1/sessions/join`, `POST /api/v1/sessions/{sessionId}/media-token`, `POST /api/v1/sessions/{sessionId}/end`, `POST /api/v1/sessions/{sessionId}/presence`.
- LiveKit 토큰 발급. `LiveKitTokenAdapter`가 §8의 grant를 그대로 발급한다. `LiveKitProperties.tokenTtl` 기본값 10분, `LiveKitRoomNames`가 §4의 이름 규칙과 역파싱을 소유한다.
- Webhook 수신과 서명 검증. `RecordingWebhookController`, `LiveKitWebhookVerifierAdapter`(SDK `WebhookReceiver`), 이벤트 ID 기준 멱등 저장(Flyway V3).
- Track Egress 시작. `LiveKitTrackEgressAdapter`, 허용 정책 `RecordingTrackPolicy`, 출력 경로 `{basePath}/{sessionId}/raw/instructor|participants/{alias}/`.
- 녹화 outbox와 릴레이. `RecordingOutbox*`, `RecordingOrchestrator`, `RecordingOutboxRelayScheduler`(Flyway V2).
- 강사 `SessionParticipant`를 세션 생성과 **같은 트랜잭션**에서 만든다(`NewSessionSaver`). 이 행이 없으면 강사가 자기가 만든 방에서 403을 받아 카메라·마이크를 켤 수 없다.
- Flyway V1~V5.

LiveKit 코드가 아예 없다는 과거 서술은 더 이상 맞지 않는다.

### 백엔드 — 없음

- **`RoomServiceClient`를 쓰지 않는다.** `LiveKitMediaRoomAdapter`는 room 이름 계산과 자격증명 노출만 한다. 그래서 `CreateRoom`, `DeleteRoom`, `UpdateParticipant`, 트랙 mute가 전부 없다. §6의 `maxParticipants=30`, §8·§9의 진행 중 공유 강제 중지, §9 종료 절차의 Room 종료가 여기에 걸린다.
- `POST /{sessionId}/start`. §5가 필수 단계로 서술하지만 매핑이 없다. FE에는 `startSessionApi`가 이미 있어 호출하면 404다.
- 정원 30명 검사. `SESSION_CAPACITY_REACHED`가 저장소에 없다. §6의 API 차단과 LiveKit 최종 방어 모두 미구현이다.
- 업무 WebSocket(§10). STOMP broker가 없다. 저장소의 유일한 WebSocket은 `audioclip`의 `EgressAudioWebSocketHandler`이며, 이건 Egress가 코칭용 오디오를 밀어넣는 **수신** 소켓이라 업무 이벤트와 무관하다.
- 화면 공유 단일 활성 강제(§8·§9). `track_published` 수신은 하지만 단일성 검사가 없다.
- 세션 상태. `SessionStatus`는 `LIVE`, `ENDED` 두 값뿐이다. §9의 `ENDING`·`NOTE_PENDING`·`PROCESSING`과 내부 `PREPARING`이 없다. 세션 생성이 즉시 `LIVE`이고 `startedAt`을 즉시 기록하며, `expiresAt`은 `startedAt + 3시간`으로 계산한다.
- Webhook 처리 이벤트가 4종뿐이다. `RecordingWebhookEventType`은 `TRACK_PUBLISHED`, `EGRESS_STARTED`, `EGRESS_UPDATED`, `EGRESS_ENDED`만 처리하고 나머지는 무시한다. §11이 나열한 `room_started`, `room_finished`, `participant_joined`, `participant_left`, `participant_connection_aborted`, `track_unpublished`는 처리하지 않는다. 따라서 §5의 "`participant_joined` Webhook에서 `firstJoinedAt` 확정"도 미구현이며, 실제로는 `POST /sessions/join` 시점에 즉시 기록한다.
- 초대 코드의 `LIVE` 상태 검사. `SessionJoinService`가 상태를 보지 않아 종료된 세션 코드로도 참가 행이 생긴다. 실제 차단은 `/media-token`이 `ENDED`를 거부하는 지점에서 일어난다.
- `recording_files.storage_key`의 V1 COMMENT가 여전히 "비공개 S3 객체 키"다. 로컬 상대 경로 정책에 맞춘 후속 migration이 필요하다.

### 이 문서의 목표 계약에 없는 구현

문서보다 코드가 앞서 있는 부분이다. 관련 작업을 할 때 이 사실을 모르면 중복 구현이 된다.

- **presence heartbeat.** 강사 5분 유예의 트리거가 문서(§9·§12)의 `participant_left`/`participant_joined` Webhook이 아니라 **heartbeat**다. `SessionPresenceService`가 Redis TTL로 관리하며, presence TTL은 30초, 강사 유예는 5분이다. 별도 스케줄러가 없고 **유예 만료 뒤 도착한 heartbeat가 종료를 트리거**한다. 참가자에게는 `ReconnectStatus`(`CONNECTED`, `RECONNECTED`, `GRACE_PERIOD`, `DISCONNECTED`, `SESSION_ENDED`)를 돌려준다. 5분이라는 값만 문서와 같고 메커니즘이 다르다.
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
6. LiveKit 연결 성공 Webhook에서 `firstJoinedAt`과 사후 자료 접근 자격을 확정한다.

API만 호출하고 실제 미디어 연결에 성공하지 않은 사용자는 사후 자료 접근 자격을 얻지 않는다.

## 6. 초대와 정원

- 정규화된 초대 코드는 모호한 문자를 제외한 대문자 영문·숫자 8자리다.
- 저장 예: `A7KM2PQR`, 표시 예: `A7KM-2PQR`.
- **현재 `InviteCodeGenerator.ALPHABET`은 `A-Z0-9` 36자 전체이며 모호한 문자를 제외하지 않는다.** `I`·`O`·`0`·`1`이 함께 나올 수 있다. 제외 규칙은 미구현이다.
- 로그인 사용자만 사용할 수 있다.
- `LIVE` 상태에서만 유효하며 종료 즉시 만료한다.
- 학생은 강사 승인 없이 입장한다.
- 강제 퇴장은 현재 연결만 끊으며 재입장과 사후 접근 자격을 취소하지 않는다.
- 같은 `(sessionId, userId)` 참가 관계는 하나만 존재한다.
- 강사를 포함한 일반 참가자 하드 캡은 30명이다.
- 초과 입장은 `409 SESSION_CAPACITY_REACHED`로 차단한다.
- LiveKit Room의 `max_participants`도 30으로 설정한다.

이 정원 정책은 FRD의 “31명 이상 베스트 에포트”를 폐기한 최신 결정이다.

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
- 발급된 JWT는 폐기할 수 없다. 진행 중인 공유를 멈추려면 연결된 참가자를 `UpdateParticipant`로 낮추거나 `RoomService`로 트랙을 mute·제거해야 한다. 토큰 TTL(10분) 안에는 재연결로 권한이 되살아나므로 `participant_joined` 시점에도 제약을 다시 적용해야 한다.
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
- 강사 이탈 Webhook부터 5분 타이머를 시작하고 같은 identity가 복귀하면 취소한다.
- 재연결 구간의 학생 분석 상태는 `측정 불가`다.

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
SESSION_ENDING
INSTRUCTOR_DISCONNECTED / INSTRUCTOR_RECONNECTED
SCREEN_SHARE_STARTED / SCREEN_SHARE_STOPPED
CHAT_MESSAGE
HAND_RAISED / HAND_LOWERED
REACTION
PARTICIPANT_KICKED
```

프론트 이벤트는 화면 표현용이며 서버의 입장·권한·녹화 사실을 확정하는 근거로 사용하지 않는다.

## 11. 서버 Webhook

```http
POST /api/v1/internal/recordings/webhook
Content-Type: application/webhook+json
Authorization: Bearer {LIVEKIT_SIGNED_JWT}
```

- Java SDK `WebhookReceiver`에 원본 body와 Authorization 헤더를 전달해 서명과 body hash를 검증한다. LiveKit 설정에 따라 헤더가 `Bearer <token>` 형태로 올 수 있어 접두어를 허용한다.
- 이벤트 ID를 고유 키로 저장해 멱등 처리한다.
- 수신·검증·내구성 있는 저장 후 빠르게 2xx를 반환하고 실제 처리는 비동기로 수행한다. 처리 준비가 안 됐으면(예: `recordings` 행 미커밋) 5xx로 응답해 LiveKit 재전송에서 재처리한다.
- 목표 처리 이벤트: `room_started`, `room_finished`, `participant_joined`, `participant_left`, `participant_connection_aborted`, `track_published`, `track_unpublished`, `egress_started`, `egress_updated`, `egress_ended`. **현재 구현은 이 중 4종만 처리한다(§3 참고).** 나머지는 무시하고 2xx만 반환한다.
- 시스템/Egress 참가자는 인원과 출석에서 제외한다.
- 종료된 세션의 `room_started` 또는 `participant_joined`는 즉시 제거·종료한다.
- Webhook 누락에 대비해 주기적으로 RoomService와 DB를 대조한다.

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

FRD 자체를 변경해야 하는 최종 결정은 두 개다.

1. 인원 무제한 및 31명 이상 베스트 에포트 폐기 → 강사 포함 30명 하드 캡.
2. Amazon S3 저장 폐기 → EC2 `/srv/zani/recordings` 로컬 저장.

내부 `PREPARING`, Track Egress 후 FFmpeg, Spring WebSocket, 익명 alias는 FRD 결과를 달성하기 위한 구현 구체화이며 제품 기능 충돌이 아니다.

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
