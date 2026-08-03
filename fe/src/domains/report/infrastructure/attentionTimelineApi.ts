/**
 * 리포트 참여도 타임라인 조회 어댑터.
 *
 * <p>230 이 서버 스펙을 확정하면 `npm run generate:types` 로 타입을 재생성해 이 수기 타입을 대체한다.
 * 그전까지는 설계 문서(docs/superpowers/specs/2026-07-30-참여도-타임라인-리포트-design.md §3,
 * 2026-08-03 개정판)의 계약을 손으로 옮겨 둔다.
 *
 * <p><b>격자가 둘이라 배열이 갈라진다.</b> 집중 흐름은 겹치지 않는 30초 구간이고, 신호(확인 필요·
 * 카메라 꺼짐·응답 분포)는 5초 간격이다. 배열마다 자기 `intervalSeconds` 를 갖는다. 한 배열에
 * 섞으면 30초 값이 5초 포인트 여섯 중 다섯에서 `null` 이 되어 "값 없음"과 구분되지 않는다.
 *
 * <p>`focusLevel` 은 **1.00~4.00 단계 평균**이다. 퍼센트가 아니며 다른 척도로 환산하지 않는다.
 * 모든 `*Ratio` 는 0.0~1.0 분수이며 표시할 때만 100 을 곱한다. 두 척도를 같은 축에 놓지 않는다.
 *
 * <p>`null` 은 값 없음이다. 절대 0 이나 1단계로 바꾸지 않는다 — 회색 공백으로 그려야 할 구간이
 * "집중이 완벽했다"로 뒤집힌다(REPORT-S-007).
 *
 * <p>`checkNeededRatio` 의 분모는 `eligibleCount`, `cameraOffRatio` 의 분모는 `connectedCount` 다.
 * 두 값을 더하거나 비교하면 안 된다.
 */

/** `CHECK_NEEDED` 는 CONFUSED·MISSED·NON_RESPONSE 를 묶은 값이다. 학생 화면은 셋을 구분하지 않는다. */
export type StudentTimelineState = "GOOD" | "CHECK_NEEDED" | "CAMERA_OFF" | "UNMEASURABLE";

/** 30초 구간 하나의 단계 평균. `focusLevel` 은 1.00~4.00 또는 값 없음이다. */
export type FocusPoint = {
  readonly offsetSeconds: number;
  readonly focusLevel: number | null;
};

/** 집단 집중 흐름 포인트. `eligibleCount` 는 그 구간에 걸친 5초 스냅샷의 최솟값이다(설계 문서 §2.10). */
export type GroupFocusPoint = FocusPoint & { readonly eligibleCount: number };

export type StudentFocusFlow = {
  readonly intervalSeconds: number;
  readonly points: readonly FocusPoint[];
};

export type GroupFocusFlow = {
  readonly intervalSeconds: number;
  readonly points: readonly GroupFocusPoint[];
};

/** 강사 카드가 그리는 익명 신호 포인트. 학생 식별자는 어떤 필드로도 오지 않는다(REPORT-I-002). */
export type GroupSignalPoint = {
  readonly offsetSeconds: number;
  readonly connectedCount: number;
  readonly eligibleCount: number;
  readonly checkNeededRatio: number | null;
  readonly cameraOffRatio: number | null;
  readonly confusedRatio: number | null;
  readonly missedRatio: number | null;
  readonly nonResponseRatio: number | null;
  readonly unmeasurableRatio: number | null;
};

export type GroupSignals = {
  readonly intervalSeconds: number;
  readonly points: readonly GroupSignalPoint[];
};

export type DistractedInterval = {
  readonly startSeconds: number;
  readonly endSeconds: number;
};

/** 서버가 인접 동일 상태를 병합해 내려주는 구간(설계 문서 §2.13). 화면은 받은 대로 그린다. */
export type StateInterval = {
  readonly startSeconds: number;
  readonly endSeconds: number;
  readonly state: StudentTimelineState;
};

/** 수업 내용이 바뀌는 지점으로 나눈 구간과 그 구간의 집중 흐름 평균. 고정 길이가 아니다(§2.12). */
export type SectionAverage = {
  readonly startSeconds: number;
  readonly endSeconds: number;
  readonly title: string;
  readonly focusLevel: number | null;
};

export type GroupAttentionTimeline = {
  readonly durationSeconds: number;
  readonly focusFlow: GroupFocusFlow;
  readonly signals: GroupSignals;
  readonly distractedIntervals: readonly DistractedInterval[];
  readonly sections: readonly SectionAverage[];
};

export type StudentAttentionTimeline = {
  readonly durationSeconds: number;
  readonly focusFlow: StudentFocusFlow;
  readonly stateIntervals: readonly StateInterval[];
  readonly sections: readonly SectionAverage[];
};

