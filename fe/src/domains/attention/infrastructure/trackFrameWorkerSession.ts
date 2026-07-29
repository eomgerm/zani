import { runTrackFrameWorker, type TrackProcessorLike } from "./trackFrameWorkerRuntime";
import type {
  TrackFrameWorkerRequest,
  TrackFrameWorkerResponse,
} from "./trackFrameWorkerProtocol";

/**
 * Worker 안에서 카메라 트랙의 수명을 쥐는 요청 처리기.
 *
 * transfer 된 트랙은 여기서만 닿을 수 있으므로 정지 요청이 오면 트랙을 끊고 완료를
 * 보고한다. Worker 진입 모듈과 분리해 두면 이 수명 규칙을 단위 테스트할 수 있다.
 */

export interface TrackFrameWorkerSessionOptions {
  createProcessor(track: MediaStreamTrack): TrackProcessorLike;
  postMessage(message: TrackFrameWorkerResponse, transfer?: Transferable[]): void;
}

export interface TrackFrameWorkerSession {
  handle(request: TrackFrameWorkerRequest): void;
}

export function createTrackFrameWorkerSession(
  options: TrackFrameWorkerSessionOptions,
): TrackFrameWorkerSession {
  const { createProcessor, postMessage } = options;

  let stopped = false;
  let activeTrack: MediaStreamTrack | null = null;

  return {
    handle(request: TrackFrameWorkerRequest): void {
      if (request.type === "stop") {
        stopped = true;
        activeTrack?.stop();
        activeTrack = null;
        // 보고를 마친 뒤에야 메인 스레드가 Worker 를 종료한다.
        postMessage({ type: "stopped" });
        return;
      }

      stopped = false;
      activeTrack = request.track;
      void runTrackFrameWorker({
        track: request.track,
        sampleIntervalMs: request.sampleIntervalMs,
        createProcessor,
        postMessage,
        isStopped: () => stopped,
      });
    },
  };
}
