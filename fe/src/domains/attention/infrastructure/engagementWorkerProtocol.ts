import type { EngagementPrediction } from "../domain/engagementPrediction";

/**
 * 메인 스레드와 추론 Worker 사이의 메시지 계약.
 *
 * 토큰(20×98 float)만 오가고 카메라 프레임이나 랜드마크 원본은 경계를 넘지 않는다.
 * 모든 값은 구조화 복제가 가능하다.
 */

export interface PredictRequest {
  readonly type: "predict";
  /** 응답과 요청을 맞추고, 취소된(밀린) 응답을 버리기 위한 단조 증가 번호. */
  readonly requestId: number;
  readonly tokens: Float32Array;
}

export type EngagementWorkerRequest = PredictRequest;

export interface PredictionResponse {
  readonly type: "prediction";
  readonly requestId: number;
  readonly prediction: EngagementPrediction;
}

/**
 * 실패 종류.
 * - `modelUnavailable`: 모델·메타데이터를 못 불러왔다. 판정을 비활성화하고 수업은 계속한다.
 * - `inferenceFailed`: 세션은 살아 있고 이번 추론만 실패했다. 다음 창을 다시 시도한다.
 */
export type EngagementFailureKind = "modelUnavailable" | "inferenceFailed";

export interface FailureResponse {
  readonly type: "failure";
  /** 특정 요청과 무관한 준비 단계 실패면 null. */
  readonly requestId: number | null;
  readonly kind: EngagementFailureKind;
  readonly message: string;
}

export type EngagementWorkerResponse = PredictionResponse | FailureResponse;

/** Worker 를 테스트에서 대체할 수 있도록 실제로 쓰는 부분만 추린 포트. */
export interface EngagementWorkerPort {
  postMessage(message: EngagementWorkerRequest, transfer?: Transferable[]): void;
  terminate(): void;
  onmessage: ((event: MessageEvent<EngagementWorkerResponse>) => void) | null;
  onerror: ((event: unknown) => void) | null;
}
