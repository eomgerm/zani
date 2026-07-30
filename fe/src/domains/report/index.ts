// report 도메인의 공개 API. 다른 도메인/앱에서는 이 파일을 통해서만 접근한다.
// infrastructure(HTTP 어댑터)와 훅·순수 함수는 공개하지 않는다 — 밖에서 필요한 것은
// 스스로 조회하고 상태를 분기하는 카드 두 개뿐이다.
export { GroupAttentionTimeline } from "./presentation/GroupAttentionTimeline";
export { StudentAttentionTimeline } from "./presentation/StudentAttentionTimeline";
