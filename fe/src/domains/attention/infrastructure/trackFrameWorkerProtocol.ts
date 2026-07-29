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

/**
 * 정지 요청 처리 완료 보고.
 *
 * 카메라 트랙은 Worker 로 transfer 되어 메인 스레드 핸들이 떨어졌으므로 Worker 만
 * 정지시킬 수 있다. 메인 스레드는 이 응답을 받은 뒤에 Worker 를 종료한다.
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
