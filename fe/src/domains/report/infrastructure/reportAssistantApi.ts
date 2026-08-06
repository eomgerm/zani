/**
 * 수업 요약 질의응답 어댑터.
 *
 * <p>`POST /api/v1/sessions/{sessionId}/reports/assistant/messages`. 드래그한 지점의 시각(`anchorStartMs`)을
 * 보내면 서버가 그 구간의 요약과 앞뒤 한 구간의 전사, 전체 목차를 근거로 답한다.
 *
 * <p>`selectedText` 는 **표시용이며 근거가 아니다.** 서버는 자기 DB 로만 근거를 만든다 — 여기서 보낸 문장을
 * 근거로 쓰면 임의의 텍스트를 붙여 넣어 수업 밖 답변을 끌어낼 수 있다.
 *
 * <p>서버가 대화를 저장하지 않으므로 후속 질문은 화면이 들고 있는 최근 6턴을 함께 보낸다. 저장하지 않는 이유는
 * `question` 자체가 자유 입력이라 이력만 서버에 옮겨도 같은 통로가 열려 있고, 대신 학생 질문을 적재하지 않아
 * 보존 기간과 강사 노출 여부를 정할 일이 없기 때문이다.
 *
 * <p>인용의 `quote` 는 모델이 쓴 문장이 아니라 서버가 전사에서 채운 실제 발화다. 그래서 화면은 이 문장을 그대로
 * 믿고 그려도 된다.
 */

export type ReportAnswerCitation = {
  /** 발화 시작 시각(ms). 누르면 영상이 이 자리로 이동한다. */
  readonly offsetMs: number;
  readonly quote: string;
};

export type ReportAnswer = {
  readonly answer: string;
  readonly citations: readonly ReportAnswerCitation[];
  /** false 면 "이 수업에서 다루지 않았어요" 경로다. 지어낸 답 대신 모른다고 말한 것이다. */
  readonly grounded: boolean;
};

export type ReportAssistantTurn = {
  readonly role: "user" | "assistant";
  readonly content: string;
};

/** 질문 간격 제한 — 429. 잠시 뒤 다시 시도하면 통과한다. */
export const ASSISTANT_RATE_LIMITED = "REPORT_ASSISTANT_001";
/** 답변을 만들지 못함 — 503. 다시 눌러 볼 가치가 있다. */
export const ASSISTANT_ANSWER_UNAVAILABLE = "REPORT_ASSISTANT_002";
/** 질문과 근거가 게이트웨이 본문 상한을 넘음 — 400. 다시 눌러도 같으므로 질문을 줄여야 한다. */
export const ASSISTANT_QUESTION_TOO_LARGE = "REPORT_ASSISTANT_003";
/** 사후 분석이 아직 내용 구간을 만들지 않음 — 404. 요약 카드와 같은 코드다. */
export const ASSISTANT_REPORT_NOT_READY = "REPORT_002";

export class ReportAssistantError extends Error {
  /** HTTP 상태. 응답을 받지 못했으면 0. */
  readonly status: number;
  /** 서버 에러 코드(ApiResponse.code). 같은 400 이라도 원인이 갈리므로 코드로 구분한다. */
  readonly code: string | null;

  constructor(message: string, status: number, code: string | null = null) {
    super(message);
    this.name = "ReportAssistantError";
    this.status = status;
    this.code = code;
  }
}

export type ReportQuestionAsker = (
  sessionId: string,
  question: {
    readonly question: string;
    readonly selectedText?: string;
    readonly anchorStartMs?: number | null;
    readonly history?: readonly ReportAssistantTurn[];
  },
  accessToken: string,
  signal?: AbortSignal,
) => Promise<ReportAnswer>;

const apiBaseUrl = () => (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");

/** 깨진 인용 하나만 버리고 나머지는 그린다. 이동 버튼 하나가 없는 편이 답변을 통째로 잃는 것보다 낫다. */
const parseCitation = (value: unknown): ReportAnswerCitation | null => {
  if (typeof value !== "object" || value === null) return null;
  const citation = value as Record<string, unknown>;
  const offsetMs = citation.offsetMs;
  const quote = citation.quote;
  if (typeof offsetMs !== "number" || !Number.isFinite(offsetMs) || offsetMs < 0) return null;
  if (typeof quote !== "string" || quote.length === 0) return null;
  return { offsetMs, quote };
};

export const askReportQuestion: ReportQuestionAsker = async (
  sessionId,
  question,
  accessToken,
  signal,
) => {
  // 계약에 있는 값만 담는다. 서버가 계약 밖 필드에 400 을 낸다.
  const body = {
    question: question.question,
    ...(question.selectedText === undefined ? {} : { selectedText: question.selectedText }),
    ...(question.anchorStartMs === undefined || question.anchorStartMs === null
      ? {}
      : { anchorStartMs: question.anchorStartMs }),
    ...(question.history === undefined ? {} : { history: question.history }),
  };

  let response: Response;
  try {
    response = await fetch(
      `${apiBaseUrl()}/api/v1/sessions/${encodeURIComponent(sessionId)}/reports/assistant/messages`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Accept: "application/json",
          Authorization: `Bearer ${accessToken}`,
        },
        credentials: "include",
        body: JSON.stringify(body),
        signal,
      },
    );
  } catch (error) {
    // 응답을 받지 못했다. 상태 0 으로 올려 429·503 과 구분할 수 있게 한다.
    throw new ReportAssistantError(`Report question failed: ${String(error)}`, 0);
  }

  if (!response.ok) {
    let code: string | null = null;
    try {
      const failure: unknown = await response.json();
      if (
        typeof failure === "object" &&
        failure !== null &&
        typeof (failure as { code?: unknown }).code === "string"
      ) {
        code = (failure as { code: string }).code;
      }
    } catch {
      // 실패 본문이 JSON 이 아니면 상태 코드만으로 처리한다.
    }
    throw new ReportAssistantError(
      `Report question failed with status ${response.status}.`,
      response.status,
      code,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new ReportAssistantError("Report answer was not valid JSON.", response.status);
  }

  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    typeof (envelope as { data?: unknown }).data !== "object" ||
    (envelope as { data?: unknown }).data === null
  ) {
    throw new ReportAssistantError("Report answer had an invalid envelope.", response.status);
  }

  const data = (envelope as { data: Record<string, unknown> }).data;
  const answer = data.answer;
  // 답변이 없으면 그릴 것이 없다. 빈 말풍선을 띄우지 않고 계약 위반으로 다룬다.
  if (typeof answer !== "string" || answer.length === 0) {
    throw new ReportAssistantError("Report answer had no answer text.", response.status);
  }

  return {
    answer,
    citations: Array.isArray(data.citations)
      ? data.citations.map(parseCitation).filter((citation) => citation !== null)
      : [],
    // 값이 없으면 근거 있음으로 보지 않는다. 지어낸 답을 근거 있는 답처럼 그리는 쪽이 더 나쁘다.
    grounded: data.grounded === true,
  };
};
