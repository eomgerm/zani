import type { AttentionStatus } from "./attentionPrediction";

/**
 * 학생 화면에 보여줄 분석 가용 상태(티켓 76).
 *
 * 판정 상태는 일곱 가지지만 학생에게는 **동작하는지 아닌지만** 보인다. 점수·등급·확률은 물론
 * "얼굴이 안 보임" 같은 개별 판정 상태도 노출하지 않는다.
 *
 * - `ACTIVE`: 분석이 돌고 있다
 * - `PAUSED`: 카메라에서 영상이 오지 않아 멈췄다. **왜 그런지는 여기서 말하지 않는다** —
 *   카메라 안내 프롬프트(81)가 원인별로 안내하므로 문구가 겹치면 안 된다
 * - `UNAVAILABLE`: 검출기 자체를 쓸 수 없다(모델·MediaPipe 로드 실패). 81 이 다루지 않는
 *   상황이라 여기서 사유를 밝힌다. 카메라 문제가 아니므로 카메라를 켜라고 하지 않는다
 */
export type AnalysisAvailability = "ACTIVE" | "PAUSED" | "UNAVAILABLE";

/**
 * 판정 상태를 학생에게 보여줄 가용 상태로 접는다.
 *
 * `unmeasurable` 을 `ACTIVE` 로 두는 것이 핵심이다. 얼굴이 잡히지 않아도 분석은 돌고 있고,
 * 이를 따로 표시하면 개별 판정 상태를 노출하는 셈이다. 자세 안내는 프롬프트(81)가 맡는다.
 */
export function analysisAvailabilityOf(status: AttentionStatus): AnalysisAvailability {
  switch (status) {
    case "unavailable":
      return "UNAVAILABLE";
    case "idle":
    case "permissionDenied":
      return "PAUSED";
    default:
      return "ACTIVE";
  }
}
