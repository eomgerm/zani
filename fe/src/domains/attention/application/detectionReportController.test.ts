import { describe, expect, it, vi } from "vitest";

import type { DetectorReport } from "../domain/detectionOutcome";
import { createDetectionReportController } from "./detectionReportController";

/** 테스트가 세는 순번 이벤트 ID 생성기. 리포트마다 새 값이 나오는지 눈으로 확인하려고 쓴다. */
function sequentialEventIds(): () => string {
  let next = 0;
  return () => {
    next += 1;
    return `event-${next}`;
  };
}

describe("createDetectionReportController", () => {
  it("reports a four-class outcome without exposing its probabilities", () => {
    const reports: DetectorReport[] = [];
    const controller = createDetectionReportController({
      reportIntervalMs: 10_000,
      windowMs: 10_000,
      now: () => 12_345,
      newEventId: sequentialEventIds(),
      onReport: (report) => reports.push(report),
    });

    controller.reportWindow({
      outcome: "Engaged",
      probabilities: [0.1, 0.2, 0.6, 0.1],
    });

    expect(reports).toEqual([
      {
        outcome: "ENGAGED",
        observedAtMs: 12_345,
        windowStartedAtMs: 2_345,
        clientEventId: "event-1",
      },
    ]);
    expect(reports[0]).not.toHaveProperty("probabilities");
  });

  // 서버가 이 값으로 중복을 판별한다. 두 관측이 같은 값을 쓰면 뒤엣것이 조용히 버려진다.
  it("stamps every report with its own client event id", () => {
    const reports: DetectorReport[] = [];
    const controller = createDetectionReportController({
      reportIntervalMs: 10_000,
      windowMs: 10_000,
      now: () => 12_345,
      newEventId: sequentialEventIds(),
      onReport: (report) => reports.push(report),
    });

    controller.reportWindow({ outcome: "UNMEASURABLE" });
    controller.reportWindow({ outcome: "UNMEASURABLE" });

    expect(reports.map((report) => report.clientEventId)).toEqual(["event-1", "event-2"]);
  });

  it("reports CAMERA_OFF immediately and repeats it every ten seconds", () => {
    vi.useFakeTimers();
    vi.setSystemTime(0);
    const reports: DetectorReport[] = [];
    const controller = createDetectionReportController({
      reportIntervalMs: 10_000,
      windowMs: 10_000,
      newEventId: sequentialEventIds(),
      onReport: (report) => reports.push(report),
    });

    controller.startImmediate({ outcome: "CAMERA_OFF" });
    expect(reports).toEqual([
      { outcome: "CAMERA_OFF", observedAtMs: 0, clientEventId: "event-1" },
    ]);

    vi.advanceTimersByTime(9_999);
    expect(reports).toHaveLength(1);

    vi.advanceTimersByTime(1);
    expect(reports).toEqual([
      { outcome: "CAMERA_OFF", observedAtMs: 0, clientEventId: "event-1" },
      { outcome: "CAMERA_OFF", observedAtMs: 10_000, clientEventId: "event-2" },
    ]);

    controller.stop();
    vi.useRealTimers();
  });

  // 정지 상태 반복 보고는 10초 창을 관측한 것이 아니다. 창 시작 시각을 지어내면 서버가
  // 있지도 않은 관측 구간을 수업 후 리포트의 근거로 남긴다.
  it("omits the window start for a stopped-state report", () => {
    vi.useFakeTimers();
    vi.setSystemTime(30_000);
    const reports: DetectorReport[] = [];
    const controller = createDetectionReportController({
      reportIntervalMs: 10_000,
      windowMs: 10_000,
      newEventId: sequentialEventIds(),
      onReport: (report) => reports.push(report),
    });

    controller.startImmediate({ outcome: "DETECTOR_UNAVAILABLE" });

    expect(reports[0]).not.toHaveProperty("windowStartedAtMs");

    controller.stop();
    vi.useRealTimers();
  });

  it("keeps the repeat schedule while hidden but suppresses reports", () => {
    vi.useFakeTimers();
    vi.setSystemTime(0);
    let visible = false;
    const reports: DetectorReport[] = [];
    const controller = createDetectionReportController({
      reportIntervalMs: 10_000,
      windowMs: 10_000,
      isReportingAllowed: () => visible,
      newEventId: sequentialEventIds(),
      onReport: (report) => reports.push(report),
    });

    controller.startImmediate({ outcome: "DETECTOR_UNAVAILABLE" });
    vi.advanceTimersByTime(10_000);
    expect(reports).toEqual([]);

    visible = true;
    vi.advanceTimersByTime(10_000);
    expect(reports).toEqual([
      { outcome: "DETECTOR_UNAVAILABLE", observedAtMs: 20_000, clientEventId: "event-1" },
    ]);

    controller.stop();
    vi.useRealTimers();
  });
});
