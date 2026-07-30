/**
 * 강사에게 보여줄 코칭 가용 상태(티켓 76).
 *
 * <h3>사유가 아니라 연속 실패로 판단한다</h3>
 *
 * 서버가 팁을 못 만든 사유(`CoachTipUnavailableReason`)는 **코칭이 죽었다는 뜻이 아니다.**
 * 트리거 하나가 실패한 것이고 다음 트리거에서 회복된다. LLM 타임아웃 한 번에 "코칭을 쓸 수
 * 없다"고 알리면 정상 동작이 고장처럼 보인다. 85 의 응답 계약도 같은 이유로 "코칭 비활성
 * 표시는 폴링 자체가 연속 실패할 때만 쓴다"고 못박고 있다.
 *
 * 폴링 실패도 한 번은 흔한 흔들림이다. 네트워크가 잠깐 끊겼다고 배지를 띄우면 같은 문제가
 * 생긴다. 그래서 연속으로 이어질 때만 알린다.
 */

/** 서버가 팁을 못 만든 사유(85 계약). 배지에는 쓰지 않고 계약 충실성과 로깅을 위해 받아 둔다. */
export type CoachTipUnavailableReason =
  | "NO_TRANSCRIPT"
  | "TRANSCRIPTION_FAILED"
  | "TIP_GENERATION_FAILED"
  | "LOW_CONFIDENCE";

/**
 * 배지를 띄우기까지 필요한 연속 폴링 실패 횟수.
 *
 * 10초 주기이므로 3회면 30초다. 한두 번은 네트워크가 흔들린 것일 수 있고, 30초 동안 한 번도
 * 받지 못했다면 강사가 알아야 할 문제다.
 */
export const COACH_POLL_FAILURE_THRESHOLD = 3;

/**
 * 강사 화면에 알릴 코칭 상태.
 *
 * - `ACTIVE`: 알릴 것이 없다. 팁이 없을 뿐이거나, 실패가 아직 일시적이다.
 * - `POLL_FAILED`: 조회가 연속으로 실패했다. 서버가 팁을 만들었어도 받지 못하니 강사에겐 같은 결과다.
 */
export type CoachingAvailability = "ACTIVE" | "POLL_FAILED";

/** 연속 실패 횟수를 강사에게 알릴 상태로 접는다. */
export function coachingAvailabilityOf(consecutiveFailures: number): CoachingAvailability {
  return consecutiveFailures >= COACH_POLL_FAILURE_THRESHOLD ? "POLL_FAILED" : "ACTIVE";
}
