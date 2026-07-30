export interface StartTrackFrameWorkerRequest {
  readonly type: "start";
  readonly readable: ReadableStream<VideoFrame>;
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

/**
 * 정지 요청 처리 완료 보고.
 *
 * Worker가 전송받은 프레임 스트림의 정지 요청 처리를 마쳤음을 알린다.
 */
export interface TrackFrameStoppedResponse {
  readonly type: "stopped";
}

export type TrackFrameWorkerResponse =
  | TrackFrameResponse
  | TrackFrameFailureResponse
  | TrackFrameStoppedResponse;

export interface TrackFrameWorkerPort {
  postMessage(message: TrackFrameWorkerRequest, transfer?: Transferable[]): void;
  terminate(): void;
  onmessage: ((event: MessageEvent<TrackFrameWorkerResponse>) => void) | null;
  onerror: ((event: unknown) => void) | null;
}
