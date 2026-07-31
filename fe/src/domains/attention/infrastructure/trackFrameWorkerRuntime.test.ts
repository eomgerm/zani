import { describe, expect, it, vi } from "vitest";

import { runTrackFrameWorker } from "./trackFrameWorkerRuntime";

describe("runTrackFrameWorker", () => {
  it("posts the first VideoFrame read from the transferred stream", async () => {
    const messages: unknown[] = [];
    const frame = { timestamp: 500_000, close: vi.fn() } as unknown as VideoFrame;
    let readCount = 0;

    await runTrackFrameWorker({
      readable: {
        getReader: () => ({
          read: async () =>
            readCount++ === 0
              ? { done: false as const, value: frame }
              : { done: true as const, value: undefined },
        }),
      },
      sampleIntervalMs: 100,
      postMessage: (message, transfer) => messages.push({ message, transfer }),
      isStopped: () => false,
    });

    expect(messages).toEqual([
      {
        message: { type: "frame", frame, timestampMs: 500 },
        transfer: [frame],
      },
    ]);
  });

  it("stays silent when the frame read fails after the stop request", async () => {
    const messages: unknown[] = [];
    let stopped = false;

    await runTrackFrameWorker({
      readable: {
        getReader: () => ({
          read: () => {
            stopped = true;
            return Promise.reject(new Error("track ended"));
          },
        }),
      },
      sampleIntervalMs: 100,
      postMessage: (message) => messages.push(message),
      isStopped: () => stopped,
    });

    expect(messages).toEqual([]);
  });
});
