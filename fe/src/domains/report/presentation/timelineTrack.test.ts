import { describe, expect, it } from "vitest";

import { mergeSegments, nextSegmentIndex } from "./timelineTrack";

const point = (offsetSeconds: number, state: string | null) =>
  ({ offsetSeconds, focusPercent: null, state }) as never;

describe("mergeSegments", () => {
  it("merges neighbouring points that share a state", () => {
    const segments = mergeSegments(
      [point(0, "GOOD"), point(5, "GOOD"), point(10, "CAMERA_OFF"), point(15, "CAMERA_OFF")],
      5,
    );

    expect(segments).toEqual([
      { startSeconds: 0, endSeconds: 10, state: "GOOD" },
      { startSeconds: 10, endSeconds: 20, state: "CAMERA_OFF" },
    ]);
  });

  it("keeps null as its own segment instead of absorbing it into a neighbour", () => {
    const segments = mergeSegments([point(0, "GOOD"), point(5, null), point(10, "GOOD")], 5);

    // 관측이 없는 구간은 회색 공백으로 보여야 한다. 옆 상태로 메우면 없는 정보를 지어내는 것이다.
    expect(segments).toHaveLength(3);
    expect(segments[1]).toEqual({ startSeconds: 5, endSeconds: 10, state: null });
  });

  it("returns an empty list for an empty series", () => {
    expect(mergeSegments([], 5)).toEqual([]);
  });

  it("handles a single point", () => {
    expect(mergeSegments([point(0, "GOOD")], 5)).toEqual([
      { startSeconds: 0, endSeconds: 5, state: "GOOD" },
    ]);
  });
});

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
