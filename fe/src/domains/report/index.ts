// report 도메인의 공개 API. 다른 도메인/app에서는 이 파일을 통해서만 접근한다.
// infrastructure(HTTP 어댑터)는 공개하지 않는다 — 밖에서 쓰는 것은 스스로 조회하고 상태를
// 분기하는 presentation 컴포넌트, 또는 어댑터를 기본값으로 물고 있는 훅뿐이다. 메모 확정처럼
// 되돌릴 수 없는 조작은 확인 절차를 가진 presentation 컴포넌트(InstructorNoteEditor)를 통해서만
// 실행되어야 한다.
//
// 훅을 내보내는 까닭: 학생 리포트 탭(lecture 도메인의 StudentReport)은 한 응답을 여러 카드가
// 나눠 쓴다 — 집중 흐름 응답 하나로 차트·타임라인·"집중 구간 비율" 타일을 함께 그리고, 학습
// 리포트 응답 하나로 활동 집계·참여 요약·복습 추천을 그린다. 카드마다 스스로 조회하게 두면 같은
// URL 을 두 번 이상 읽는다. 그래서 조회는 탭이 한 번 하고 표시 컴포넌트에 값을 내려 준다.
export { GroupAttentionTimeline } from "./presentation/GroupAttentionTimeline";
export {
  StudentAttentionTimeline,
  StudentAttentionTimelineView,
  useStudentAttentionTimeline,
} from "./presentation/StudentAttentionTimeline";
export { InstructorNoteEditor } from "./presentation/InstructorNoteEditor";
export { StudentReportClip } from "./presentation/StudentReportClip";
export type { ClipSeekRequest } from "./presentation/StudentReportClip";
export { useStudentReport } from "./presentation/useStudentReport";
export { useStudentQuiz } from "./presentation/useStudentQuiz";
export { focusedIntervalRatio } from "./presentation/focusedIntervalRatio";
export { formatOffset } from "./presentation/offsetTime";
// 조회 계약: 함수 서명 · 응답의 모양 · 실패 어휘. 어댑터 함수 자체는 공개하지 않는다 — 밖에서
// 서버와 이야기하는 길은 훅뿐이고, 이것들은 그 훅에 다른 조회를 끼워 넣거나(테스트) 훅이 내려준
// 값을 다루기 위한 타입이다. 여기 없으면 밖에서 infrastructure 를 직접 열게 되어 경계가 무의미해진다.
//
// 에러 클래스를 함께 내보내는 까닭: 상태 분기는 훅이 하지만, 그 분기를 확인하려면 밖에서 특정
// 상태의 실패를 만들어 넣을 수 있어야 한다.
export type {
  StudentReportRequester,
  StudentRecommendation,
  // 컴포넌트 이름과 겹치지 않게 이름을 갈라 둔다(lecture 의 StudentReport 화면).
  StudentReport as StudentReportData,
} from "./infrastructure/studentReportApi";
export { StudentReportError } from "./infrastructure/studentReportApi";
export type {
  StudentTimelineRequester,
  SectionAverage,
  // 이 도메인의 StudentAttentionTimeline 컴포넌트와 이름이 같아 갈라 둔다.
  StudentAttentionTimeline as StudentAttentionTimelineData,
} from "./infrastructure/attentionTimelineApi";
export { AttentionTimelineError } from "./infrastructure/attentionTimelineApi";
export type {
  QuizAnswer,
  QuizAnswersSubmitter,
  QuizGrading,
  QuizQuestion,
  StudentQuiz,
  StudentQuizRequester,
} from "./infrastructure/studentQuizApi";
export { StudentQuizError } from "./infrastructure/studentQuizApi";
