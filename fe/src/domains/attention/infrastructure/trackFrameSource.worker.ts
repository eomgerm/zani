/// <reference lib="webworker" />

import { runTrackFrameWorker, type TrackProcessorLike } from "./trackFrameWorkerRuntime";
import type {
  TrackFrameWorkerRequest,
  TrackFrameWorkerResponse,
} from "./trackFrameWorkerProtocol";

const scope = self as unknown as DedicatedWorkerGlobalScope;

type TrackProcessorConstructor = new (options: {
  track: MediaStreamTrack;
  maxBufferSize?: number;
}) => TrackProcessorLike;

let stopped = false;
let activeTrack: MediaStreamTrack | null = null;

function createProcessor(track: MediaStreamTrack): TrackProcessorLike {
  const constructor = (
    globalThis as typeof globalThis & { MediaStreamTrackProcessor?: TrackProcessorConstructor }
  ).MediaStreamTrackProcessor;
  if (!constructor) throw new Error("MediaStreamTrackProcessor를 지원하지 않는 브라우저입니다.");
  return new constructor({ track, maxBufferSize: 1 });
}

function postMessage(message: TrackFrameWorkerResponse, transfer?: Transferable[]): void {
  scope.postMessage(message, transfer ?? []);
}

scope.onmessage = (event: MessageEvent<TrackFrameWorkerRequest>) => {
  const request = event.data;
  if (request.type === "stop") {
    stopped = true;
    activeTrack?.stop();
    activeTrack = null;
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
};
