import type { FocusPoint } from "../infrastructure/attentionTimelineApi";

/**
 * 집중 구간 비율 — 측정 가능한 30초 칸 중 집중 흐름이 2.5단계 이상인 칸의 비율(정수 %).
 *
 * <p>수업 전체를 하나의 점수로 축약한 값이 아니다(REPORT-S-010). 평균을 내지 않고 칸을 세기만
 * 한다 — 값 하나가 크게 낮아도 "몇 칸이 괜찮았는지" 는 그대로 남는다. 타인과 견주는 값도 아니다.
 *
 * <p>2.5 는 4단계 척도의 가운데다. 3 으로 올리면 "보통" 칸이 전부 빠져 비율이 실제 체감보다 낮게
 * 읽히고, 2 로 내리면 낮았던 칸까지 집중으로 세어 준다.
 *
 * <p>값이 없는 칸은 분모에서도 뺀다. 카메라를 끈 시간을 "집중 안 함" 으로 세면 안 된다
 * (REPORT-S-007). 측정 가능한 칸이 하나도 없으면 비율을 만들지 않고 `null` 이다.
 */
export function focusedIntervalRatio(points: readonly FocusPoint[]): number | null {
  const measurable = points.filter((point) => point.focusLevel !== null);
  if (measurable.length === 0) return null;
  const focused = measurable.filter((point) => (point.focusLevel as number) >= 2.5).length;
  return Math.round((focused / measurable.length) * 100);
}
