import { describe, expect, it } from "vitest";

import { runTrackFrameWorker } from "./trackFrameWorkerRuntime";

describe("runTrackFrameWorker", () => {
  it("reports detector unavailability when TrackProcessor cannot start", async () => {
    const messages: unknown[] = [];

    await runTrackFrameWorker({
      track: {} as MediaStreamTrack,
      sampleIntervalMs: 100,
      createProcessor: () => {
        throw new Error("TrackProcessor unsupported");
      },
      postMessage: (message) => messages.push(message),
      isStopped: () => false,
    });

    expect(messages).toEqual([{ type: "failure", message: "TrackProcessor unsupported" }]);
  });
});
