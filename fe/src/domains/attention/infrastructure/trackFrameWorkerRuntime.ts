import { pumpTrackFrames } from "./trackFramePump";
import type { TrackFrameWorkerResponse } from "./trackFrameWorkerProtocol";

export interface TrackProcessorLike {
  readonly readable: {
    getReader(): {
      read(): Promise<ReadableStreamReadResult<VideoFrame>>;
    };
  };
}

export interface TrackFrameWorkerRuntimeOptions {
  readonly track: MediaStreamTrack;
  readonly sampleIntervalMs: number;
  createProcessor(track: MediaStreamTrack): TrackProcessorLike;
  postMessage(message: TrackFrameWorkerResponse, transfer?: Transferable[]): void;
  isStopped(): boolean;
}

function reason(error: unknown): string {
  return error instanceof Error ? error.message : "카메라 프레임을 읽지 못했습니다.";
}

export async function runTrackFrameWorker(
  options: TrackFrameWorkerRuntimeOptions,
): Promise<void> {
  const { track, sampleIntervalMs, createProcessor, postMessage, isStopped } = options;
  try {
    const reader = createProcessor(track).readable.getReader();
    await pumpTrackFrames<VideoFrame>({
      sampleIntervalMs,
      isStopped,
      read: async () => {
        const result = await reader.read();
        return result.done
          ? { done: true, value: undefined }
          : { done: false, value: result.value };
      },
      onFrame(frame, timestampMs) {
        postMessage(
          { type: "frame", frame, timestampMs },
          [frame as unknown as Transferable],
        );
      },
    });
  } catch (error) {
    postMessage({ type: "failure", message: reason(error) });
  }
}
