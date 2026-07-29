/// <reference lib="webworker" />

import type { TrackProcessorLike } from "./trackFrameWorkerRuntime";
import { createTrackFrameWorkerSession } from "./trackFrameWorkerSession";
import type {
  TrackFrameWorkerRequest,
  TrackFrameWorkerResponse,
} from "./trackFrameWorkerProtocol";

const scope = self as unknown as DedicatedWorkerGlobalScope;

type TrackProcessorConstructor = new (options: {
  track: MediaStreamTrack;
  maxBufferSize?: number;
}) => TrackProcessorLike;

function createProcessor(track: MediaStreamTrack): TrackProcessorLike {
  const constructor = (
    globalThis as typeof globalThis & { MediaStreamTrackProcessor?: TrackProcessorConstructor }
  ).MediaStreamTrackProcessor;
  if (!constructor) throw new Error("MediaStreamTrackProcessor를 지원하지 않는 브라우저입니다.");
  return new constructor({ track, maxBufferSize: 1 });
}

const session = createTrackFrameWorkerSession({
  createProcessor,
  postMessage(message: TrackFrameWorkerResponse, transfer?: Transferable[]): void {
    scope.postMessage(message, transfer ?? []);
  },
});

scope.onmessage = (event: MessageEvent<TrackFrameWorkerRequest>) => {
  session.handle(event.data);
};
