import { describe, expect, it } from "vitest";

import { focusedIntervalRatio } from "./focusedIntervalRatio";

describe("focusedIntervalRatio", () => {
  it("측정 가능한 칸 중 2.5단계 이상 비율을 정수로 반올림한다", () => {
    expect(
      focusedIntervalRatio([
        { offsetSeconds: 0, focusLevel: 4 },
        { offsetSeconds: 30, focusLevel: 2.5 },
        { offsetSeconds: 60, focusLevel: 2.49 },
      ]),
    ).toBe(67);
  });

  it("값이 없는 칸은 분모에서도 뺀다 — 카메라 끈 시간을 집중 안 함으로 세지 않는다", () => {
    expect(
      focusedIntervalRatio([
        { offsetSeconds: 0, focusLevel: 3 },
        { offsetSeconds: 30, focusLevel: null },
        { offsetSeconds: 60, focusLevel: null },
      ]),
    ).toBe(100);
  });

  it("측정 가능한 칸이 없으면 null 이다 — 0% 가 아니다", () => {
    expect(focusedIntervalRatio([{ offsetSeconds: 0, focusLevel: null }])).toBeNull();
    expect(focusedIntervalRatio([])).toBeNull();
  });
});
