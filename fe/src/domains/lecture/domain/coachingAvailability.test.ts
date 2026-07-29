import { describe, expect, it } from "vitest";

import { coachingAvailabilityOf, type CoachTipUnavailableReason } from "./coachingAvailability";

describe("coachingAvailabilityOf", () => {
  it("stays quiet when a tip simply is not waiting", () => {
    expect(coachingAvailabilityOf(null)).toBe("ACTIVE");
  });

  // 수업 시작 직후에는 버퍼가 늘 60초 미만이다. 이걸 고장으로 표시하면 매 수업 오류가 뜬다.
  it("treats a short audio buffer as normal, not a failure", () => {
    expect(coachingAvailabilityOf("NO_TRANSCRIPT")).toBe("ACTIVE");
  });

  // 모델이 확신이 부족해 거른 것은 설계된 동작이다.
  it("treats a filtered low-confidence tip as normal", () => {
    expect(coachingAvailabilityOf("LOW_CONFIDENCE")).toBe("ACTIVE");
  });

  it.each(["TRANSCRIPTION_FAILED", "TIP_FAILED"] as const)(
    "surfaces %s because the instructor should know it broke",
    (reason) => {
      expect(coachingAvailabilityOf(reason)).toBe(reason);
    },
  );

  it("covers every reason the server can report", () => {
    const all: readonly CoachTipUnavailableReason[] = [
      "NO_TRANSCRIPT",
      "TRANSCRIPTION_FAILED",
      "TIP_FAILED",
      "LOW_CONFIDENCE",
    ];

    all.forEach((reason) => {
      expect(["ACTIVE", "TRANSCRIPTION_FAILED", "TIP_FAILED"]).toContain(
        coachingAvailabilityOf(reason),
      );
    });
  });
});
