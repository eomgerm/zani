// lecture 도메인의 공개 API. 다른 도메인/app에서는 이 파일을 통해서만 접근한다.
// infrastructure(HTTP 어댑터)는 공개하지 않는다. 수업 종료처럼 되돌릴 수 없는 조작은
// 확인 절차를 가진 presentation 컴포넌트(EndSessionButton)를 통해서만 실행되어야 한다.
export { HomeScreen } from "./presentation/HomeScreen";
export { MyLecturesScreen } from "./presentation/MyLecturesScreen";
export { ReportScreen } from "./presentation/ReportScreen";
export { RoomScreen } from "./presentation/RoomScreen";
export { RoomProvider, useRoomConnection } from "./presentation/RoomProvider";
export { useRoomParticipants } from "./presentation/useRoomParticipants";
export type { UseRoomParticipantsResult } from "./presentation/useRoomParticipants";
export { useRoomMediaControls } from "./presentation/useRoomMediaControls";
export type { RoomMediaControls } from "./presentation/useRoomMediaControls";
export { useRoomReconnect } from "./presentation/useRoomReconnect";
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
export { VideoEditorScreen } from "./presentation/VideoEditorScreen";
