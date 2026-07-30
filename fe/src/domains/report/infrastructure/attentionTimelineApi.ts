/**
 * 리포트 참여도 타임라인 조회 어댑터.
 *
 * <p>230 이 서버 스펙을 확정하면 `npm run generate:types` 로 타입을 재생성해 이 수기 타입을 대체한다.
 * 그전까지는 설계 문서(docs/superpowers/specs/2026-07-30-참여도-타임라인-리포트-design.md §3)의
 * 계약을 손으로 옮겨 둔다.
 *
 * <p>비율은 0.0~1.0 분수다. `focusPercent` 만 0~100 정수이며, 단위가 다른 것은 의도된 계약이다.
 *
 * <p>`null` 은 값 없음이다. 절대 0 으로 바꾸지 않는다 — 회색 공백으로 그려야 할 구간이
 * "집중이 완벽했다"로 뒤집힌다(REPORT-S-007).
 *
 * <p>`checkNeededRatio` 의 분모는 `eligibleCount`, `cameraOffRatio` 의 분모는 `connectedCount` 다.
 * 두 값을 더하거나 비교하면 안 된다.
 */

/** 강사 카드가 그리는 익명 집단 포인트. 학생 식별자는 어떤 필드로도 오지 않는다(REPORT-I-002). */
export type GroupTimelinePoint = {
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

export type DistractedInterval = {
  readonly startSeconds: number;
  readonly endSeconds: number;
};

export type GroupAttentionTimeline = {
  readonly intervalSeconds: number;
  readonly durationSeconds: number;
  readonly points: readonly GroupTimelinePoint[];
  readonly distractedIntervals: readonly DistractedInterval[];
};

/** `CHECK_NEEDED` 는 CONFUSED·MISSED·NON_RESPONSE 를 묶은 값이다. 학생 화면은 셋을 구분하지 않는다. */
export type StudentTimelineState = "GOOD" | "CHECK_NEEDED" | "CAMERA_OFF" | "UNMEASURABLE";

export type StudentTimelinePoint = {
  readonly offsetSeconds: number;
  /** 0~100 정수. 창의 70% 를 채우지 못하면 `null` 이다. */
  readonly focusPercent: number | null;
  readonly state: StudentTimelineState | null;
};

export type StudentAttentionTimeline = {
  readonly intervalSeconds: number;
  readonly durationSeconds: number;
  readonly points: readonly StudentTimelinePoint[];
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

/**
 * 숫자여야 하는 필드가 숫자가 아니면 그 **점**만 버린다. 시계열 전체를 버리지 않는다 —
 * 점 하나가 깨졌다고 리포트를 통째로 못 보여줄 이유가 없다.
 */
const parseGroupPoint = (value: unknown): GroupTimelinePoint | null => {
  if (typeof value !== "object" || value === null) {
    return null;
  }

  const point = value as Record<string, unknown>;
  if (
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
  if (typeof value !== "object" || value === null) {
    return null;
  }

  const interval = value as Record<string, unknown>;
  if (!isFiniteNumber(interval.startSeconds) || !isFiniteNumber(interval.endSeconds)) {
    return null;
  }

  return { startSeconds: interval.startSeconds, endSeconds: interval.endSeconds };
};

/** 모르는 `state` 는 점을 버리지 않고 `null` 로 낮춘다. 점이 사라지면 시간축에 구멍이 난다. */
const parseStudentPoint = (value: unknown): StudentTimelinePoint | null => {
  if (typeof value !== "object" || value === null) {
    return null;
  }

  const point = value as Record<string, unknown>;
  if (!isFiniteNumber(point.offsetSeconds)) {
    return null;
  }

  return {
    offsetSeconds: point.offsetSeconds,
    focusPercent: isFiniteNumber(point.focusPercent) ? point.focusPercent : null,
    state: STUDENT_STATES.includes(point.state as StudentTimelineState)
      ? (point.state as StudentTimelineState)
      : null,
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
 * Bearer 헤더가 없으면 401 이다. 역할이 맞지 않으면 403 이 온다(§3.4).
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

/** 격자 간격은 5초 고정이지만(§6) 서버가 값을 내려주므로 그대로 쓴다. 없으면 계약 기본값을 쓴다. */
const DEFAULT_INTERVAL_SECONDS = 5;

const intervalOf = (value: unknown): number =>
  isFiniteNumber(value) && value > 0 ? value : DEFAULT_INTERVAL_SECONDS;

const durationOf = (value: unknown): number => (isFiniteNumber(value) && value >= 0 ? value : 0);

export const requestGroupAttentionTimeline: GroupTimelineRequester = async (
  sessionId,
  accessToken,
  signal,
) => {
  const { data, status } = await fetchTimeline(sessionId, accessToken, "group", signal);

  // 관측이 한 건도 없으면 `points: []` 다. 오류가 아니다(§3.4).
  if (!Array.isArray(data.points)) {
    throw new AttentionTimelineError("Attention timeline response had no point array.", status);
  }

  const distractedIntervals = Array.isArray(data.distractedIntervals)
    ? data.distractedIntervals
    : [];

  return {
    intervalSeconds: intervalOf(data.intervalSeconds),
    durationSeconds: durationOf(data.durationSeconds),
    points: data.points
      .map(parseGroupPoint)
      .filter((point): point is GroupTimelinePoint => point !== null),
    distractedIntervals: distractedIntervals
      .map(parseDistractedInterval)
      .filter((interval): interval is DistractedInterval => interval !== null),
  };
};

export const requestStudentAttentionTimeline: StudentTimelineRequester = async (
  sessionId,
  accessToken,
  signal,
) => {
  const { data, status } = await fetchTimeline(sessionId, accessToken, "me", signal);

  if (!Array.isArray(data.points)) {
    throw new AttentionTimelineError("Attention timeline response had no point array.", status);
  }

  return {
    intervalSeconds: intervalOf(data.intervalSeconds),
    durationSeconds: durationOf(data.durationSeconds),
    points: data.points
      .map(parseStudentPoint)
      .filter((point): point is StudentTimelinePoint => point !== null),
  };
};
