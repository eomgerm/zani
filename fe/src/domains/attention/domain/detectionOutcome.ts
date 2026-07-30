import type { AttentionLabel } from "./attentionPrediction";

export type DetectionOutcome =
  | "NOT_ENGAGED"
  | "BARELY_ENGAGED"
  | "ENGAGED"
  | "HIGHLY_ENGAGED"
  | "UNMEASURABLE"
  | "CAMERA_OFF"
  | "DETECTOR_UNAVAILABLE";

export type PredictionDetectorOutput = {
  readonly outcome: AttentionLabel;
  /** 로컬 상태 판정에만 사용하며 보고 DTO에는 포함하지 않는다. */
  readonly probabilities: readonly number[];
};

export type NonPredictionDetectorOutcome =
  | "UNMEASURABLE"
  | "CAMERA_OFF"
  | "DETECTOR_UNAVAILABLE";

export type DetectorOutput =
  | PredictionDetectorOutput
  | { readonly outcome: NonPredictionDetectorOutcome };

export type ImmediateDetectorOutput = {
  readonly outcome: "CAMERA_OFF" | "DETECTOR_UNAVAILABLE";
};

/** 서버 보고 경계. 원본 프레임·랜드마크·확률을 담을 수 없다. */
export interface DetectorReport {
  readonly outcome: DetectionOutcome;
  readonly observedAtMs: number;
}

const REPORT_OUTCOME_BY_ATTENTION_LABEL: Record<AttentionLabel, DetectionOutcome> = {
  "Not-Engaged": "NOT_ENGAGED",
  "Barely-Engaged": "BARELY_ENGAGED",
  Engaged: "ENGAGED",
  "Highly-Engaged": "HIGHLY_ENGAGED",
};

export function toDetectorReportOutcome(output: DetectorOutput): DetectionOutcome {
  return "probabilities" in output
    ? REPORT_OUTCOME_BY_ATTENTION_LABEL[output.outcome]
    : output.outcome;
}
