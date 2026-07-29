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

  it("stays silent when the frame read fails because the stop request ended the track", async () => {
    const messages: unknown[] = [];
    let stopped = false;

    await runTrackFrameWorker({
      track: {} as MediaStreamTrack,
      sampleIntervalMs: 100,
      createProcessor: () => ({
        readable: {
          getReader: () => ({
            read: () => {
              // 정지 요청이 트랙을 끝내면 리더가 거부된다.
              stopped = true;
              return Promise.reject(new Error("track ended"));
            },
          }),
        },
      }),
      postMessage: (message) => messages.push(message),
      isStopped: () => stopped,
    });

    expect(messages).toEqual([]);
  });
});
