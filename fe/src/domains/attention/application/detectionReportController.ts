import {
  toDetectorReportOutcome,
  type DetectorOutput,
  type DetectorReport,
  type ImmediateDetectorOutput,
} from "../domain/detectionOutcome";

export interface DetectionReportControllerOptions {
  readonly reportIntervalMs: number;
  /** 판정 창의 길이. 창 보고의 시작 시각을 관측 시각에서 되짚는 데 쓴다. */
  readonly windowMs: number;
  readonly now?: () => number;
  readonly isReportingAllowed?: () => boolean;
  /** 리포트마다 새 멱등키를 만든다. 기본값은 `crypto.randomUUID`. */
  readonly newEventId?: () => string;
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
    windowMs,
    now = Date.now,
    isReportingAllowed = () => true,
    newEventId = () => crypto.randomUUID(),
    onReport,
  } = options;
  let interval: ReturnType<typeof setInterval> | null = null;

  /**
   * 보고 한 건을 만든다. 멱등키는 보낼 것이 확정된 뒤에만 뽑는다 — 숨은 창까지 소모하면
   * 서버 기록의 ID 가 실제로 보낸 관측 수와 어긋난다.
   */
  const emit = (output: DetectorOutput, observedWindow: boolean): void => {
    if (!isReportingAllowed()) return;
    const observedAtMs = now();
    onReport({
      outcome: toDetectorReportOutcome(output),
      observedAtMs,
      // 창이 아닌 보고는 키 자체를 넣지 않는다. `undefined` 를 실으면 계약 밖 필드가 된다.
      ...(observedWindow ? { windowStartedAtMs: observedAtMs - windowMs } : {}),
      clientEventId: newEventId(),
    });
  };

  const stop = (): void => {
    if (interval === null) return;
    clearInterval(interval);
    interval = null;
  };

  return {
    reportWindow(output): void {
      emit(output, true);
    },
    startImmediate(output): void {
      stop();
      emit(output, false);
      interval = setInterval(() => emit(output, false), reportIntervalMs);
    },
    stop,
  };
}
