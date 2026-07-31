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
- 업무 이벤트는 Spring WebSocket **STOMP**와 Redis를 통해 전달하고 필요한 항목은 DB에 저장한다(§10).
- LiveKit Cloud, S3, MinIO는 운영에서 사용하지 않는다.
- 녹화 파일은 `/srv/zani/recordings`에 저장한다.

## 3. 현재 구현과 목표의 차이

2026-07-23 기준 코드에는 다음 상태가 확인됐다.

### 프론트엔드

- `livekit-client`, `@livekit/components-react` 의존성은 존재한다.
- `mediaTokenApi`, `liveKitRoom`, `RoomProvider`와 관련 테스트가 추가되어 `/media-token` 호출, `Room.connect`, SDK 재연결 이벤트 반영까지 구현돼 있다.
- `RoomScreen`은 위 연결 상태를 표시하지만 참가자·미디어 제어는 아직 fixture와 로컬 상태다.
- `CreateSetupScreen`, `PrejoinScreen`도 실제 세션 API와 장치 점검 대신 시연용 로컬 동작을 사용한다.
- 실제 Track publish/subscribe, 장치 권한 처리, 업무 WebSocket 연동은 아직 없다.

### 백엔드

- 세션 생성·목록·초대 코드 입장 API가 존재한다.
- 세션 생성 즉시 `LIVE`와 `startedAt`을 저장하고 초대 코드를 반환한다.
- Flyway V1 스키마와 `SessionParticipant` 모델·영속성 구현이 존재한다.
- `POST /sessions/join` 시 `SessionParticipant.firstJoinedAt`을 즉시 기록한다.
- 강사의 `SessionParticipant`는 세션 생성 과정에서 만들지 않는다.
- LiveKit SDK, RoomService, `/media-token`, `/start`, `/end`, Webhook, Egress 연동은 없다.
- V1의 `recording_files.storage_key` 설명은 S3 객체 키로 남아 있어 로컬 상대 경로 정책에 맞춘 후속 migration과 코드 정리가 필요하다.

목표 계약에서는 내부 `PREPARING`, `ENDING` 단계와 실제 LiveKit Webhook 기준 최초 입장을 추가해야 한다. 기존 코드를 현재 명세에 맞추는 작업은 별도 구현 티켓에서 수행한다.

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

```json
{
  "sessionId": 123,
  "roomName": "zani-dev-session-123",
  "liveKitUrl": "wss://i15a105.p.ssafy.io",
  "accessToken": "<redacted>",
  "expiresAt": "2026-07-23T15:30:00+09:00",
  "participant": {
    "identity": "p-456",
    "displayName": "김싸피",
    "role": "STUDENT",
    "recordingAlias": "student-003"
  }
}
```

- TTL은 10분이다.
- 브라우저 메모리에만 보관한다.
- 완전 재입장 시 같은 identity로 새 토큰을 발급한다.
- `ENDING` 이후에는 발급하지 않는다.
- `egressId`는 응답하지 않는다.

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

업무 이벤트는 Spring WebSocket **STOMP**가 처리한다. LiveKit DataPacket은 쓰지 않는다(§2).

목적지는 세션당 주제 하나다. 종류별로 나누면 구독·재연결·순서 보장이 종류 수만큼 늘어나는데, 어차피 같은 수업 화면이 전부 소비한다. 종류는 봉투의 `type`으로 구분한다.

```text
핸드셰이크  /ws                              CONNECT 프레임 헤더로 인증한다
구독        /topic/sessions/{sessionId}      SUBSCRIBE 시점에 세션 멤버십을 확인한다
발행        /app/sessions/{sessionId}/{종류}
거절 통지    /user/queue/errors               보낸 사람에게만 간다
스냅샷      GET /api/v1/sessions/{id}/live-state
```

브라우저 WebSocket은 핸드셰이크에 `Authorization` 헤더를 붙일 수 없고, 흔한 우회책인 쿼리 파라미터는 액세스 토큰을 프록시·액세스 로그에 남긴다. STOMP는 핸드셰이크와 별개로 CONNECT 프레임에 헤더를 실을 수 있어 토큰이 URL에 노출되지 않는다.

봉투와 스냅샷의 필드 규격은 **스토리 14의 `[API 계약]`이 기준**이다.

```text
CHAT_MESSAGE                        63
HAND_RAISED / HAND_LOWERED          64
REACTION                            64
SCREEN_SHARE_STARTED / STOPPED      65
FORCE_MUTED                         66
```

- 강제 퇴장(`PARTICIPANT_KICKED`)은 범위에서 제외됐다. 강사 제어는 강제 음소거만 제공한다(2026-07-30 확정).
- `SESSION_ENDING`·`INSTRUCTOR_DISCONNECTED`·`INSTRUCTOR_RECONNECTED`는 **구현되어 있지 않다.** presence는 REST heartbeat(FRD §10.6)로 처리하고 있어 이 채널을 쓰지 않는다. 필요해지면 담당 티켓을 먼저 정한다.
- 내장 브로커(`enableSimpleBroker`)는 구독 정보를 프로세스 메모리에 둔다. 인스턴스를 늘리면 각 인스턴스에 붙은 클라이언트끼리 메시지가 오가지 않으므로 외부 브로커로 바꿔야 한다.
- 배포에서 같은 도메인을 쓰려면 Nginx에 `/ws` location이 필요하다. `Upgrade`·`Connection` 헤더를 넘기고 `proxy_read_timeout`을 길게 잡는다 — 기본값 60초면 조용한 수업에서 1분마다 끊긴다.

프론트 이벤트는 화면 표현용이며 서버의 입장·권한·녹화 사실을 확정하는 근거로 사용하지 않는다.

## 11. 서버 Webhook

```http
POST /internal/livekit/webhook
Content-Type: application/webhook+json
Authorization: Bearer {LIVEKIT_SIGNED_JWT}
```

- Java SDK `WebhookReceiver`에 원본 body와 Authorization 헤더를 전달해 서명과 body hash를 검증한다.
- 이벤트 ID를 고유 키로 저장해 멱등 처리한다.
- 수신·검증·내구성 있는 저장 후 빠르게 2xx를 반환하고 실제 처리는 비동기로 수행한다.
- 처리 이벤트: `room_started`, `room_finished`, `participant_joined`, `participant_left`, `participant_connection_aborted`, `track_published`, `track_unpublished`, `egress_started`, `egress_updated`, `egress_ended`.
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
- 현재 세션 생성은 강사 `SessionParticipant`를 만들지 않는다. identity와 역할 일관성을 위해 생성해야 한다.
- 공유된 V1 migration을 직접 수정하지 말고 후속 버전 migration으로 상태·참가자·로컬 저장 경로 변경을 적용한다.
- LiveKit SDK DTO와 예외가 application/domain에 누출되지 않도록 Port/Adapter를 사용한다.
- 녹화·Webhook·파일은 기술 상태지만 세션 상태 전이는 Session Aggregate 행동을 거쳐야 한다.
- 학생 카메라 Egress 요청을 만들지 않는 규칙은 테스트로 고정한다.
- 비밀값, 토큰, 실제 녹화 파일을 Git 또는 일반 로그에 기록하지 않는다.
