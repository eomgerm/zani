/**
 * 수업 요약 조회 어댑터.
 *
 * <p>`GET /api/v1/sessions/{sessionId}/reports/summary`. 강사·학생이 **같은 값**을 받는 공통 산출물이라
 * 역할별 분기가 없다 — 화면도 두 탭에서 같은 컴포넌트를 쓴다.
 *
 * <p>응답은 한 문단(`summary`)이다. 프로토타입은 제목+본문 5절 구조였지만 서버가 만드는 것은 문단
 * 하나이므로, 없는 절을 화면이 지어내지 않는다.
 *
 * <p>요약이 아직 없으면 서버가 빈 문자열이 아니라 404 를 준다. "분석이 안 끝났다" 와 "요약이 비었다" 는
 * 화면에서 할 말이 다르기 때문이다(전자는 기다리라고, 후자는 빈 카드).
 */

export type SessionSummary = {
  readonly summary: string;
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

  const summary = dataOf(envelope, response.status).summary;
  // 문자열이 아니거나 빈 값이면 그릴 것이 없다. 빈 카드를 그리지 않고 계약 위반으로 다룬다.
  if (typeof summary !== "string" || summary.length === 0) {
    throw new SessionSummaryError("Session summary response had no summary.", response.status);
  }

  return { summary };
};
