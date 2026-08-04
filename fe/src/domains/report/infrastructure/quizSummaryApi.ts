/**
 * AI 이해도 퀴즈 요약(문항 수 · 예상 풀이 시간) 조회 어댑터.
 *
 * <p>`GET /api/v1/sessions/{sessionId}/quiz`(249). 리포트 화면은 퀴즈를 풀지 않으므로 문항·보기
 * 본문은 읽지 않는다 — "몇 문제, 몇 분" 만 세어 카드에 적는다. 문항 본문까지 타입으로 옮기면
 * 리포트가 퀴즈 화면의 계약 변화에 함께 끌려다닌다.
 *
 * <p>`estimatedDurationMinutes` 는 응답에서 빠질 수 있다(`@JsonInclude(NON_NULL)`). 없으면
 * `null` 이며 0 이 아니다 — "약 0분" 은 3분 걸리는 퀴즈를 안 걸리는 것처럼 말한다.
 *
 * <p>퀴즈가 아직 없으면 404 다. 오류가 아니라 "아직 준비되지 않음" 이며 분기는 훅이 맡는다.
 */

export type QuizSummary = {
  readonly questionCount: number;
  /** 예상 풀이 시간(분). 서버가 주지 않으면 `null` 이다. */
  readonly estimatedDurationMinutes: number | null;
};

export class QuizSummaryError extends Error {
  /** HTTP 상태. 응답을 받지 못했으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "QuizSummaryError";
    this.status = status;
  }
}

export type QuizSummaryRequester = (
  sessionId: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<QuizSummary>;

const objectOf = (value: unknown): Record<string, unknown> | null =>
  typeof value === "object" && value !== null ? (value as Record<string, unknown>) : null;

export const requestQuizSummary: QuizSummaryRequester = async (sessionId, accessToken, signal) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");

  let response: Response;
  try {
    response = await fetch(`${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/quiz`, {
      method: "GET",
      headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` },
      credentials: "include",
      signal,
    });
  } catch (error) {
    // 응답을 받지 못했다. 상태 0 으로 올려 404 와 구분할 수 있게 한다.
    throw new QuizSummaryError(`Quiz summary request failed: ${String(error)}`, 0);
  }

  if (!response.ok) {
    throw new QuizSummaryError(
      `Quiz summary request failed with status ${response.status}.`,
      response.status,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new QuizSummaryError("Quiz summary response was not valid JSON.", response.status);
  }

  const wrapper = objectOf(envelope);
  const data = objectOf(wrapper?.data);
  if (wrapper?.isSuccess !== true || data === null || !Array.isArray(data.questions)) {
    throw new QuizSummaryError("Quiz summary response had an invalid envelope.", response.status);
  }

  const duration = data.estimatedDurationMinutes;

  return {
    questionCount: data.questions.length,
    estimatedDurationMinutes:
      typeof duration === "number" && Number.isFinite(duration) && duration > 0
        ? Math.trunc(duration)
        : null,
  };
};
