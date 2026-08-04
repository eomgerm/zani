// report 도메인의 공개 API. 다른 도메인/app에서는 이 파일을 통해서만 접근한다.
// infrastructure(HTTP 어댑터)와 훅·순수 함수는 공개하지 않는다 — 밖에서 필요한 것은 스스로
// 조회하고 상태를 분기하는 presentation 컴포넌트뿐이다. 메모 확정처럼 되돌릴 수 없는 조작은
// 확인 절차를 가진 presentation 컴포넌트(InstructorNoteEditor)를 통해서만 실행되어야 한다.
export { GroupAttentionTimeline } from "./presentation/GroupAttentionTimeline";
export { StudentAttentionTimeline } from "./presentation/StudentAttentionTimeline";
export { InstructorNoteEditor } from "./presentation/InstructorNoteEditor";
export { StudentReportClip } from "./presentation/StudentReportClip";
export { StudentRecommendations } from "./presentation/StudentRecommendations";
// 이동 명령의 모양만 밖에 공개한다. 명령을 만들어 보내는 쪽(리포트 화면)이 nonce 를 찍는다.
export type { SeekRequest } from "./presentation/ReportPlayer";
