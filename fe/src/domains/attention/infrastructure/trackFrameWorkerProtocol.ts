export interface StartTrackFrameWorkerRequest {
  readonly type: "start";
  readonly track: MediaStreamTrack;
  readonly sampleIntervalMs: number;
}

export interface StopTrackFrameWorkerRequest {
  readonly type: "stop";
}

export type TrackFrameWorkerRequest =
  | StartTrackFrameWorkerRequest
  | StopTrackFrameWorkerRequest;

export interface TrackFrameResponse {
  readonly type: "frame";
  readonly frame: VideoFrame;
  readonly timestampMs: number;
}

export interface TrackFrameFailureResponse {
  readonly type: "failure";
  readonly message: string;
}

export type TrackFrameWorkerResponse = TrackFrameResponse | TrackFrameFailureResponse;

export interface TrackFrameWorkerPort {
  postMessage(message: TrackFrameWorkerRequest, transfer?: Transferable[]): void;
  terminate(): void;
  onmessage: ((event: MessageEvent<TrackFrameWorkerResponse>) => void) | null;
  onerror: ((event: unknown) => void) | null;
}
