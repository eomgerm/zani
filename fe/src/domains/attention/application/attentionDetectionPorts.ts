import type { AttentionPrediction } from "../domain/attentionPrediction";

/** application이 소유하는 프레임 최소 계약. 실제 VideoFrame은 infrastructure가 제공한다. */
export interface AttentionFrame {
  close(): void;
}

/** MediaPipe 출력과 특징 추출을 감춘 49차원 특징 검출 포트. */
export interface AttentionFeatureDetector {
  detect(frame: AttentionFrame, timestampMs: number): Float32Array | null;
  close(): void;
}

export interface AttentionFrameSourceHandlers {
  onFrame(frame: AttentionFrame, timestampMs: number): void;
  onFailure(message: string): void;
}

export interface AttentionFrameSource {
  stop(): void;
}

export interface AttentionInferenceFailure {
  /** `modelUnavailable` 만 판정을 비활성화한다. 나머지는 다음 창에서 다시 시도한다. */
  readonly kind: "modelUnavailable" | "modelLoadRetrying" | "inferenceFailed";
  readonly message: string;
}

export interface AttentionInferenceClient {
  submit(tokens: Float32Array): void;
  terminate(): void;
}

export interface AttentionInferenceHandlers {
  onPrediction(prediction: AttentionPrediction): void;
  onFailure(failure: AttentionInferenceFailure): void;
}

export type CreateAttentionFrameSource = (
  handlers: AttentionFrameSourceHandlers,
) => AttentionFrameSource;

export type CreateAttentionFeatureDetector = () => Promise<AttentionFeatureDetector>;

export type CreateAttentionInferenceClient = (
  handlers: AttentionInferenceHandlers,
) => AttentionInferenceClient;
