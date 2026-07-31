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
  const readable = new ReadableStream<VideoFrame>();
  const createProcessor = vi.fn(() => ({ readable }));
  const received: Array<{ frame: VideoFrame; timestampMs: number }> = [];
  const onFailure = vi.fn();
  const source = createTrackProcessorFrameSource({
    track: originalTrack,
    createWorker: () => harness.worker,
    createProcessor,
    onFrame: (frame, timestampMs) => received.push({ frame, timestampMs }),
    onFailure,
  });
  return { source, clonedTrack, readable, createProcessor, received, onFailure };
}

describe("createTrackProcessorFrameSource", () => {
  it("transfers the processor stream instead of the cloned camera track", () => {
    const harness = fakeWorker();
    const { clonedTrack, readable, createProcessor } = startSource(harness);

    expect(createProcessor).toHaveBeenCalledWith(clonedTrack);
    expect(harness.posted[0]?.message).toEqual({
      type: "start",
      readable,
      sampleIntervalMs: 100,
    });
    expect(harness.posted[0]?.transfer).toEqual([readable]);
    expect(harness.posted[0]?.transfer).not.toContain(clonedTrack);
  });

  it("delivers worker frames to the consumer", () => {
    const harness = fakeWorker();
    const frame = { close: vi.fn() } as unknown as VideoFrame;
    const { received } = startSource(harness);

    harness.emit({ type: "frame", frame, timestampMs: 500 });

    expect(received).toEqual([{ frame, timestampMs: 500 }]);
  });

  it("releases the cloned track and worker when frame reading fails", () => {
    const harness = fakeWorker();
    const { clonedTrack, onFailure } = startSource(harness);

    harness.emit({ type: "failure", message: "카메라 프레임을 읽지 못했습니다." });

    expect(clonedTrack.stop).toHaveBeenCalledTimes(1);
    expect(harness.worker.terminate).toHaveBeenCalledTimes(1);
    expect(onFailure).toHaveBeenCalledWith("카메라 프레임을 읽지 못했습니다.");
  });

  describe("stop", () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it("stops the cloned track while keeping the worker alive until acknowledgement", () => {
      const harness = fakeWorker();
      const { source, clonedTrack } = startSource(harness);

      source.stop();

      expect(clonedTrack.stop).toHaveBeenCalledTimes(1);
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
