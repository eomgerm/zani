import { ATTENTION_DETECTION_CONFIG } from "./attentionDetectionConfig";
import type {
  TrackFrameWorkerPort,
  TrackFrameWorkerResponse,
} from "./trackFrameWorkerProtocol";

export interface TrackProcessorFrameSourceOptions {
  readonly track: MediaStreamTrack;
  createWorker?: () => TrackFrameWorkerPort;
  onFrame(frame: VideoFrame, timestampMs: number): void;
  onFailure(message: string): void;
}

export interface TrackProcessorFrameSource {
  stop(): void;
}

function spawnWorker(): TrackFrameWorkerPort {
  return new Worker(new URL("./trackFrameSource.worker.ts", import.meta.url), {
    type: "module",
  }) as unknown as TrackFrameWorkerPort;
}

function workerErrorMessage(event: unknown): string {
  return typeof event === "object" && event !== null && "message" in event
    ? String((event as { message: unknown }).message)
    : "카메라 프레임 Worker를 시작하지 못했습니다.";
}

export function createTrackProcessorFrameSource(
  options: TrackProcessorFrameSourceOptions,
): TrackProcessorFrameSource {
  const { track, createWorker = spawnWorker, onFrame, onFailure } = options;
  const clonedTrack = track.clone();
  let worker: TrackFrameWorkerPort;
  try {
    worker = createWorker();
  } catch (error) {
    clonedTrack.stop();
    throw error;
  }
  let stopped = false;

  worker.onmessage = (event: MessageEvent<TrackFrameWorkerResponse>) => {
    const response = event.data;
    if (response.type === "failure") {
      if (!stopped) onFailure(response.message);
      return;
    }
    if (stopped) {
      response.frame.close();
      return;
    }
    onFrame(response.frame, response.timestampMs);
  };
  worker.onerror = (event: unknown) => {
    if (!stopped) onFailure(workerErrorMessage(event));
  };

  try {
    worker.postMessage(
      {
        type: "start",
        track: clonedTrack,
        sampleIntervalMs: ATTENTION_DETECTION_CONFIG.sampleIntervalMs,
      },
      [clonedTrack as unknown as Transferable],
    );
  } catch (error) {
    clonedTrack.stop();
    worker.terminate();
    stopped = true;
    onFailure(workerErrorMessage(error));
  }

  return {
    stop(): void {
      if (stopped) return;
      stopped = true;
      worker.postMessage({ type: "stop" });
      worker.terminate();
    },
  };
}
