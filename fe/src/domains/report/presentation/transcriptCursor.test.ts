import { describe, expect, it } from "vitest";

import { activeSegmentIndex } from "./transcriptCursor";

const segments = [
  { startSeconds: 2 },
  { startSeconds: 195 },
  { startSeconds: 400 },
  { startSeconds: 730 },
];

describe("activeSegmentIndex", () => {
  it("첫 발화 전에는 -1 이다", () => {
    expect(activeSegmentIndex(segments, 0)).toBe(-1);
    expect(activeSegmentIndex(segments, 1.9)).toBe(-1);
  });

  it("시작 시각 정각에 그 행이 켜진다", () => {
    expect(activeSegmentIndex(segments, 2)).toBe(0);
    expect(activeSegmentIndex(segments, 400)).toBe(2);
  });

  it("행 사이 틈에서는 직전 행을 유지한다 — 깜빡이지 않는다", () => {
    expect(activeSegmentIndex(segments, 300)).toBe(1);
  });

  it("마지막 행 뒤에는 마지막 행이 켜져 있다", () => {
    expect(activeSegmentIndex(segments, 99999)).toBe(3);
  });

  it("빈 전사는 -1 이다", () => {
    expect(activeSegmentIndex([], 10)).toBe(-1);
  });
});
