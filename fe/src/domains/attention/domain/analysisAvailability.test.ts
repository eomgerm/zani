import { describe, expect, it } from "vitest";

import { analysisAvailabilityOf } from "./analysisAvailability";
import type { AttentionStatus } from "./attentionPrediction";

describe("analysisAvailabilityOf", () => {
  it.each(["preparing", "collecting", "measuring"] as const)(
    "treats %s as analysis running",
    (status) => {
      expect(analysisAvailabilityOf(status)).toBe("ACTIVE");
    },
  );

  // 얼굴이 안 잡혀도 분석은 돌고 있다. 따로 표시하면 개별 판정 상태를 노출하는 셈이다.
  it("keeps unmeasurable as running so the individual state stays private", () => {
    expect(analysisAvailabilityOf("unmeasurable")).toBe("ACTIVE");
  });

  it.each(["idle", "permissionDenied"] as const)(
    "treats %s as paused, leaving the reason to the camera prompt",
    (status) => {
      expect(analysisAvailabilityOf(status)).toBe("PAUSED");
    },
  );

  // 검출기 실패는 카메라 문제가 아니라 프롬프트가 다루지 않는다. 여기서만 사유를 밝힌다.
  it("separates a detector failure from a camera problem", () => {
    expect(analysisAvailabilityOf("unavailable")).toBe("UNAVAILABLE");
  });

  it("never reports the same availability for a camera outage and a detector failure", () => {
    expect(analysisAvailabilityOf("idle")).not.toBe(analysisAvailabilityOf("unavailable"));
  });

  it("covers every status the detection hook can report", () => {
    const all: readonly AttentionStatus[] = [
      "idle",
      "preparing",
      "collecting",
      "measuring",
      "unmeasurable",
      "permissionDenied",
      "unavailable",
    ];

    all.forEach((status) => {
      expect(["ACTIVE", "PAUSED", "UNAVAILABLE"]).toContain(analysisAvailabilityOf(status));
    });
  });
});
