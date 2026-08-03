import { ATTENTION_DETECTION_CONFIG } from "../domain/attentionDetectionConfig";
import type {
  TrackFrameWorkerPort,
  TrackFrameWorkerResponse,
} from "./trackFrameWorkerProtocol";

export interface TrackProcessorFrameSourceOptions {
  readonly track: MediaStreamTrack;
  createWorker?: () => TrackFrameWorkerPort;
  createProcessor?: (track: MediaStreamTrack) => TrackProcessorLike;
  onFrame(frame: VideoFrame, timestampMs: number): void;
  onFailure(message: string): void;
}

export interface TrackProcessorLike {
  readonly readable: ReadableStream<VideoFrame>;
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

type TrackProcessorConstructor = new (options: {
  track: MediaStreamTrack;
  maxBufferSize?: number;
}) => TrackProcessorLike;

function createBrowserTrackProcessor(track: MediaStreamTrack): TrackProcessorLike {
  const constructor = (
    globalThis as typeof globalThis & { MediaStreamTrackProcessor?: TrackProcessorConstructor }
  ).MediaStreamTrackProcessor;
  if (!constructor) throw new Error("MediaStreamTrackProcessor를 지원하지 않는 브라우저입니다.");
  return new constructor({ track, maxBufferSize: 1 });
}

function workerErrorMessage(event: unknown): string {
  return typeof event === "object" && event !== null && "message" in event
    ? String((event as { message: unknown }).message)
    : "카메라 프레임 Worker를 시작하지 못했습니다.";
}

export function createTrackProcessorFrameSource(
  options: TrackProcessorFrameSourceOptions,
): TrackProcessorFrameSource {
  const {
    track,
    createWorker = spawnWorker,
    createProcessor = createBrowserTrackProcessor,
    onFrame,
    onFailure,
  } = options;
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

  function fail(message: string): void {
    if (stopped) return;
    stopped = true;
    clonedTrack.stop();
    terminate();
    onFailure(message);
  }

  worker.onmessage = (event: MessageEvent<TrackFrameWorkerResponse>) => {
    const response = event.data;
    if (response.type === "stopped") {
      // Worker가 정지 요청을 처리했으므로 이제 안전하게 버릴 수 있다.
      terminate();
      return;
    }
    if (response.type === "failure") {
      fail(response.message);
      return;
    }
    if (stopped) {
      response.frame.close();
      return;
    }
    onFrame(response.frame, response.timestampMs);
  };
  worker.onerror = (event: unknown) => {
    fail(workerErrorMessage(event));
  };

  try {
    const readable = createProcessor(clonedTrack).readable;
    worker.postMessage(
      {
        type: "start",
        readable,
        sampleIntervalMs: ATTENTION_DETECTION_CONFIG.sampleIntervalMs,
      },
      [readable],
    );
  } catch (error) {
    fail(workerErrorMessage(error));
  }

  return {
    stop(): void {
      if (stopped) return;
      stopped = true;
      // 분석 전용 clone만 끝낸다. LiveKit이 publish 중인 원본 track은 건드리지 않는다.
      clonedTrack.stop();
      worker.postMessage({ type: "stop" });
      // Worker 가 보고하지 못해도 영원히 살아 있지 않게 한다.
      stopTimer = setTimeout(terminate, TRACK_FRAME_WORKER_STOP_TIMEOUT_MS);
    },
  };
}
