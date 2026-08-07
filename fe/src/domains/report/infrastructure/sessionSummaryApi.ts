/**
 * 수업 요약 조회 어댑터.
 *
 * <p>`GET /api/v1/sessions/{sessionId}/reports/summary`. 강사·학생이 **같은 값**을 받는 공통 산출물이라
 * 역할별 분기가 없다 — 화면도 두 탭에서 같은 컴포넌트를 쓴다.
 *
 * <p>응답은 수업 전체를 훑는 한 문단(`summary`)과, 같은 분석이 나눈 내용 구간(`sections`)이다. 절 구분은
 * 화면이 지어내는 것이 아니라 사후 분석이 만든 구간을 그대로 쓴다(S15P11A105-314).
 *
 * <p>요약이 아직 없으면 서버가 빈 문자열이 아니라 404 를 준다. "분석이 안 끝났다" 와 "요약이 비었다" 는
 * 화면에서 할 말이 다르기 때문이다(전자는 기다리라고, 후자는 빈 카드).
 *
 * <p>구간은 요약과 달리 **빈 배열이 정상**이다. 내용 타임라인 없이 요약만 있는 세션이 있고, 그 세션도
 * 요약은 보여야 한다.
 */

export type SessionSummarySection = {
  readonly title: string;
  readonly summary: string;
  /**
   * 반올림하지 않은 구간 시작(ms).
   *
   * <p>화면은 `startSeconds` 로 그리지만 드래그 앵커는 이 값을 그대로 서버로 되돌린다. 초로 낮춘 값을
   * 다시 ×1000 해서 보내면 200,400ms 구간이 200,000ms 가 되어 **앞 구간에 떨어진다** — 그러면 서버는
   * 옆 구간의 전사로 답하고, 답변은 여전히 유창해서 화면으로는 잡히지 않는다.
   */
  readonly startOffsetMs: number;
  readonly startSeconds: number;
  readonly endSeconds: number;
};

export type SessionSummary = {
  readonly summary: string;
  readonly sections: readonly SessionSummarySection[];
};

export class SessionSummaryError extends Error {
  /** HTTP 상태. 응답을 받지 못했으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "SessionSummaryError";
    this.status = status;
  }
}

export type SessionSummaryRequester = (
  sessionId: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<SessionSummary>;

/**
 * 봉투를 열어 `data` 객체를 꺼낸다. `isSuccess` 가 참이 아니거나 `data` 가 객체가 아니면 계약 위반이므로 던진다.
 */
const dataOf = (envelope: unknown, status: number): Record<string, unknown> => {
  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    typeof (envelope as { data?: unknown }).data !== "object" ||
    (envelope as { data?: unknown }).data === null
  ) {
    throw new SessionSummaryError("Session summary response had an invalid envelope.", status);
  }

  return (envelope as { data: Record<string, unknown> }).data;
};

const objectOf = (value: unknown): Record<string, unknown> | null =>
  typeof value === "object" && value !== null ? (value as Record<string, unknown>) : null;

const stringOf = (value: unknown): string => (typeof value === "string" ? value : "");

/** ms 를 초로. 구간 오프셋은 서버에서 NOT NULL 이라 값이 깨졌을 때만 0 이 된다. */
const secondsOf = (value: unknown): number =>
  typeof value === "number" && Number.isFinite(value) && value >= 0 ? Math.round(value / 1000) : 0;

/** ms 를 그대로. 드래그 앵커가 쓰는 값이라 여기서 반올림하면 앵커가 옆 구간으로 밀린다. */
const msOf = (value: unknown): number =>
  typeof value === "number" && Number.isFinite(value) && value >= 0 ? Math.trunc(value) : 0;

/**
 * 깨진 구간 하나만 버리고 나머지는 그린다. 목록 전체를 버리면 구간 하나가 이상하다는 이유로 수업 전체의
 * 절 구분이 사라진다.
 *
 * <p>제목과 요약이 둘 다 비면 그릴 것이 없어 버린다. 요약만 없는 구간은 제목으로도 자리를 말하므로 남긴다 —
 * 248 이 제목만 채운 세션이 있다(서버 쪽 `summary` 는 nullable).
 */
const parseSection = (value: unknown): SessionSummarySection | null => {
  const section = objectOf(value);
  if (section === null) return null;

  const title = stringOf(section.title);
  const summary = stringOf(section.summary);
  if (title.length === 0 && summary.length === 0) return null;

  return {
    title,
    summary,
    startOffsetMs: msOf(section.startedOffsetMs),
    startSeconds: secondsOf(section.startedOffsetMs),
    endSeconds: secondsOf(section.endedOffsetMs),
  };
};

export const requestSessionSummary: SessionSummaryRequester = async (
  sessionId,
  accessToken,
  signal,
) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");

  let response: Response;
  try {
    response = await fetch(
      `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/reports/summary`,
      {
        method: "GET",
        headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` },
        credentials: "include",
        signal,
      },
    );
  } catch (error) {
    // 응답을 받지 못했다. 상태 0 으로 올려 403·404 와 구분할 수 있게 한다.
    throw new SessionSummaryError(`Session summary request failed: ${String(error)}`, 0);
  }

  if (!response.ok) {
    throw new SessionSummaryError(
      `Session summary request failed with status ${response.status}.`,
      response.status,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new SessionSummaryError("Session summary response was not valid JSON.", response.status);
  }

  const data = dataOf(envelope, response.status);
  const summary = data.summary;
  // 문자열이 아니거나 빈 값이면 그릴 것이 없다. 빈 카드를 그리지 않고 계약 위반으로 다룬다.
  if (typeof summary !== "string" || summary.length === 0) {
    throw new SessionSummaryError("Session summary response had no summary.", response.status);
  }

  // 구간이 없어도 요약은 그린다. 배열이 아예 없는 응답(구간을 싣기 전 서버)도 같은 자리로 떨어진다.
  const sections = Array.isArray(data.sections)
    ? data.sections.map(parseSection).filter((section) => section !== null)
    : [];

  return { summary, sections };
};
