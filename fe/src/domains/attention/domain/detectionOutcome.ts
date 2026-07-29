import type { AttentionLabel } from "./attentionPrediction";

export type DetectorOutcomeName =
  | AttentionLabel
  | "UNMEASURABLE"
  | "CAMERA_OFF"
  | "DETECTOR_UNAVAILABLE";

export type PredictionDetectorOutput = {
  readonly outcome: AttentionLabel;
  /** 로컬 상태 판정에만 사용하며 보고 DTO에는 포함하지 않는다. */
  readonly probabilities: readonly number[];
};

export type DetectorOutput =
  | PredictionDetectorOutput
  | { readonly outcome: Exclude<DetectorOutcomeName, AttentionLabel> };

export type ImmediateDetectorOutput = {
  readonly outcome: "CAMERA_OFF" | "DETECTOR_UNAVAILABLE";
};

/** 서버 보고 경계. 원본 프레임·랜드마크·확률을 담을 수 없다. */
export interface DetectorReport {
  readonly outcome: DetectorOutcomeName;
  readonly observedAtMs: number;
}
