import { describe, expect, it, vi } from "vitest";

import { pumpTrackFrames, type TrackVideoFrame } from "./trackFramePump";

function frame(timestamp: number): TrackVideoFrame {
  return { timestamp, close: vi.fn() };
}

describe("pumpTrackFrames", () => {
  it("samples track frames at 10fps while the page is hidden", async () => {
    Object.defineProperty(document, "hidden", { configurable: true, value: true });
    const frames = [frame(0), frame(50_000), frame(100_000), frame(150_000), frame(200_000)];
    let index = 0;
    const emitted: number[] = [];

    await pumpTrackFrames({
      read: async () =>
        index < frames.length
          ? { done: false, value: frames[index++] as TrackVideoFrame }
          : { done: true, value: undefined },
      sampleIntervalMs: 100,
      onFrame: async (next, timestampMs) => {
        emitted.push(timestampMs);
        next.close();
      },
    });

    expect(emitted).toEqual([0, 100, 200]);
    expect(frames.every((next) => vi.mocked(next.close).mock.calls.length === 1)).toBe(true);
  });
});
