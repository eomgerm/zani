import { ATTENTION_DETECTION_CONFIG } from "./attentionDetectionConfig";
import type { DetectorOutput } from "./detectionOutcome";

export interface AttentionCoachingState {
  readonly lowEngagementCount: number;
  readonly unmeasurableCount: number;
}

export interface AttentionCoachingDecision {
  readonly state: AttentionCoachingState;
  readonly prompt: "UNDERSTANDING_CHECK" | "POSTURE_GUIDE" | null;
}

export interface AttentionCoachingInput {
  readonly output: DetectorOutput;
  readonly promptVisible: boolean;
}

export const INITIAL_ATTENTION_COACHING_STATE: AttentionCoachingState = {
  lowEngagementCount: 0,
  unmeasurableCount: 0,
};

export function reduceAttentionCoaching(
  state: AttentionCoachingState,
  input: AttentionCoachingInput,
): AttentionCoachingDecision {
  if (input.promptVisible) return { state, prompt: null };

  if (input.output.outcome === "UNMEASURABLE") {
    const unmeasurableCount = state.unmeasurableCount + 1;
    return {
      state: { lowEngagementCount: state.lowEngagementCount, unmeasurableCount },
      prompt:
        unmeasurableCount % ATTENTION_DETECTION_CONFIG.consecutiveDetectionCount === 0
          ? "POSTURE_GUIDE"
          : null,
    };
  }

  if ("probabilities" in input.output) {
    const lowEngagementProbability =
      input.output.probabilities[0] + input.output.probabilities[1];
    if (
      lowEngagementProbability >=
      ATTENTION_DETECTION_CONFIG.lowEngagementProbabilityThreshold
    ) {
      const lowEngagementCount = state.lowEngagementCount + 1;
      return {
        state: {
          lowEngagementCount,
          unmeasurableCount: 0,
        },
        prompt:
          lowEngagementCount % ATTENTION_DETECTION_CONFIG.consecutiveDetectionCount === 0
            ? "UNDERSTANDING_CHECK"
            : null,
      };
    }
  }

  return { state: INITIAL_ATTENTION_COACHING_STATE, prompt: null };
}
