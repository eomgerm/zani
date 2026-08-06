// lecture 도메인의 공개 API. 다른 도메인/app에서는 이 파일을 통해서만 접근한다.
// infrastructure(HTTP 어댑터)는 공개하지 않는다. 수업 종료처럼 되돌릴 수 없는 조작은
// 확인 절차를 가진 presentation 컴포넌트(EndSessionButton)를 통해서만 실행되어야 한다.
export { canonicalInviteCode, inviteCodeFrom } from "./domain/inviteCode";
export { HomeScreen } from "./presentation/HomeScreen";
export { PrejoinScreen } from "./presentation/PrejoinScreen";
export { MyLecturesScreen } from "./presentation/MyLecturesScreen";
export { ReportScreen } from "./presentation/ReportScreen";
export { RoomScreen } from "./presentation/RoomScreen";
// 강의실 배치 검토용 목업. 강의실 내부 컴포넌트를 여러 개 조합하지만, 밖으로는 이 화면 하나만
// 내보낸다 — 검토용 화면 때문에 방 내부 부품이 공개 API 가 되면 도메인 경계가 그만큼 얇아진다.
export { RoomMockupScreen } from "./presentation/RoomMockupScreen";
export {
  LandingRecordingRoom,
  type LandingRecordingScene,
} from "./presentation/LandingRecordingRoom";
export { RoomProvider, useRoomConnection } from "./presentation/RoomProvider";
export { useRoomParticipants } from "./presentation/useRoomParticipants";
export type { UseRoomParticipantsResult } from "./presentation/useRoomParticipants";
export { useRoomMediaControls } from "./presentation/useRoomMediaControls";
export type { RoomMediaControls } from "./presentation/useRoomMediaControls";
export { useRoomReconnect } from "./presentation/useRoomReconnect";
export { useSessionRole } from "./presentation/useSessionRole";
export type { SessionRole, SessionRoleStatus } from "./presentation/useSessionRole";
export { useSessionPresence } from "./presentation/useSessionPresence";
export type { SessionPresenceState } from "./presentation/useSessionPresence";
export { useSessionTimeWarning, WARNING_THRESHOLD_MINUTES } from "./presentation/useSessionTimeWarning";
export type { SessionTimeWarningState } from "./presentation/useSessionTimeWarning";
export type {
  Measurability,
  ReconnectStatus,
  RoomReconnectState,
  UseRoomReconnectOptions,
} from "./presentation/useRoomReconnect";
export { CreateSetupScreen } from "./presentation/CreateSetupScreen";
export { NoteScreen } from "./presentation/NoteScreen";
export { QuizScreen } from "./presentation/QuizScreen";
