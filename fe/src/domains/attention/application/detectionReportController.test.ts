import { describe, expect, it, vi } from "vitest";

import type { DetectorReport } from "../domain/detectionOutcome";
import { createDetectionReportController } from "./detectionReportController";

describe("createDetectionReportController", () => {
  it("reports a four-class outcome without exposing its probabilities", () => {
    const reports: DetectorReport[] = [];
    const controller = createDetectionReportController({
      now: () => 12_345,
      onReport: (report) => reports.push(report),
    });

    controller.reportWindow({
      outcome: "Engaged",
      probabilities: [0.1, 0.2, 0.6, 0.1],
    });

    expect(reports).toEqual([{ outcome: "Engaged", observedAtMs: 12_345 }]);
    expect(reports[0]).not.toHaveProperty("probabilities");
  });

  it("reports CAMERA_OFF immediately and repeats it every ten seconds", () => {
    vi.useFakeTimers();
    vi.setSystemTime(0);
    const reports: DetectorReport[] = [];
    const controller = createDetectionReportController({
      onReport: (report) => reports.push(report),
    });

    controller.startImmediate({ outcome: "CAMERA_OFF" });
    expect(reports).toEqual([{ outcome: "CAMERA_OFF", observedAtMs: 0 }]);

    vi.advanceTimersByTime(9_999);
    expect(reports).toHaveLength(1);

    vi.advanceTimersByTime(1);
    expect(reports).toEqual([
      { outcome: "CAMERA_OFF", observedAtMs: 0 },
      { outcome: "CAMERA_OFF", observedAtMs: 10_000 },
    ]);

    controller.stop();
    vi.useRealTimers();
  });

  it("keeps the repeat schedule while hidden but suppresses reports", () => {
    vi.useFakeTimers();
    vi.setSystemTime(0);
    let visible = false;
    const reports: DetectorReport[] = [];
    const controller = createDetectionReportController({
      isReportingAllowed: () => visible,
      onReport: (report) => reports.push(report),
    });

    controller.startImmediate({ outcome: "DETECTOR_UNAVAILABLE" });
    vi.advanceTimersByTime(10_000);
    expect(reports).toEqual([]);

    visible = true;
    vi.advanceTimersByTime(10_000);
    expect(reports).toEqual([
      { outcome: "DETECTOR_UNAVAILABLE", observedAtMs: 20_000 },
    ]);

    controller.stop();
    vi.useRealTimers();
  });
});
