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

/**
 * 서버 보고 경계. 원본 프레임·랜드마크·확률을 담을 수 없다.
 *
 * 서버 `AttentionEventRequest` 가 계약 밖 필드를 400 으로 거절하므로, 여기에 필드를 더할 때는
 * 그 계약에 있는 값인지 먼저 확인한다.
 */
export interface DetectorReport {
  readonly outcome: DetectionOutcome;
  readonly observedAtMs: number;
  /**
   * 10초 창의 시작 시각. 창을 관측한 보고에만 있다.
   *
   * 카메라 OFF·검출기 불가처럼 창 없이 상태만 반복해 알리는 보고에는 없다 — 없는 관측 구간을
   * 지어내면 서버가 그것을 수업 후 리포트의 근거로 남긴다.
   *
   * **측정값이 아니라 재구성값이다.** 창이 찼는지는 프레임 타임스탬프로 판단하는데 이 값은 벽시계
   * 관측 시각에서 창 길이를 뺀 것이라, 프레임 도착이 밀린 창에서는 실제 시작보다 늦게 찍힌다
   * (느린 기기에서 오차가 커진다). 정밀한 창 시각이 필요해지면 판정 세션이 실제 시작 시각을
   * 함께 내보내야 한다.
   */
  readonly windowStartedAtMs?: number;
  /**
   * 서버가 중복을 판별하는 멱등키. 리포트를 만들 때 한 번 정하고 재시도해도 바꾸지 않는다 —
   * 새 값을 만들면 같은 관측이 두 번 반영된다.
   */
  readonly clientEventId: string;
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