export class AttentionTimelineError extends Error {
  /** HTTP 상태. 네트워크 실패 등 응답이 없으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "AttentionTimelineError";
    this.status = status;
  }
}

export type GroupTimelineRequester = (
  sessionId: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<GroupAttentionTimeline>;

export type StudentTimelineRequester = (
  sessionId: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<StudentAttentionTimeline>;

const STUDENT_STATES: readonly StudentTimelineState[] = [
  "GOOD",
  "CHECK_NEEDED",
  "CAMERA_OFF",
  "UNMEASURABLE",
];

const isFiniteNumber = (value: unknown): value is number =>
  typeof value === "number" && Number.isFinite(value);

/**
 * 비율은 `null` 이거나 유한한 숫자다. 그 외(문자열·NaN·undefined)는 `null` 로 낮춘다.
 * 0 으로 낮추면 "관측이 없었다"가 "완벽했다"로 뒤집힌다.
 */
const ratioOf = (value: unknown): number | null => (isFiniteNumber(value) ? value : null);

const objectOf = (value: unknown): Record<string, unknown> | null =>
  typeof value === "object" && value !== null ? (value as Record<string, unknown>) : null;

/**
 * 숫자여야 하는 필드가 숫자가 아니면 그 **점**만 버린다. 시계열 전체를 버리지 않는다 —
 * 점 하나가 깨졌다고 리포트를 통째로 못 보여줄 이유가 없다.
 *
 * <p>`focusLevel` 은 값이 없을 수 있는 필드라 숫자가 아니면 `null` 로 낮춘다. 1 로 채우지 않는다.
 */
const parseFocusPoint = (value: unknown): FocusPoint | null => {
  const point = objectOf(value);
  if (point === null || !isFiniteNumber(point.offsetSeconds)) {
    return null;
  }

  return {
    offsetSeconds: point.offsetSeconds,
    focusLevel: isFiniteNumber(point.focusLevel) ? point.focusLevel : null,
  };
};

const parseGroupFocusPoint = (value: unknown): GroupFocusPoint | null => {
  const point = objectOf(value);
  if (point === null || !isFiniteNumber(point.eligibleCount)) {
    return null;
  }

  const base = parseFocusPoint(point);
  return base === null ? null : { ...base, eligibleCount: point.eligibleCount };
};

const parseSignalPoint = (value: unknown): GroupSignalPoint | null => {
  const point = objectOf(value);
  if (
    point === null ||
    !isFiniteNumber(point.offsetSeconds) ||
    !isFiniteNumber(point.connectedCount) ||
    !isFiniteNumber(point.eligibleCount)
  ) {
    return null;
  }

  return {
    offsetSeconds: point.offsetSeconds,
    connectedCount: point.connectedCount,
    eligibleCount: point.eligibleCount,
    checkNeededRatio: ratioOf(point.checkNeededRatio),
    cameraOffRatio: ratioOf(point.cameraOffRatio),
    confusedRatio: ratioOf(point.confusedRatio),
    missedRatio: ratioOf(point.missedRatio),
    nonResponseRatio: ratioOf(point.nonResponseRatio),
    unmeasurableRatio: ratioOf(point.unmeasurableRatio),
  };
};

const parseDistractedInterval = (value: unknown): DistractedInterval | null => {
  const interval = objectOf(value);
  if (
    interval === null ||
    !isFiniteNumber(interval.startSeconds) ||
    !isFiniteNumber(interval.endSeconds)
  ) {
    return null;
  }

  return { startSeconds: interval.startSeconds, endSeconds: interval.endSeconds };
};

/**
 * 모르는 `state` 는 구간을 버린다. 상태가 없는 상태 구간은 그릴 수 없다.
 *
 * <p>옛 포인트 계약에서는 점을 살리고 `state` 만 `null` 로 낮췄지만, 구간에는 그럴 자리가 없다.
 */
const parseStateInterval = (value: unknown): StateInterval | null => {
  const interval = objectOf(value);
  if (
    interval === null ||
    !isFiniteNumber(interval.startSeconds) ||
    !isFiniteNumber(interval.endSeconds) ||
    !STUDENT_STATES.includes(interval.state as StudentTimelineState)
  ) {
    return null;
  }

  return {
    startSeconds: interval.startSeconds,
    endSeconds: interval.endSeconds,
    state: interval.state as StudentTimelineState,
  };
};

/** `title` 은 248 이 채운 값이다. 없으면 빈 문자열로 두고 화면이 정한다. */
const parseSection = (value: unknown): SectionAverage | null => {
  const section = objectOf(value);
  if (
    section === null ||
    !isFiniteNumber(section.startSeconds) ||
    !isFiniteNumber(section.endSeconds)
  ) {
    return null;
  }

  return {
    startSeconds: section.startSeconds,
    endSeconds: section.endSeconds,
    title: typeof section.title === "string" ? section.title : "",
    focusLevel: isFiniteNumber(section.focusLevel) ? section.focusLevel : null,
  };
};

/**
 * 봉투를 열어 `data` 객체를 꺼낸다. `isSuccess` 가 참이 아니거나 `data` 가 객체가 아니면
 * 계약 위반이므로 던진다.
 */
const dataOf = (envelope: unknown, status: number): Record<string, unknown> => {
  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    typeof (envelope as { data?: unknown }).data !== "object" ||
    (envelope as { data?: unknown }).data === null
  ) {
    throw new AttentionTimelineError("Attention timeline response had an invalid envelope.", status);
  }

