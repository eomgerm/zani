import { describe, expect, it, vi } from "vitest";

import type {
  TrackFrameWorkerPort,
  TrackFrameWorkerResponse,
} from "./trackFrameWorkerProtocol";
import { createTrackProcessorFrameSource } from "./trackProcessorFrameSource";

function fakeWorker() {
  const posted: Array<{ message: unknown; transfer: Transferable[] | undefined }> = [];
  const worker: TrackFrameWorkerPort = {
    onmessage: null,
    onerror: null,
    postMessage(message, transfer) {
      posted.push({ message, transfer });
    },
    terminate: vi.fn(),
  };
  return {
    worker,
    posted,
    emit(response: TrackFrameWorkerResponse) {
      worker.onmessage?.({ data: response } as MessageEvent<TrackFrameWorkerResponse>);
    },
  };
}

describe("createTrackProcessorFrameSource", () => {
  it("transfers a cloned camera track and delivers worker frames", () => {
    const harness = fakeWorker();
    const clonedTrack = { stop: vi.fn() } as unknown as MediaStreamTrack;
    const originalTrack = { clone: () => clonedTrack } as unknown as MediaStreamTrack;
    const received: Array<{ frame: VideoFrame; timestampMs: number }> = [];
    const frame = { close: vi.fn() } as unknown as VideoFrame;

    const source = createTrackProcessorFrameSource({
      track: originalTrack,
      createWorker: () => harness.worker,
      onFrame: (next, timestampMs) => received.push({ frame: next, timestampMs }),
      onFailure: vi.fn(),
    });

    expect(harness.posted[0]?.message).toMatchObject({ type: "start", track: clonedTrack });
    expect(harness.posted[0]?.transfer).toContain(clonedTrack);

    harness.emit({ type: "frame", frame, timestampMs: 500 });
    expect(received).toEqual([{ frame, timestampMs: 500 }]);

    source.stop();
    expect(harness.worker.terminate).toHaveBeenCalledTimes(1);
  });
});
