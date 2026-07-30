import {
  toDetectorReportOutcome,
  type DetectorOutput,
  type DetectorReport,
  type ImmediateDetectorOutput,
} from "../domain/detectionOutcome";

export interface DetectionReportControllerOptions {
  readonly reportIntervalMs: number;
  readonly now?: () => number;
  readonly isReportingAllowed?: () => boolean;
  onReport(report: DetectorReport): void;
}

export interface DetectionReportController {
  reportWindow(output: DetectorOutput): void;
  startImmediate(output: ImmediateDetectorOutput): void;
  stop(): void;
}

/** 검출기 내부 출력에서 서버에 허용된 최소 보고 DTO만 만든다. */
export function createDetectionReportController(
  options: DetectionReportControllerOptions,
): DetectionReportController {
  const {
    reportIntervalMs,
    now = Date.now,
    isReportingAllowed = () => true,
    onReport,
  } = options;
  let interval: ReturnType<typeof setInterval> | null = null;

  const emit = (output: DetectorOutput): void => {
    if (!isReportingAllowed()) return;
    onReport({ outcome: toDetectorReportOutcome(output), observedAtMs: now() });
  };

  const stop = (): void => {
    if (interval === null) return;
    clearInterval(interval);
    interval = null;
  };

  return {
    reportWindow(output): void {
      emit(output);
    },
    startImmediate(output): void {
      stop();
      emit(output);
      interval = setInterval(() => emit(output), reportIntervalMs);
    },
    stop,
  };
}
