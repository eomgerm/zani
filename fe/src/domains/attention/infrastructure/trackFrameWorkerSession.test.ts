import { describe, expect, it, vi } from "vitest";

import type { TrackFrameWorkerResponse } from "./trackFrameWorkerProtocol";
import { createTrackFrameWorkerSession } from "./trackFrameWorkerSession";

function neverEndingStream(): ReadableStream<VideoFrame> {
  return {
    getReader: () => ({
      read: () => new Promise<ReadableStreamReadResult<VideoFrame>>(() => {}),
    }),
  } as ReadableStream<VideoFrame>;
}

function harness() {
  const messages: TrackFrameWorkerResponse[] = [];
  const session = createTrackFrameWorkerSession({
    postMessage: (message) => messages.push(message),
  });
  return { session, messages };
}

function startRequest(readable: ReadableStream<VideoFrame> = neverEndingStream()) {
  return { type: "start", readable, sampleIntervalMs: 100 } as const;
}

describe("createTrackFrameWorkerSession", () => {
  it("reads frames from the transferred stream", async () => {
    const frame = { timestamp: 300_000, close: vi.fn() } as unknown as VideoFrame;
    const readable = new ReadableStream<VideoFrame>({
      start(controller) {
        controller.enqueue(frame);
        controller.close();
      },
    });
    const { session, messages } = harness();

    session.handle(startRequest(readable));
    await vi.waitFor(() => expect(messages).toHaveLength(1));

    expect(messages).toEqual([{ type: "frame", frame, timestampMs: 300 }]);
  });

  it("acknowledges a stop request that arrives before any stream", () => {
    const { session, messages } = harness();

    session.handle({ type: "stop" });

    expect(messages).toEqual([{ type: "stopped" }]);
  });

  it("acknowledges repeated stop requests without owning the camera track", () => {
    const { session, messages } = harness();

    session.handle(startRequest());
    session.handle({ type: "stop" });
    session.handle({ type: "stop" });

    expect(messages).toEqual([{ type: "stopped" }, { type: "stopped" }]);
  });
});
