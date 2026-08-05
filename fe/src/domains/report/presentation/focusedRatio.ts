import type { FocusPoint } from "../infrastructure/attentionTimelineApi";

/**
 * 집중 구간 비율 — 한눈에 보기의 다섯 번째 칸.
 *
 * <p><b>서버가 주지 않는 값이다.</b> 강사 리포트 응답에는 없고 집중 흐름(`GET
 * /reports/attention/group`)의 점들에서 화면이 센다. 서버가 한 번 더 계산하면 판정 기준(2.5 단계)이
 * 두 곳에 생기고, 한쪽만 바뀌면 같은 수업이 화면과 API 에서 다른 비율을 갖는다.
 */

/**
 * "집중했다" 로 볼 단계 경계.
 *
 * <p>1~4 단계 척도의 한가운데다. 3 이상으로 올리면 평범하게 잘 굴러간 수업도 절반 아래로 떨어져
 * 강사가 매번 실패한 것처럼 읽고, 2 로 내리면 거의 모든 구간이 집중으로 잡혀 값이 아무것도
 * 구분하지 못한다.
 */
export const FOCUSED_LEVEL = 2.5;

/** 비율을 읽는 말. 숫자만 두면 78% 가 좋은 것인지 나쁜 것인지 강사가 알 수 없다. */
export type FocusedRatioBand = "좋음" | "보통" | "낮음";

const GOOD_RATIO = 80;
const FAIR_RATIO = 60;

export const focusedRatioBand = (percent: number): FocusedRatioBand => {
  if (percent >= GOOD_RATIO) return "좋음";
  if (percent >= FAIR_RATIO) return "보통";
  return "낮음";
};

/**
 * 집중 단계가 {@link FOCUSED_LEVEL} 이상인 구간의 비율(0~100 정수).
 *
 * <p><b>분모는 값이 있는 구간만이다.</b> 인원이 모자라 서버가 감춘 구간(`focusLevel === null`,
 * REPORT-I-005)은 분모에도 분자에도 넣지 않는다. 분모에 넣으면 사람이 적었다는 이유만으로 비율이
 * 내려가 강사가 자기 수업을 잘못 읽는다.
 *
 * <p>잴 수 있는 구간이 하나도 없으면 `null` 이다. **0% 가 아니다** — "아무도 집중하지 않았다" 와
 * "잴 수 없었다" 는 화면에서 다른 글자로 나가야 한다.
 */
export const focusedIntervalRatio = (points: readonly FocusPoint[]): number | null => {
  let measured = 0;
  let focused = 0;

  for (const point of points) {
    if (point.focusLevel === null) continue;
    measured += 1;
    if (point.focusLevel >= FOCUSED_LEVEL) focused += 1;
  }

  return measured === 0 ? null : Math.round((focused / measured) * 100);
};
