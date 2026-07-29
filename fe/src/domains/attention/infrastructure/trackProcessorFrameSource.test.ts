import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type {
  TrackFrameWorkerPort,
  TrackFrameWorkerResponse,
} from "./trackFrameWorkerProtocol";
import {
  TRACK_FRAME_WORKER_STOP_TIMEOUT_MS,
  createTrackProcessorFrameSource,
} from "./trackProcessorFrameSource";

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

function startSource(harness: ReturnType<typeof fakeWorker>) {
  const clonedTrack = { stop: vi.fn() } as unknown as MediaStreamTrack;
  const originalTrack = { clone: () => clonedTrack } as unknown as MediaStreamTrack;
  const received: Array<{ frame: VideoFrame; timestampMs: number }> = [];
  const onFailure = vi.fn();
  const source = createTrackProcessorFrameSource({
    track: originalTrack,
    createWorker: () => harness.worker,
    onFrame: (frame, timestampMs) => received.push({ frame, timestampMs }),
    onFailure,
  });
  return { source, clonedTrack, received, onFailure };
}

describe("createTrackProcessorFrameSource", () => {
  it("transfers a cloned camera track and delivers worker frames", () => {
    const harness = fakeWorker();
    const frame = { close: vi.fn() } as unknown as VideoFrame;
    const { clonedTrack, received } = startSource(harness);

    expect(harness.posted[0]?.message).toMatchObject({ type: "start", track: clonedTrack });
    expect(harness.posted[0]?.transfer).toContain(clonedTrack);

    harness.emit({ type: "frame", frame, timestampMs: 500 });
    expect(received).toEqual([{ frame, timestampMs: 500 }]);
  });

  describe("stop", () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it("keeps the worker alive until it acknowledges camera track cleanup", () => {
      const harness = fakeWorker();
      const { source } = startSource(harness);

      source.stop();

      expect(harness.posted[1]?.message).toEqual({ type: "stop" });
      expect(harness.worker.terminate).not.toHaveBeenCalled();
    });

    it("terminates the worker once the stop acknowledgement arrives", () => {
      const harness = fakeWorker();
      const { source } = startSource(harness);

      source.stop();
      harness.emit({ type: "stopped" });

      expect(harness.worker.terminate).toHaveBeenCalledTimes(1);
    });

    it("terminates a worker that never acknowledges the stop request", () => {
      const harness = fakeWorker();
      const { source } = startSource(harness);

      source.stop();
      vi.advanceTimersByTime(TRACK_FRAME_WORKER_STOP_TIMEOUT_MS);

      expect(harness.worker.terminate).toHaveBeenCalledTimes(1);
    });

    it("terminates only once when the acknowledgement follows the timeout", () => {
      const harness = fakeWorker();
      const { source } = startSource(harness);

      source.stop();
      vi.advanceTimersByTime(TRACK_FRAME_WORKER_STOP_TIMEOUT_MS);
      harness.emit({ type: "stopped" });

      expect(harness.worker.terminate).toHaveBeenCalledTimes(1);
    });

    it("stays idempotent across repeated calls", () => {
      const harness = fakeWorker();
      const { source } = startSource(harness);

      source.stop();
      source.stop();
      harness.emit({ type: "stopped" });
      source.stop();
      vi.advanceTimersByTime(TRACK_FRAME_WORKER_STOP_TIMEOUT_MS);

      const stopRequests = harness.posted.filter(
        (entry) => (entry.message as { type: string }).type === "stop",
      );
      expect(stopRequests).toHaveLength(1);
      expect(harness.worker.terminate).toHaveBeenCalledTimes(1);
    });

    it("closes frames that arrive between the stop request and termination", () => {
      const harness = fakeWorker();
      const frame = { close: vi.fn() } as unknown as VideoFrame;
      const { source, received } = startSource(harness);

      source.stop();
      harness.emit({ type: "frame", frame, timestampMs: 700 });

      expect(received).toEqual([]);
      expect(frame.close).toHaveBeenCalledTimes(1);
    });

    it("ignores worker failures reported after the stop request", () => {
      const harness = fakeWorker();
      const { source, onFailure } = startSource(harness);

      source.stop();
      harness.emit({ type: "failure", message: "카메라 프레임을 읽지 못했습니다." });

      expect(onFailure).not.toHaveBeenCalled();
    });
  });
});
