import { runTrackFrameWorker } from "./trackFrameWorkerRuntime";
import type {
  TrackFrameWorkerRequest,
  TrackFrameWorkerResponse,
} from "./trackFrameWorkerProtocol";

/**
 * Worker 안에서 전송된 프레임 스트림을 읽고 정지 요청을 처리한다.
 */

export interface TrackFrameWorkerSessionOptions {
  postMessage(message: TrackFrameWorkerResponse, transfer?: Transferable[]): void;
}

export interface TrackFrameWorkerSession {
  handle(request: TrackFrameWorkerRequest): void;
}

export function createTrackFrameWorkerSession(
  options: TrackFrameWorkerSessionOptions,
): TrackFrameWorkerSession {
  const { postMessage } = options;

  let stopped = false;

  return {
    handle(request: TrackFrameWorkerRequest): void {
      if (request.type === "stop") {
        stopped = true;
        postMessage({ type: "stopped" });
        return;
      }

      stopped = false;
      void runTrackFrameWorker({
        readable: request.readable,
        sampleIntervalMs: request.sampleIntervalMs,
        postMessage,
        isStopped: () => stopped,
      });
    },
  };
}
