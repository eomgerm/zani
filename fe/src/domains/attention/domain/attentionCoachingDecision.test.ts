import { describe, expect, it } from "vitest";

import {
  INITIAL_ATTENTION_COACHING_STATE,
  reduceAttentionCoaching,
} from "./attentionCoachingDecision";

describe("reduceAttentionCoaching", () => {
  it("counts low engagement from the two low-class probabilities at the 0.35 boundary", () => {
    const result = reduceAttentionCoaching(INITIAL_ATTENTION_COACHING_STATE, {
      output: {
        outcome: "Engaged",
        probabilities: [0.2, 0.15, 0.6, 0.05],
      },
      promptVisible: false,
    });

    expect(result).toEqual({
      state: { lowEngagementCount: 1, unmeasurableCount: 0 },
      prompt: null,
    });
  });

  it("requests an understanding check on the third consecutive low-engagement window", () => {
    const lowOutput = {
      outcome: "Barely-Engaged" as const,
      probabilities: [0.1, 0.3, 0.5, 0.1],
    };
    const first = reduceAttentionCoaching(INITIAL_ATTENTION_COACHING_STATE, {
      output: lowOutput,
      promptVisible: false,
    });
    const second = reduceAttentionCoaching(first.state, {
      output: lowOutput,
      promptVisible: false,
    });
    const third = reduceAttentionCoaching(second.state, {
      output: lowOutput,
      promptVisible: false,
    });

    expect(first.prompt).toBeNull();
    expect(second.prompt).toBeNull();
    expect(third).toEqual({
      state: { lowEngagementCount: 3, unmeasurableCount: 0 },
      prompt: "UNDERSTANDING_CHECK",
    });
  });

  it("preserves the low-engagement count and requests posture guidance on the third unmeasurable window", () => {
    let state = { lowEngagementCount: 2, unmeasurableCount: 0 };
    let prompt: string | null = null;

    for (let count = 0; count < 3; count += 1) {
      const decision = reduceAttentionCoaching(state, {
        output: { outcome: "UNMEASURABLE" },
        promptVisible: false,
      });
      state = decision.state;
      prompt = decision.prompt;
    }

    expect(state).toEqual({ lowEngagementCount: 2, unmeasurableCount: 3 });
    expect(prompt).toBe("POSTURE_GUIDE");
  });

  it("resets both counts for a non-low four-class window", () => {
    const result = reduceAttentionCoaching(
      { lowEngagementCount: 2, unmeasurableCount: 2 },
      {
        output: {
          outcome: "Engaged",
          probabilities: [0.1, 0.1, 0.7, 0.1],
        },
        promptVisible: false,
      },
    );

    expect(result.state).toEqual(INITIAL_ATTENTION_COACHING_STATE);
  });

  it("resets only the unmeasurable count for a low-engagement window", () => {
    const result = reduceAttentionCoaching(
      { lowEngagementCount: 1, unmeasurableCount: 2 },
      {
        output: {
          outcome: "Barely-Engaged",
          probabilities: [0.1, 0.3, 0.5, 0.1],
        },
        promptVisible: false,
      },
    );

    expect(result.state).toEqual({ lowEngagementCount: 2, unmeasurableCount: 0 });
  });

  it.each(["CAMERA_OFF", "DETECTOR_UNAVAILABLE"] as const)(
    "resets both counts for %s",
    (outcome) => {
      const result = reduceAttentionCoaching(
        { lowEngagementCount: 2, unmeasurableCount: 2 },
        { output: { outcome }, promptVisible: false },
      );

      expect(result.state).toEqual(INITIAL_ATTENTION_COACHING_STATE);
    },
  );

  it("pauses both counts while any student prompt is visible", () => {
    const state = { lowEngagementCount: 2, unmeasurableCount: 1 };
    const result = reduceAttentionCoaching(state, {
      output: { outcome: "UNMEASURABLE" },
      promptVisible: true,
    });

    expect(result).toEqual({ state, prompt: null });
  });
});
