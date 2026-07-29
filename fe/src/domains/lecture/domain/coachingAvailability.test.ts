import { describe, expect, it } from "vitest";

import { COACH_POLL_FAILURE_THRESHOLD, coachingAvailabilityOf } from "./coachingAvailability";

describe("coachingAvailabilityOf", () => {
  it("stays quiet while polling succeeds", () => {
    expect(coachingAvailabilityOf(0)).toBe("ACTIVE");
  });

  // 한두 번은 네트워크가 흔들린 것일 수 있다. 매번 배지를 띄우면 정상 동작이 고장처럼 보인다.
  it("tolerates a failure that has not repeated enough yet", () => {
    expect(coachingAvailabilityOf(COACH_POLL_FAILURE_THRESHOLD - 1)).toBe("ACTIVE");
  });

  it("reports once the failures reach the threshold", () => {
    expect(coachingAvailabilityOf(COACH_POLL_FAILURE_THRESHOLD)).toBe("POLL_FAILED");
  });

  it("keeps reporting while the failures continue", () => {
    expect(coachingAvailabilityOf(COACH_POLL_FAILURE_THRESHOLD + 5)).toBe("POLL_FAILED");
  });

  // 10초 주기라 3회면 30초다. 이보다 짧으면 흔들림에, 길면 강사가 너무 늦게 안다.
  it("waits about half a minute before bothering the instructor", () => {
    expect(COACH_POLL_FAILURE_THRESHOLD).toBe(3);
  });
});
