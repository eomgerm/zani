import { pumpTrackFrames } from "./trackFramePump";
import type { TrackFrameWorkerResponse } from "./trackFrameWorkerProtocol";

export interface TrackFrameReadable {
  getReader(): {
    read(): Promise<ReadableStreamReadResult<VideoFrame>>;
  };
}

export interface TrackFrameWorkerRuntimeOptions {
  readonly readable: TrackFrameReadable;
  readonly sampleIntervalMs: number;
  postMessage(message: TrackFrameWorkerResponse, transfer?: Transferable[]): void;
  isStopped(): boolean;
}

function reason(error: unknown): string {
  return error instanceof Error ? error.message : "카메라 프레임을 읽지 못했습니다.";
}

export async function runTrackFrameWorker(
  options: TrackFrameWorkerRuntimeOptions,
): Promise<void> {
  const { readable, sampleIntervalMs, postMessage, isStopped } = options;
  try {
    const reader = readable.getReader();
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
          [frame],
        );
      },
    });
  } catch (error) {
    // 정지 요청이 트랙을 끊으면 리더도 거부되므로 의도한 종료를 실패로 보고하지 않는다.
    if (isStopped()) return;
    postMessage({ type: "failure", message: reason(error) });
  }
}