  return (envelope as { data: Record<string, unknown> }).data;
};

/**
 * 서버는 Access Token 으로 요청자가 이 세션의 강사인지 학생인지 판단한다. 쿠키는 refresh 전용이라
 * Bearer 헤더가 없으면 401 이다. 역할이 맞지 않으면 403 이 온다(§3.5).
 */
const fetchTimeline = async (
  sessionId: string,
  accessToken: string,
  path: "group" | "me",
  signal?: AbortSignal,
): Promise<{ data: Record<string, unknown>; status: number }> => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");

  let response: Response;
  try {
    response = await fetch(
      `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/reports/attention/${path}`,
      {
        method: "GET",
        headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` },
        credentials: "include",
        signal,
      },
    );
  } catch (error) {
    // 응답을 받지 못했다. 상태 0 으로 올려 403·409 와 구분할 수 있게 한다.
    throw new AttentionTimelineError(`Attention timeline request failed: ${String(error)}`, 0);
  }

  if (!response.ok) {
    throw new AttentionTimelineError(
      `Attention timeline request failed with status ${response.status}.`,
      response.status,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new AttentionTimelineError(
      "Attention timeline response was not valid JSON.",
      response.status,
    );
  }

  return { data: dataOf(envelope, response.status), status: response.status };
};

/** 격자마다 기본 간격이 다르다. 서버가 값을 주지만 없을 때를 대비한 계약 기본값이다. */
const DEFAULT_FOCUS_INTERVAL_SECONDS = 30;
const DEFAULT_SIGNAL_INTERVAL_SECONDS = 5;

const intervalOf = (value: unknown, fallback: number): number =>
  isFiniteNumber(value) && value > 0 ? value : fallback;

const durationOf = (value: unknown): number => (isFiniteNumber(value) && value >= 0 ? value : 0);

const arrayOf = (value: unknown): unknown[] => (Array.isArray(value) ? value : []);

/**
 * 격자 하나를 꺼낸다. 배열이 없으면 그래프의 본체가 없다는 뜻이라 던진다 —
 * 관측이 한 건도 없는 세션은 `points: []` 로 오지, 키 자체가 빠지지 않는다(§3.5).
 */
const gridOf = (value: unknown, status: number, name: string): Record<string, unknown> => {
  const grid = objectOf(value);
  if (grid === null || !Array.isArray(grid.points)) {
    throw new AttentionTimelineError(
      `Attention timeline response had no ${name} point array.`,
      status,
    );
  }

  return grid;
};

export const requestGroupAttentionTimeline: GroupTimelineRequester = async (
  sessionId,
  accessToken,
  signal,
) => {
  const { data, status } = await fetchTimeline(sessionId, accessToken, "group", signal);

  const focusFlow = gridOf(data.focusFlow, status, "focusFlow");
  const signals = gridOf(data.signals, status, "signals");

  return {
    durationSeconds: durationOf(data.durationSeconds),
    focusFlow: {
      intervalSeconds: intervalOf(focusFlow.intervalSeconds, DEFAULT_FOCUS_INTERVAL_SECONDS),
      points: (focusFlow.points as unknown[])
        .map(parseGroupFocusPoint)
        .filter((point): point is GroupFocusPoint => point !== null),
    },
    signals: {
      intervalSeconds: intervalOf(signals.intervalSeconds, DEFAULT_SIGNAL_INTERVAL_SECONDS),
      points: (signals.points as unknown[])
        .map(parseSignalPoint)
        .filter((point): point is GroupSignalPoint => point !== null),
    },
    distractedIntervals: arrayOf(data.distractedIntervals)
      .map(parseDistractedInterval)
      .filter((interval): interval is DistractedInterval => interval !== null),
    sections: arrayOf(data.sections)
      .map(parseSection)
      .filter((section): section is SectionAverage => section !== null),
  };
};

export const requestStudentAttentionTimeline: StudentTimelineRequester = async (
  sessionId,
  accessToken,
  signal,
) => {
  const { data, status } = await fetchTimeline(sessionId, accessToken, "me", signal);

  const focusFlow = gridOf(data.focusFlow, status, "focusFlow");

  return {
    durationSeconds: durationOf(data.durationSeconds),
    focusFlow: {
      intervalSeconds: intervalOf(focusFlow.intervalSeconds, DEFAULT_FOCUS_INTERVAL_SECONDS),
      points: (focusFlow.points as unknown[])
        .map(parseFocusPoint)
        .filter((point): point is FocusPoint => point !== null),
    },
    stateIntervals: arrayOf(data.stateIntervals)
      .map(parseStateInterval)
      .filter((interval): interval is StateInterval => interval !== null),
    sections: arrayOf(data.sections)
      .map(parseSection)
      .filter((section): section is SectionAverage => section !== null),
  };
};
