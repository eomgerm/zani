// report 도메인의 공개 API. 다른 도메인/app에서는 이 파일을 통해서만 접근한다.
// infrastructure(HTTP 어댑터)는 공개하지 않는다 — 밖에서 필요한 것은 스스로 조회하고 상태를
// 분기하는 presentation 컴포넌트다. 메모 확정처럼 되돌릴 수 없는 조작은 확인 절차를 가진
// presentation 컴포넌트(InstructorNoteEditor)를 통해서만 실행되어야 한다.
//
// 훅과 순수 함수는 원칙적으로 감추되, 한 응답을 한 화면의 두 자리에서 써야 할 때만 연다.
// 강사 리포트가 그렇다 — 집중 흐름 카드가 그리는 것과 같은 점들을 한눈에 보기가 세야 해서,
// 카드가 자기 몫을 또 부르면 큰 응답을 한 화면에서 두 번 받는다. 조회는 화면이 한 번 하고
// 카드에는 `source` 로 넘긴다.
export { GroupAttentionTimeline } from "./presentation/GroupAttentionTimeline";
export { StudentAttentionTimeline } from "./presentation/StudentAttentionTimeline";
export { InstructorNoteEditor } from "./presentation/InstructorNoteEditor";
export { StudentReportClip } from "./presentation/StudentReportClip";
export type { ClipSeekRequest } from "./presentation/StudentReportClip";

export { useGroupAttentionTimeline } from "./presentation/useGroupAttentionTimeline";
export type { GroupTimelineRequester } from "./infrastructure/attentionTimelineApi";
export { useInstructorReport } from "./presentation/useInstructorReport";
export type {
  InstructorReportStatus,
  UseInstructorReportResult,
} from "./presentation/useInstructorReport";
// 어댑터는 감추되 오류 타입은 연다. 조회를 갈아끼우는 쪽(테스트·목)이 403·404 를 흉내 내려면
// 이 클래스로 던져야 훅이 상태를 갈라 본다.
export { InstructorReportError } from "./infrastructure/instructorReportApi";
export type {
  InstructorInsight,
  InstructorReport,
  InstructorReportRequester,
  InstructorReportStats,
  InstructorScore,
  InstructorTip,
} from "./infrastructure/instructorReportApi";
export { focusedIntervalRatio, focusedRatioBand, FOCUSED_LEVEL } from "./presentation/focusedRatio";
export { formatOffset } from "./presentation/offsetTime";
