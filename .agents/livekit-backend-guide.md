# ZANI LiveKit 백엔드 구현 가이드

## 1. 범위와 선행 규칙

이 문서는 Spring Boot가 ZANI 세션과 LiveKit을 연결하는 목표 계약을 정의한다. 전체 흐름은 [livekit-overview.md](./livekit-overview.md)를 먼저 읽는다.

백엔드 구현 시 다음 규칙이 우선한다.

- `../AGENTS.md`
- `./ddd-development-guide.md`
- `presentation -> application -> domain`, infrastructure는 inward-facing Port 구현
- LiveKit SDK·DTO·예외를 application/domain에 노출하지 않음

## 2. 현재 구현 상태

백엔드 구현 상태는
[`livekit-integration-context.md` §3](./livekit-integration-context.md#3-현재-구현-상태)이
단독으로 소유한다. **작업 시작 전에 그 절을 먼저 읽는다.** 이 문서 아래는 목표 계약이며 이미 구현된 부분과 아직 없는 부분이 섞여 있다.

작업 방식 규칙은 그대로다.

- 기존 코드를 우회하지 말고 OpenAPI, Domain 상태 전이, DB migration을 함께 변경한다.
- 공유된 `V1__create_initial_schema.sql`은 수정하지 않고 후속 버전 migration을 추가한다.

## 3. 권장 도메인 경계

세션의 업무 상태와 참가 권한은 기존 `session` 도메인이 소유한다. LiveKit은 외부 시스템이다.

```text
session.presentation
→ session.application.<use-case>
→ session.domain
→ session.application.port
← session.infrastructure.livekit
```

녹화·Webhook·Egress는 `session`이 아니라 별도 `recording` 도메인이 소유한다. room 이름과 접속 설정은 `session`이 소유하므로 `recording`은 `MediaRoomPort`를 통해서만 받는다.

현재 UseCase 패키지는 아래와 같다. 새 UseCase를 만들 때 이 이름 규칙(도메인 접미사를 붙이지 않는다)을 따른다.

```text
session.application.create          join           issuemediatoken
                    end             presence       get
                    resolveparticipant             resolveconnectedstudents
recording.application.orchestrate   webhook
```

아직 없는 것: 세션 시작(`/start`), 화면 공유 단일 활성 강제, 녹화 상태 조회. 실제 호출자가 생기는 범위만 만든다.

외부 시스템 접점은 모두 application port 뒤에 있다. LiveKit SDK 타입은 어댑터 안에만 존재한다. **새 port를 만들기 전에 이 목록을 확인한다** — 같은 역할의 port를 다른 이름으로 하나 더 만들지 않기 위해서다.

```text
session.application.port    LiveKitTokenPort               LiveKit 토큰 발급
                            MediaRoomPort                  room 이름·역파싱·접속 자격증명
                            SessionPresencePort            presence (Redis)
                            SessionActivationLockPort      강사 활성 세션 잠금 (Redis)
recording.application.port  TrackEgressPort                Track Egress 시작
                            RecordingWebhookVerifierPort   webhook 서명 검증
                            RecordingWebhookEventPort      webhook 이벤트 저장
                            RecordingOutboxPort            녹화 outbox
                            AudioStreamEgressRegistryPort  코칭 오디오 Egress 등록 (Redis)
```

`MediaRoomPort`가 room 관련 port다. `LiveKitRoomPort`라는 이름을 새로 만들지 않는다. 참가자 제어(`UpdateParticipant`·트랙 mute)용 port는 아직 없으므로 필요해질 때 만든다.

벤더 DTO 대신 내부 Command/Result 또는 application-owned 값 객체를 사용한다.

## 4. 세션 상태

목표 상태:

```text
PREPARING
→ LIVE
→ ENDING
→ NOTE_PENDING
→ PROCESSING
→ COMPLETED | FAILED
→ DELETED
```

주요 불변식:

- 한 강사는 `PREPARING` 또는 `LIVE` 세션을 동시에 하나만 가질 수 있다.
- `PREPARING`은 강사만 미디어 토큰을 받을 수 있다.
- 학생은 `LIVE` 세션에서만 join·토큰 발급이 가능하다.
- `ENDING`부터 신규 입장·토큰 발급·화면 공유 시작을 차단한다.
- `start`, `end`는 멱등해야 한다.
- 최대 수업 시간은 실제 `startedAt`부터 3시간이다.

세션 상태 전이는 `Session` Aggregate 행동으로 구현한다. LiveKit 호출 성공 여부를 조정하는 Application Service가 직접 상태 필드를 바꾸지 않는다.

## 5. 데이터 모델 변경 포인트

현재 Flyway V1에 기본 테이블이 존재한다. 다음 정보를 저장하도록 후속 버전 migration을 작성한다.

### Session

```text
room_name
status: PREPARING, LIVE, ENDING, ...
started_at nullable
recording_started_at nullable
ended_at nullable
note_due_at nullable
limited_mode
```

### SessionParticipant

```text
id                         # 기존 TsidGenerator
session_id
user_id
role
recording_alias
first_joined_at nullable   # LiveKit 첫 연결 성공
last_joined_at nullable
last_left_at nullable
```

승인 플로우를 두지 않으므로 참가자 행에 공유 승인 컬럼을 두지 않는다. 활성 공유는 세션 단위 서버 상태로 관리한다.

- `(session_id, user_id)`는 unique다.
- `recording_alias`는 세션 안에서 unique다.
- 강사도 `SessionParticipant`를 가진다.
- LiveKit identity는 `p-{sessionParticipantId}`다.

### 녹화

최소 개념:

```text
RecordingJob
RecordedTrack
LiveKitWebhookEvent
```

Webhook event ID, Egress ID, Track SID, source, alias, 시작·종료 시각, 상대 파일 경로, 상태, 실패 사유를 저장한다.

## 6. Room과 초대 정책

Room 이름:

```text
zani-{environment}-session-{sessionId}
```

Room 생성 설정:

```text
metadata.sessionId={sessionId}
```

- Room 이름은 백엔드에서만 만든다.
- **정원 하드 캡을 두지 않는다.** `maxParticipants`를 설정하지 않고 API에서도 인원 초과를 차단하지 않는다. 동시 30명까지가 성능 보장 대상이며(FRD §8.3), 31명 이상은 동작을 보장하지 않을 뿐 막지 않는다. 기준은 [`livekit-integration-context.md`](./livekit-integration-context.md) §6이 소유한다.

초대 코드:

- canonical 값은 모호한 문자를 제외한 대문자 영문·숫자 8자리다.
- DB 저장 예: `A7KM2PQR`.
- UI 표시 예: `A7KM-2PQR`.
- 입력 시 하이픈 제거와 대문자 정규화 후 검증한다.
- `LIVE` 동안만 유효하고 종료 즉시 만료한다.

무작위 대입 시도 제한은 두지 않는다. 8자 × 32자 = 1.1조 가지이고 수업이 3시간만 살아 있어 탐색 창도 짧다. 구현하지 않을 요구를 남겨 두면 계속 미이행으로 잡힌다.

현재 구현과 다른 지점은 [`livekit-integration-context.md` §3](./livekit-integration-context.md#3-현재-구현-상태)이 소유한다.

## 7. API 목표 계약

공통 응답은 프로젝트의 `ApiResponse<T>` 형식을 유지한다.

### 세션 준비 생성

```http
POST /api/v1/sessions
Authorization: Bearer {ZANI_ACCESS_TOKEN}

{
  "title": "React 상태관리 심화"
}
```

목표 결과:

- `PREPARING` Session 생성
- 강사 SessionParticipant 생성
- LiveKit Room 생성
- `sessionId` 반환
- 아직 초대 코드로 학생 입장을 허용하지 않음

### 미디어 토큰

```http
POST /api/v1/sessions/{sessionId}/media-token
Authorization: Bearer {ZANI_ACCESS_TOKEN}

{}
```

응답 스키마는 [`livekit-integration-context.md` §7](./livekit-integration-context.md#7-미디어-토큰)이
소유한다. 여기에 중복해 적지 않는다. 요약하면 `participant` 중첩 객체가 없는 평면 구조이고,
`role`과 `displayName`은 응답이 아니라 LiveKit 토큰의 metadata·name으로 전달된다.

검증:

- JWT 사용자와 SessionParticipant 일치. 멤버십을 세션 조회보다 먼저 확인해 비멤버에게 세션 존재·상태를 노출하지 않는다.
- 강사: `PREPARING` 또는 `LIVE`
- 학생: `LIVE`
- 종료·삭제 상태 차단
- 역할·identity·name·grant를 요청 body에서 받지 않음
- TTL 10분
- `egressId` 미노출

### 세션 시작

```http
POST /api/v1/sessions/{sessionId}/start
```

- 강사만 호출한다.
- RoomService로 강사 참가자와 microphone Track을 확인한다.
- camera가 없으면 제한 모드로 기록할 수 있다.
- 기존 허용 Track Egress를 시작한다.
- 성공 시 `LIVE`, `startedAt`, `recordingStartedAt`을 기록한다.
- 활성화된 초대 코드·링크와 3시간 종료 시각을 반환한다.

### 학생 참가

```http
POST /api/v1/sessions/join

{
  "inviteCode": "A7KM-2PQR"
}
```

- 로그인 사용자만 가능하다.
- 코드를 정규화한다.
- `LIVE`, 정원, 기존 참가 관계를 검사한다.
- 기존 관계가 있으면 멱등하게 재사용한다.
- 이 API 시점에는 `firstJoinedAt`과 사후 접근 자격을 확정하지 않는다.

### 세션 종료

```http
POST /api/v1/sessions/{sessionId}/end
```

- 강사만 호출한다.
- `ENDING`으로 전환하고 신규 입장·토큰을 차단한다.
- WebSocket 종료 안내, Egress stop, DeleteRoom 순으로 요청한다.
- Egress 최종 완료를 기다리느라 Room 종료를 지연하지 않는다.
- `NOTE_PENDING`과 30분 메모 타이머를 기록한다.

## 8. 토큰 grant

두 역할 공통(2026-07-30 확정 — 화면 공유에 역할 제한을 두지 않는다):

```text
roomJoin=true
canSubscribe=true
canPublish=true
canPublishSources=[CAMERA, MICROPHONE, SCREEN_SHARE, SCREEN_SHARE_AUDIO]
canPublishData=false
```

JWT 클레임에는 `TrackSource`의 소문자 표기(`camera`, `screen_share`, …)를 실어야 한다. 위 대문자 표기는 protobuf enum 이름이며, 그대로 실으면 publish가 조용히 전부 막힌다. 자세한 기준은 [`livekit-integration-context.md`](./livekit-integration-context.md) §8이 소유한다.

부여하지 않는 권한:

```text
roomCreate
roomList
roomAdmin
roomRecord
canUpdateOwnMetadata
```

API key와 secret은 서버 환경 변수에서만 읽고 응답·로그·DB에 저장하지 않는다.

## 9. 화면 공유

역할 제한이 없다. 두 역할 모두 기본 토큰으로 공유를 시작할 수 있고, 승인 플로우는 두지 않는다. 제약은 **한 세션에 활성 공유 하나**이며 서버의 활성 공유 상태로 강제한다. **선착순이라 나중 요청을 거부하며 기존 공유를 밀어내지 않는다**(FRD §10.2).

```mermaid
sequenceDiagram
    participant P as 참가자 FE
    participant BE as Spring Boot
    participant LK as LiveKit

    P->>LK: 화면·화면 오디오 publish
    LK->>BE: 트랙 발행 통지
    BE->>BE: 활성 공유 단일성 검사
    BE->>LK: 이미 공유자가 있으면 방금 들어온 트랙을 mute·제거
    BE-->>P: 공유 상태 브로드캐스트(STOMP)
```

- 단일성 판정은 서버가 소유한다. 클라이언트 상태를 근거로 삼지 않는다.
- **정리 대상은 나중에 들어온 트랙이다.** 기존 공유자의 트랙은 건드리지 않는다. 화면·화면 오디오 두 트랙을 함께 정리한다.
- 강사의 학생 공유 중지는 이와 별개 동작이다. 강사가 명시적으로 중지를 요청한 경우에만 **기존** 트랙을 제거하며, 강사가 자기 공유를 시작하려는 것만으로는 학생 공유가 밀려나지 않는다.
- 발급된 JWT는 폐기할 수 없다. 진행 중인 공유를 멈추려면 `UpdateParticipant`로 연결된 참가자의 source를 낮추거나 `RoomService`로 트랙을 mute·제거한다.
- 토큰 TTL(10분) 안에는 재연결로 권한이 되살아난다. 따라서 참가자가 다시 연결되는 시점에도 제약을 다시 적용해야 한다.
- 트랙이 새로 발행될 때마다 단일 공유를 다시 검증한다.

## 10. 애플리케이션 WebSocket

LiveKit DataPacket은 사용하지 않고 `canPublishData=false`를 명시한다.

Spring WebSocket이 담당할 이벤트:

```text
CHAT_MESSAGE                                    티켓 63 (구현됨)
HAND_RAISED / HAND_LOWERED / REACTION           티켓 64
SCREEN_SHARE_STARTED / SCREEN_SHARE_STOPPED     티켓 65
FORCE_MUTED                                     티켓 66
```

봉투 구조와 목적지는 [`livekit-integration-context.md`](./livekit-integration-context.md) §10.1이 소유한다. 새 종류를 더할 때 `SessionEventType`에 값을 추가하고 봉투는 그대로 쓴다.

**강사 이탈·복귀와 세션 종료 안내는 이 채널이 아니다.** presence 응답이 담당한다(§12 참고). 같은 정보를 두 경로로 보내지 않는다.

**강제 퇴장 기능은 없다**(FRD §10.5). 그에 해당하는 이벤트도 두지 않는다.

`FORCE_MUTED`는 LiveKit `MutePublishedTrack` 호출이 필요하므로 `RoomServiceClient` 도입이 선행 조건이다(§6·§9와 같은 선행).

- JWT, 세션 참여 관계와 역할을 handshake 및 메시지 처리마다 검증한다.
- 공개 채팅과 학생-강사 1:1 채팅 destination을 분리한다.
- 학생 간 1:1 메시지는 거부한다.
- 저장이 필요한 채팅·손들기·반응은 DB에 기록하고 Redis는 전달·fan-out에 사용한다.
- 클라이언트가 보낸 역할·displayName을 신뢰하지 않는다.

## 11. Webhook

**어떤 이벤트를 어디까지 처리할지는 이 문서가 정하지 않는다.** 목록을 적으면 LiveKit 이 보내는 종류가
바뀌거나 처리 범위가 늘 때마다 낡는다. 처리 범위는 코드가 정본이고, 기준은
[`livekit-integration-context.md`](./livekit-integration-context.md) §11 이 소유한다.

지켜야 하는 것:

- 수신 경로를 일반 ZANI JWT 인증과 분리한다. 인증은 LiveKit 서명 토큰으로만 한다.
- **서명 검증 전에 업무 처리를 하지 않는다.** 검증 실패는 거부하고 상태를 반영하지 않는다(FRD `NFR-SEC-008`).
- 이벤트 식별자에 unique constraint 를 두어 같은 이벤트를 두 번 반영하지 않는다(FRD `RECORD-008`).
- 검증과 내구성 있는 저장을 마친 뒤 빠르게 2xx 를 반환하고 실제 처리는 비동기로 한다.
- 중복·역순 도착이 상태를 역행시키지 않게 한다. 비동기 처리가 실패하면 재시도 가능한 상태로 남긴다.
- 가능하면 내부 경로에서만 접근하게 둔다.
- Egress·Ingress·Agent 같은 시스템 참가자는 일반 인원·출석·구독 제어에서 제외한다.
- Webhook 은 전달이 보장되지 않는다. 누락을 전제로 상태를 보정할 경로를 둔다.

## 12. 연결과 자동 종료

- 학생이 이탈하면 퇴장 상태만 갱신한다.
- 강사면 5분 후 실행되는 종료 작업을 예약한다.
- 같은 identity가 5분 안에 다시 연결되면 작업을 취소한다.
- 수업 시작 시 2시간 50분 알림과 3시간 종료 작업을 예약한다.
- 강사 명시 종료, 3시간, 강사 5분 미복귀는 같은 `EndSessionUseCase`를 호출한다.
- 학생 재연결 중 분석 구간은 `측정 불가` 이벤트로 저장한다.

토큰 TTL은 10분이다. `ENDING` 이후에는 재발급하지 않는다. 종료된 세션에 새 연결이나 room 재시작이 감지되면 기존 토큰 재사용으로 보고 즉시 제거·종료한다.

## 13. Egress 허용 정책

트랙이 발행되면 다음 정책으로 Track Egress를 시작한다.

| 역할·source | 처리 |
| --- | --- |
| 강사 CAMERA | 저장 |
| 강사 MICROPHONE | 저장 |
| 강사 SCREEN_SHARE | 저장 |
| 강사 SCREEN_SHARE_AUDIO | 저장 |
| 학생 MICROPHONE | recordingAlias별 저장 |
| 학생 SCREEN_SHARE | 저장 |
| 학생 SCREEN_SHARE_AUDIO | 저장 |
| 학생 CAMERA | **Egress 요청 생성 금지** |

> ⚠️ **구현 격차(2026-07-30).** 승인 개념이 폐지되어 활성 공유는 소유자와 무관하게 저장 대상이다. 그런데 `RecordingTrackPolicy.decide`는 아직 `studentScreenShareApproved` 플래그로 판단하고, `RecordingWebhookService`가 그 값을 `false`로 고정해 넘긴다. 결과적으로 **학생 공유는 publish 되지만 저장되지 않고 로그도 남지 않는다.** 이 표를 만족시키려면 플래그를 제거하는 후속 작업이 필요하다.
>
> `.agents/frd.md` §10.2·§15.1 과 `LIVE-002` 는 2026-07-31 에 개정됐다 — 승인 개념이 빠지고 선착순 단일 활성 공유로 바뀌었다. **이제 문서 쪽은 정합하며 남은 것은 코드다.** `studentScreenShareApproved` 파라미터를 제거하고 학생 `SCREEN_SHARE`·`SCREEN_SHARE_AUDIO` 를 `RECORD` 로 바꾼다. `RecordingTrackPolicy` 의 클래스 javadoc("강사 승인 중에만 저장하며")과 `RecordingTrackPolicyTest` 를 함께 고쳐야 한다.

Track Egress는 원본 codec으로 저장한다. 예를 들어 VP8은 WebM, H.264는 MP4, Opus는 Ogg가 될 수 있으므로 확장자를 고정 가정하지 않고 Egress 결과를 manifest에 기록한다.

학생이 마이크를 껐다 켜 새 Track이 생기면 alias 디렉터리에 새 segment로 저장하고 전체 수업 기준 offset을 기록한다.

## 14. 로컬 저장과 파일 제공

S3는 사용하지 않는다.

```text
/srv/zani/recordings/{sessionId}/
├── raw/instructor/
├── raw/participants/student-001/
├── manifest/tracks.json
├── transcript/
└── final/lecture.mp4
```

manifest 스키마는 이 문서가 소유하지 않는다. 필드 이름과 불변식은
[`media-finalize-recording-guide.md`](./media-finalize-recording-guide.md) §3이 정본이며,
후처리 Worker와 `RecordingManifest`가 그 계약을 양쪽에서 강제한다. manifest를 만들거나
읽는 코드를 쓸 때는 그 문서를 본다.

DB에는 그 밖에 Egress 운영 정보(egress ID, Egress 상태, Track SID, 발행·중단 시각, 파일 크기, 실패 코드)를 함께 남긴다. manifest에 넣을 필드와 DB에만 둘 필드를 섞지 않는다.

- manifest에는 학생 실제 이름·이메일·userId를 넣지 않는다.
- DB에서만 `SessionParticipant`와 alias를 연결한다.
- 상대 경로만 DB와 manifest에 저장한다.
- Spring Boot가 다운로드 권한을 검사한 후 Nginx `X-Accel-Redirect` 같은 내부 전달을 사용한다.
- `/srv/zani/recordings`를 정적 파일 루트로 공개하지 않는다.
- 종료 90일 후 파일과 관련 결과를 삭제한다.
- 디스크 임계치와 삭제 실패를 모니터링한다.

## 15. 후처리

```text
Egress 종료
→ Track 파일·manifest 검증
→ 학생별 Whisper 전사
→ 시간축 정렬
→ 강사·학생 음성 및 화면 오디오 혼합
→ 화면 공유 구간과 강사 카메라 합성
→ final/lecture.mp4
```

- 학생 원본 오디오는 `student-001` 단위로 계속 보존한다.
- 전체 음성 혼합은 최종 MP4 생성 시에만 한다.
- 공유 화면이 없으면 강사 카메라가 주 화면이다.
- 공유 화면이 있으면 공유 화면이 주 화면이고 강사 카메라는 우측 하단이다.

병합 단계의 레이아웃 도출, 오디오 혼합, 출력 규격, 검증, 종료 코드는
[`media-finalize-recording-guide.md`](./media-finalize-recording-guide.md)가 정본이다.
이 문서는 그 Worker를 언제 호출하고 결과 상태를 어디에 남기는지만 다룬다.

manifest에 학생 카메라 트랙이 들어 있을 때의 처리는 두 문서가 어긋나 있다. 이 문서는 원래
후처리를 실패시키라고 규정했지만, 현재 Worker는 **해당 트랙을 제외하고 경고만 남기며 계속
진행**한다. 더 강한 쪽으로 합의된 바가 없으므로 지금 동작은 Worker 쪽이며, 결정 전까지 어느
규칙도 근거로 삼지 않는다. `media-finalize-recording-guide.md` §9에 같은 미결 사항이 있다.

애초에 학생 카메라는 Egress 요청 자체를 만들지 않으므로(§13) 정상 경로에서는 manifest에 들어올 수 없다.

녹화 실행 상태(`RecordingStatus`, V1 스키마의 `status` 컬럼 값과 일치):

```text
STARTING → RECORDING → COMPLETE | PARTIAL | FAILED
```

## 16. 오류 계약

| 상황 | 권장 응답 |
| --- | --- |
| 인증 없음 | `401` |
| 세션 참가자 아님·역할 불일치 | `403` |
| 세션·초대 코드 없음 | `404` |
| 종료·시작 불가능 상태 | `409` |
| LiveKit 호출 실패 | `502` |
| LiveKit timeout·일시 불가 | `503` 또는 재시도 상태 |

Vendor 예외는 infrastructure adapter에서 application-owned ErrorCode로 변환한다.

## 17. 보안

- LiveKit API secret, Webhook 서명 토큰, 사용자 media token을 로그에 출력하지 않는다.
- `/media-token` 응답에 API secret, Egress ID, 내부 파일 경로를 포함하지 않는다.
- Webhook은 raw body 검증 전 업무 처리하지 않는다.
- 프론트엔드가 보낸 role, identity, displayName, grant를 무시한다.
- Room 이름과 participant identity를 DB에서 재구성한다.
- 종료 세션·미등록 identity의 Track은 즉시 차단한다.
- 녹화 파일 다운로드마다 로그인·참가 관계·자료 권한을 검사한다.

## 18. 테스트 범위

### Domain/Application

- `PREPARING → LIVE → ENDING → NOTE_PENDING` 상태 전이
- 한 강사 하나의 활성 세션
- 역할과 무관하게 동일한 토큰 grant
- 종료 상태 토큰 발급 차단
- 화면 공유 단일 활성 강제와 중지
- 3시간 종료와 강사 5분 미복귀

### Presentation

- 요청 body가 role·identity·grant를 받지 않음
- 인증·인가·오류 코드
- OpenAPI 계약과 실제 response 일치

### Infrastructure

- LiveKit adapter 요청·응답 mapping
- Webhook 서명·body hash·중복·역순 처리
- 학생 CAMERA에서 Egress 호출이 0회임을 검증
- 학생별 MICROPHONE Egress path가 alias를 사용함
- 종료된 Room 재생성 감지
- 로컬 파일 경로 탈출 방지

### 통합

- 강사 생성부터 Egress 시작까지
- 학생 장치 통과·join·LiveKit 첫 입장 기록
- 화면 공유 단일 활성 강제
- 재연결과 5분 자동 종료
- 종료 후 익명 오디오·manifest·최종 MP4

## 19. 권장 구현 순서

1. OpenAPI와 FE 생성 타입 변경
2. DB migration과 Session 상태 전이
3. 강사 SessionParticipant·identity·recordingAlias
4. LiveKit Port/Adapter와 Room 생성
5. `/media-token`과 grant 테스트
6. `/start`, Egress 조정, 초대 활성화
7. 학생 join과 실제 연결 기준 첫 입장 확정
8. Spring WebSocket 업무 이벤트
9. 화면 공유 단일 활성 강제
10. `/end`, 3시간·5분 스케줄링
11. Track Egress manifest와 로컬 파일 제공
12. FFmpeg·Whisper 후처리 연결

## 20. 완료 체크리스트

- [ ] 기존 DDD 의존 방향을 지킨다.
- [ ] OpenAPI와 실제 Controller가 일치한다.
- [ ] 강사와 학생 모두 SessionParticipant를 가진다.
- [ ] `firstJoinedAt`은 실제 LiveKit 첫 연결에서 확정한다.
- [ ] 모든 토큰 값은 서버가 결정하고 TTL은 10분이다.
- [ ] 모든 역할 토큰에 Data grant가 없고 publish source가 카메라·마이크·화면 공유로 제한된다.
- [ ] Webhook이 서명·중복·역순을 안전하게 처리한다.
- [ ] 학생 CAMERA에 Egress를 시작하지 않는다.
- [ ] 학생 오디오는 alias별로 분리된다.
- [ ] S3 코드·환경 변수·IAM 의존성이 없다.
- [ ] 로컬 녹화 파일을 외부 정적 경로로 공개하지 않는다.
