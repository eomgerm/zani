# ZANI LiveKit 프론트엔드 구현 가이드

## 1. 범위

이 문서는 Next.js 프론트엔드가 Spring Boot와 LiveKit을 연결하는 방법을 정의한다. 전체 구조는 [livekit-overview.md](./livekit-overview.md)를 먼저 읽는다.

현재 `fe/package.json`에는 다음 의존성이 이미 있다.

```text
livekit-client
@livekit/components-react
```

프론트엔드 구현 상태는
[`livekit-integration-context.md` §3](./livekit-integration-context.md#3-현재-구현-상태)이
단독으로 소유한다. **작업 시작 전에 그 절을 먼저 읽는다.** 이 문서 아래 내용 중 상당 부분은 이미 구현돼 있고, 남은 큰 공백은 업무 WebSocket(§10)이다.

## 2. 프론트엔드가 결정하면 안 되는 값

다음 값은 백엔드 응답만 사용한다.

- LiveKit Room 이름
- participant identity
- 표시 이름과 역할
- publish/subscribe grant
- 녹화 alias
- Egress ID와 녹화 대상

프론트엔드가 `/media-token`에 보내는 업무 값은 없다.

## 3. 화면별 흐름

### 강사 방 생성

```text
CreateSetup
→ 브라우저·마이크·카메라 검사
→ POST /api/v1/sessions
→ POST /api/v1/sessions/{id}/media-token
→ LiveKit connect·마이크 publish
→ POST /api/v1/sessions/{id}/start
→ 초대 링크 표시
→ RoomScreen
```

- 초대 링크는 `/start` 성공 전 학생에게 노출하지 않는다.
- 강사 카메라 실패 시 제한 모드 확인 UI를 표시할 수 있다.
- 강사 마이크 또는 LiveKit 연결 실패 시 방 생성 완료로 이동하지 않는다.

### 학생 입장

```text
/join/{inviteCode}
→ 로그인
→ 초대 코드 검증
→ Prejoin
→ Chrome·카메라·마이크·고지 통과
→ POST /api/v1/sessions/join
→ POST /api/v1/sessions/{id}/media-token
→ LiveKit connect
→ RoomScreen
```

- 학생은 장치 테스트 실패 시 입장할 수 없다.
- 권한 거부, 장치 없음, 다른 앱 점유, 미지원 브라우저를 구분해 안내한다.
- 테스트가 끝난 뒤 카메라·마이크를 꺼서 입장하는 것은 허용한다.

## 4. 미디어 토큰 사용

```http
POST /api/v1/sessions/{sessionId}/media-token
Authorization: Bearer {ZANI_ACCESS_TOKEN}
Accept: application/json
```

본문을 보내지 않는다. 보낼 업무 값이 없다.

Bearer 헤더는 생략할 수 없다. 쿠키에는 refresh 토큰만 있고 그마저 `/auth/refresh` 경로 전용이라, 헤더를 빼면 무조건 401이 되어 강의실이 LiveKit에 붙지 못한다.

응답은 공통 `ApiResponse<T>` 봉투의 `data`에 담긴다. 현재 형태다(`mediaTokenApi.ts`).

```ts
type MediaToken = {
  liveKitUrl: string;
  accessToken: string;
  roomName: string;
  participantIdentity: string;
  /** LiveKit 토큰 만료 시각(TTL 10분). */
  expiresAt: string;
  /** 최대 수업 시간(3시간) 도달로 자동 종료될 시각. 종료 임박 안내의 기준. */
  sessionExpiresAt: string;
  /** 강의명. 서버가 안 내려주는 구성도 있어 필수 검증에 넣지 않는다. */
  sessionTitle: string | null;
};
```

- `participant` 중첩 객체는 없다. `participantIdentity` 평면 필드다.
- **`role`은 이 응답에 오지 않는다.** LiveKit 토큰 metadata JSON(`{"role":"INSTRUCTOR"}`)에 실려 오므로 `participant.metadata`에서 읽는다. metadata가 없거나 JSON이 깨져도 화면이 죽지 않게 `student`로 폴백한다(`useRoomParticipants.ts`). 역할이 바뀔 수 있으므로 metadata 변경 이벤트를 구독한다.
- `displayName`도 응답에 없다. LiveKit participant의 `name`을 쓴다.
- `recordingAlias`는 프론트엔드에 내려오지 않는다. 녹화 대상 결정은 서버 몫이므로 필요하지 않다.
- 토큰은 React 상태나 Room 인스턴스가 살아 있는 메모리에만 둔다.
- `localStorage`, `sessionStorage`, 쿠키, 로그에 저장하지 않는다.
- URL query에 토큰을 직접 넣지 않는다.
- 새로고침 또는 완전 재입장 시 다시 발급받는다.

## 5. Prejoin 장치 검사

사용자 버튼 클릭 이후에만 `getUserMedia` 또는 LiveKit local track API를 호출한다.

학생 통과 조건:

- Windows 또는 macOS 최신 데스크톱 Chrome
- 카메라 권한과 실제 프레임 입력
- 마이크 권한과 실제 입력 레벨
- 선택 장치 미리보기
- 녹화·분석 고지 확인

강사 통과 조건:

- 마이크 입력 필수
- LiveKit 미디어 연결 필수
- 카메라 실패는 제한 모드로 허용

정리 규칙:

- 미리보기는 로컬에만 렌더링한다.
- 화면을 나가면 local track을 stop하고 listener를 해제한다.
- Prejoin에서는 화면 공유를 시작하지 않는다.
- 장치 선택값은 Room 입장 후 publish할 때 재사용한다.

## 6. Room 연결 상태 머신

```text
IDLE
→ FETCHING_TOKEN
→ CONNECTING
→ CONNECTED
→ RECONNECTING
→ CONNECTED | DISCONNECTED
→ REENTRY_REQUIRED
```

- `room.connect()` Promise가 해결되기 전 입장 완료 UI를 표시하지 않는다.
- `RECONNECTING` 중 새 Room을 만들거나 `connect()`를 중복 호출하지 않는다.
- LiveKit 자동 재연결이 최종 실패했을 때만 새 토큰을 발급받는다.
- 전체 재입장은 1초, 2초, 4초 간격으로 최대 3회 수행한다.
- 실패하면 세션 정보와 장치 선택을 유지한 재입장 화면을 표시한다.

같은 사용자·세션은 동일 identity를 사용하므로 여러 탭을 동시에 열지 않도록 중복 접속 안내를 제공한다.

## 7. 필수 LiveKit 이벤트

| 이벤트 | UI 처리 |
| --- | --- |
| `ParticipantConnected` | 참가자 목록 추가 |
| `ParticipantDisconnected` | Track·타일 정리 |
| `TrackPublished` | 게시 상태 반영 |
| `TrackUnpublished` | 미디어 요소 제거 |
| `TrackSubscribed` | Track을 DOM에 연결 |
| `TrackUnsubscribed` | DOM 연결 해제 |
| `TrackMuted` / `TrackUnmuted` | 마이크·카메라 상태 표시 |
| `ActiveSpeakersChanged` | 발화자 강조 |
| `Reconnecting` / `Reconnected` | 연결 복구 UI |
| `Disconnected` | 종료 사유에 따른 화면 전환 |
| `ConnectionQualityChanged` | 연결 품질 경고 |
| `ParticipantPermissionsChanged` | 화면 공유 버튼·권한 상태 갱신 |
| `MediaDevicesChanged` | 장치 선택 목록 갱신 |

React 구현 규칙:

- `useEffect` cleanup에서 listener를 반드시 해제한다.
- Strict Mode 재실행으로 listener가 중복 등록되지 않게 한다.
- `TrackSubscribed`에서 attach한 요소는 `TrackUnsubscribed`에서 detach한다.
- Room 종료 시 local track과 Room 인스턴스를 정리한다.
- 이벤트 callback 안에서 오래 걸리는 API를 직렬로 막지 않는다.

## 8. Track과 레이아웃

```text
CAMERA             → 참가자 카메라 타일
MICROPHONE         → Room 오디오 렌더러
SCREEN_SHARE       → 메인 공유 화면
SCREEN_SHARE_AUDIO → 공유 영상과 함께 재생
```

- 공유 화면이 없으면 카메라 그리드다.
- 공유 화면이 있으면 공유 화면이 메인이다.
- 강사 카메라는 우측 하단 보조 화면이다.
- 다른 참가자 카메라는 상단 또는 측면에 배치한다.
- 공유 종료 시 그리드로 돌아간다.
- 두 번째 공유 Track이 감지되면 표시하지 않고 백엔드에 보고한다.
- 학생 카메라의 실시간 렌더링과 녹화 여부는 별개다. 프론트엔드는 녹화 여부를 결정하지 않는다.

## 9. 화면 공유

역할 제한이 없다. 두 역할 모두 같은 흐름을 따른다(2026-07-30 확정 — 승인 플로우 없음).

- 공유 버튼을 항상 표시한다.
- 공유 시작 전 Spring Boot의 단일 공유 제어로 사용권을 획득한다.
- 다른 공유자가 있으면 백엔드 결과에 따라 기존 공유를 중지하거나 요청을 거부한다.
- 종료·중지·서버 회수 시 화면 Track과 화면 오디오 Track을 함께 정리한다.
- 서버가 `SCREEN_SHARE_STOPPED`를 보내거나 LiveKit `ParticipantPermissionsChanged`로 권한이 내려가면 즉시 공유를 정리한다.

버튼을 숨기는 것으로 보안을 대신하지 않는다. 활성 공유 단일성은 서버 판정이 최종 기준이다.

## 10. Spring Boot WebSocket 이벤트

다음 이벤트는 LiveKit DataPacket이 아니라 애플리케이션 WebSocket으로 처리한다.

```text
SESSION_ENDING
INSTRUCTOR_DISCONNECTED
INSTRUCTOR_RECONNECTED
SCREEN_SHARE_STARTED
SCREEN_SHARE_STOPPED
CHAT_MESSAGE
HAND_RAISED
HAND_LOWERED
REACTION
PARTICIPANT_KICKED
```

- 공개 채팅과 학생-강사 1:1 채팅의 수신 대상을 구분한다.
- 학생 간 1:1 채팅 UI를 제공하지 않는다.
- `SESSION_ENDING`을 받으면 새 publish를 중단하고 종료 안내를 표시한다.
- `PARTICIPANT_KICKED`는 현재 연결만 종료하며 영구 차단으로 표시하지 않는다.
- WebSocket 이벤트는 사용자 입력 표현에 사용하되 서버의 최종 권한 결과를 따른다.

## 11. 녹화 표시

- 수업 중 항상 녹화 상태를 표시한다.
- 학생 카메라가 녹화되지 않는다는 안내를 표시한다.
- Egress 일부 또는 전체 실패 알림은 강사에게 즉시 표시한다.
- 프론트엔드는 Egress ID, 파일 경로, 로컬 스토리지 경로를 받지 않는다.

## 12. 종료 UX

### 학생 나가기

- 자신의 `room.disconnect()`만 수행한다.
- 세션 종료 API를 호출하지 않는다.

### 강사 나가기

- 단순 탭 종료·네트워크 단절과 `수업 종료` 버튼을 구분한다.
- `수업 종료`는 확인 UI 후 `POST /sessions/{id}/end`를 호출한다.
- `SESSION_ENDING` 이후 Room이 닫히면 강사 메모 화면으로 이동한다.
- 강사 네트워크 단절 중 학생에게 5분 재연결 대기 상태를 표시한다.

## 13. 오류 처리

| 상태 | 프론트엔드 처리 |
| --- | --- |
| `401` | 로그인 갱신 또는 로그인 화면 |
| `403` | 참가 관계·역할 오류 안내 |
| `404` | 유효하지 않은 초대·세션 안내 |
| `409 SESSION_CAPACITY_REACHED` | 30명 정원 초과 안내 |
| 종료된 세션 `409/410` | 종료된 수업 안내 |
| LiveKit 연결 실패 | 네트워크·TURN 안내와 재입장 |
| 장치 권한 거부 | 브라우저 설정별 해결 안내 |
| Egress 실패 | 강사에게 녹화 제한 안내 |

오류 화면에 토큰, Room 내부 SID, 서버 파일 경로를 노출하지 않는다.

## 14. 구현 순서

1. OpenAPI 변경 후 `npm run generate:types`로 타입 재생성
2. API client와 세션·미디어 토큰 query/mutation 구현
3. 실제 Prejoin 장치 검사와 cleanup 구현
4. LiveKit Room 연결과 Track publish/subscribe 구현
5. Room 이벤트와 레이아웃 연결
6. Spring WebSocket 채팅·손들기·반응 연결
7. 화면 공유 시작·중지와 단일 활성 반영 구현
8. 재연결·중복 탭·종료 UX 구현
9. 단위·컴포넌트·브라우저 통합 테스트 추가

## 15. 완료 체크리스트

- [ ] 프론트엔드가 Room·identity·role·grant를 생성하지 않는다.
- [ ] 토큰을 영구 저장하거나 로그로 출력하지 않는다.
- [ ] 학생 장치 테스트 실패 시 입장을 차단한다.
- [ ] 화면 공유는 역할 제한 없이 시작하고, 서버가 판정한 활성 공유 단일성을 따른다.
- [ ] LiveKit 미디어 이벤트와 Spring 업무 이벤트를 분리한다.
- [ ] 모든 listener와 Track cleanup이 존재한다.
- [ ] 공유 화면·강사 카메라 레이아웃이 요구사항과 일치한다.
- [ ] 연결 실패 후 무한 재시도하지 않는다.
- [ ] 학생 나가기가 수업 종료 API를 호출하지 않는다.
- [ ] 학생 카메라 비녹화 안내와 녹화 상태를 표시한다.
