import { describe, expect, expectTypeOf, it } from "vitest";

import type { DetectorOutput } from "./detectionOutcome";
import { toDetectorReportOutcome } from "./detectionOutcome";

describe("DetectorOutput", () => {
  it("requires probabilities for every local four-class prediction", () => {
    expectTypeOf<{ readonly outcome: "NOT_ENGAGED" }>()
      .not.toMatchTypeOf<DetectorOutput>();
  });

  it.each([
    ["Not-Engaged", "NOT_ENGAGED"],
    ["Barely-Engaged", "BARELY_ENGAGED"],
    ["Engaged", "ENGAGED"],
    ["Highly-Engaged", "HIGHLY_ENGAGED"],
  ] as const)("maps the local %s label to the server %s outcome", (outcome, expected) => {
    expect(
      toDetectorReportOutcome({
        outcome,
        probabilities: [0.25, 0.25, 0.25, 0.25],
      }),
    ).toBe(expected);
  });

  it.each(["UNMEASURABLE", "CAMERA_OFF", "DETECTOR_UNAVAILABLE"] as const)(
    "keeps the local %s outcome unchanged at the server boundary",
    (outcome) => {
      const output = { outcome };

      expect(toDetectorReportOutcome(output)).toBe(outcome);
      expect(output).not.toHaveProperty("probabilities");
    },
  );
});
