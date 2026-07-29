import { describe, expect, it, vi } from "vitest";

import type { TrackFrameWorkerResponse } from "./trackFrameWorkerProtocol";
import { createTrackFrameWorkerSession } from "./trackFrameWorkerSession";
import type { TrackProcessorLike } from "./trackFrameWorkerRuntime";

function neverEndingProcessor(): TrackProcessorLike {
  return {
    readable: {
      getReader: () => ({
        read: () => new Promise<ReadableStreamReadResult<VideoFrame>>(() => {}),
      }),
    },
  };
}

function harness(createProcessor: () => TrackProcessorLike = neverEndingProcessor) {
  const messages: TrackFrameWorkerResponse[] = [];
  const session = createTrackFrameWorkerSession({
    createProcessor,
    postMessage: (message) => messages.push(message),
  });
  return { session, messages };
}

function startRequest(track: MediaStreamTrack) {
  return { type: "start", track, sampleIntervalMs: 100 } as const;
}

describe("createTrackFrameWorkerSession", () => {
  it("stops the started camera track before acknowledging the stop request", () => {
    const stopOrder: string[] = [];
    const track = {
      stop: () => stopOrder.push("track.stop"),
    } as unknown as MediaStreamTrack;
    const { session, messages } = harness();

    session.handle(startRequest(track));
    session.handle({ type: "stop" });

    expect(stopOrder).toEqual(["track.stop"]);
    expect(messages).toEqual([{ type: "stopped" }]);
  });

  it("acknowledges a stop request that arrives before any track", () => {
    const { session, messages } = harness();

    session.handle({ type: "stop" });

    expect(messages).toEqual([{ type: "stopped" }]);
  });

  it("stops the camera track only once across repeated stop requests", () => {
    const track = { stop: vi.fn() } as unknown as MediaStreamTrack;
    const { session, messages } = harness();

    session.handle(startRequest(track));
    session.handle({ type: "stop" });
    session.handle({ type: "stop" });

    expect(track.stop).toHaveBeenCalledTimes(1);
    expect(messages).toEqual([{ type: "stopped" }, { type: "stopped" }]);
  });

  it("reports a detector failure when the processor cannot start", async () => {
    const track = { stop: vi.fn() } as unknown as MediaStreamTrack;
    const { session, messages } = harness(() => {
      throw new Error("TrackProcessor unsupported");
    });

    session.handle(startRequest(track));
    await vi.waitFor(() => expect(messages).toHaveLength(1));

    expect(messages).toEqual([
      { type: "failure", message: "TrackProcessor unsupported" },
    ]);
  });
});
