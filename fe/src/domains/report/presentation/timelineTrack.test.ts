import { describe, expect, it } from "vitest";

import { nextSegmentIndex } from "./timelineTrack";

// mergeSegments 는 서버가 상태 구간을 병합해 주면서 사라졌다(설계 문서 §2.13).
// 클라이언트가 다시 병합하면 같은 규칙이 두 곳에 생긴다.

describe("nextSegmentIndex", () => {
  it("moves one step at a time and stops at both ends", () => {
    expect(nextSegmentIndex(0, "ArrowRight", 3)).toBe(1);
    expect(nextSegmentIndex(2, "ArrowRight", 3)).toBe(2);
    expect(nextSegmentIndex(1, "ArrowLeft", 3)).toBe(0);
    expect(nextSegmentIndex(0, "ArrowLeft", 3)).toBe(0);
  });

  it("jumps to the ends with Home and End", () => {
    expect(nextSegmentIndex(1, "Home", 3)).toBe(0);
    expect(nextSegmentIndex(1, "End", 3)).toBe(2);
  });

  it("ignores unrelated keys", () => {
    expect(nextSegmentIndex(1, "Enter", 3)).toBe(1);
  });

  it("stays at zero when there is nothing to move through", () => {
    expect(nextSegmentIndex(0, "End", 0)).toBe(0);
  });
});
