import { ATTENTION_DETECTION_CONFIG } from "../domain/attentionDetectionConfig";
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

/** 정지 보고를 이만큼 기다린 뒤에는 Worker 를 강제로 종료한다. */
export const TRACK_FRAME_WORKER_STOP_TIMEOUT_MS = 1_000;

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
  let terminated = false;
  let stopTimer: ReturnType<typeof setTimeout> | null = null;

  function terminate(): void {
    if (terminated) return;
    terminated = true;
    if (stopTimer !== null) {
      clearTimeout(stopTimer);
      stopTimer = null;
    }
    worker.terminate();
  }

  worker.onmessage = (event: MessageEvent<TrackFrameWorkerResponse>) => {
    const response = event.data;
    if (response.type === "stopped") {
      // 카메라 트랙이 끊긴 뒤이므로 이제 Worker 를 버려도 된다.
      terminate();
      return;
    }
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
    // transfer 가 실패했으면 트랙은 아직 이쪽 소유라 직접 끊을 수 있다.
    clonedTrack.stop();
    stopped = true;
    terminate();
    onFailure(workerErrorMessage(error));
  }

  return {
    stop(): void {
      if (stopped) return;
      stopped = true;
      worker.postMessage({ type: "stop" });
      // Worker 가 보고하지 못해도 영원히 살아 있지 않게 한다.
      stopTimer = setTimeout(terminate, TRACK_FRAME_WORKER_STOP_TIMEOUT_MS);
    },
  };
}
