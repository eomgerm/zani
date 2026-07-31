/// <reference lib="webworker" />

import { createTrackFrameWorkerSession } from "./trackFrameWorkerSession";
import type {
  TrackFrameWorkerRequest,
  TrackFrameWorkerResponse,
} from "./trackFrameWorkerProtocol";

const scope = self as unknown as DedicatedWorkerGlobalScope;

const session = createTrackFrameWorkerSession({
  postMessage(message: TrackFrameWorkerResponse, transfer?: Transferable[]): void {
    scope.postMessage(message, transfer ?? []);
  },
});

scope.onmessage = (event: MessageEvent<TrackFrameWorkerRequest>) => {
  session.handle(event.data);
};
