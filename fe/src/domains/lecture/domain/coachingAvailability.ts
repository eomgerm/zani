/**
 * 강사에게 보여줄 코칭 가용 상태(티켓 76).
 *
 * 서버가 팁을 못 만든 사유는 네 가지인데(204), **그중 둘은 고장이 아니다.** 넷을 모두 비활성으로
 * 표시하면 정상 동작이 오류처럼 보인다.
 *
 * - `NO_TRANSCRIPT`: 강사 오디오 버퍼가 60초 미만이다. 수업 시작 직후에는 항상 이 상태라,
 *   비활성으로 표시하면 매 수업 첫 1분마다 오류가 뜬다.
 * - `LOW_CONFIDENCE`: 모델이 확신이 부족해 걸러냈다. 설계된 필터링이지 실패가 아니다.
 * - `TRANSCRIPTION_FAILED`·`TIP_FAILED`: 실제 고장이라 강사가 알아야 한다.
 *
 * 76 요구사항이 "실패 상태가 있을 때만 코칭 비활성 사유를 표시한다"고 한 것이 이 구분이다.
 */
export type CoachTipUnavailableReason =
  | "NO_TRANSCRIPT"
  | "TRANSCRIPTION_FAILED"
  | "TIP_FAILED"
  | "LOW_CONFIDENCE";

/**
 * 강사 화면에 알릴 코칭 상태.
 *
 * - `ACTIVE`: 코칭이 돌고 있다(또는 팁이 없을 뿐 정상이다). 알릴 것이 없다.
 * - `TRANSCRIPTION_FAILED`·`TIP_FAILED`: 서버 쪽 고장
 * - `POLL_FAILED`: 조회 자체가 실패했다. 서버가 만들었어도 받을 수 없으니 강사에겐 같은 결과다.
 */
export type CoachingAvailability =
  | "ACTIVE"
  | "TRANSCRIPTION_FAILED"
  | "TIP_FAILED"
  | "POLL_FAILED";

/** 폴링이 성공했을 때, 응답의 미표시 사유를 강사에게 알릴 상태로 접는다. */
export function coachingAvailabilityOf(
  reason: CoachTipUnavailableReason | null,
): CoachingAvailability {
  return reason === "TRANSCRIPTION_FAILED" || reason === "TIP_FAILED" ? reason : "ACTIVE";
}
