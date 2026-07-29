export type SessionSummary = {
  /** TSID 라 JS 안전 정수 범위를 넘는다. 문자열로만 다뤄야 값이 깨지지 않는다. */
  sessionId: string;
  inviteCode: string;
  /** `PREPARING`, `LIVE`, `ENDING`, `NOTE_PENDING`, `ENDED` 중 하나. 서버 구현에 따라 앞의 두 개만 올 수도 있다. */
  status: string;
  /** 이 수업에서 내 역할. `INSTRUCTOR` 또는 `STUDENT`. */
  role: string;
};

export class SessionListRequestError extends Error {
  /** HTTP 상태. 응답을 받지 못했으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "SessionListRequestError";
    this.status = status;
  }
}

export type SessionListRequester = (
  accessToken: string,
  signal?: AbortSignal,
) => Promise<SessionSummary[]>;

/**
 * 서버가 TSID 를 숫자로 내보내는 구성도 있어 둘 다 받는다. 숫자로 파싱된 값은 JS 안전 정수 범위를 넘겨
 * 이미 반올림돼 있을 수 있으므로, 문자열로 오는 편이 정확하다(서버 쪽 개선 대상).
 */
const idOf = (value: unknown): string | null => {
  if (typeof value === "string" && value.length > 0) return value;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return null;
};

const isSessionSummary = (value: unknown): value is SessionSummary => {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const summary = value as Record<string, unknown>;
  return (
    idOf(summary.sessionId) !== null &&
    typeof summary.inviteCode === "string" &&
    typeof summary.status === "string" &&
    typeof summary.role === "string"
  );
};

/**
 * 내가 참여한 수업 목록을 가져온다(GET /api/v1/sessions).
 *
 * <p>응답에는 제목·시작 시각이 없다. 그래서 "진행 중인 수업으로 돌아가기"처럼 식별자만 필요한 용도에는 쓸 수 있지만, 제목을 보여주는 목록 화면에는 서버가 필드를 추가해야 한다.
 */
export const requestSessionList: SessionListRequester = async (accessToken, signal) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(`${apiBaseUrl}/api/v1/sessions`, {
    method: "GET",
    headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` },
    credentials: "include",
    signal,
  });

  if (!response.ok) {
    throw new SessionListRequestError(
      `Session list request failed with status ${response.status}.`,
      response.status,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new SessionListRequestError(
      "Session list response was not valid JSON.",
      response.status,
    );
  }

  const data =
    typeof envelope === "object" &&
    envelope !== null &&
    (envelope as { isSuccess?: unknown }).isSuccess === true
      ? (envelope as { data?: unknown }).data
      : undefined;

  if (!Array.isArray(data) || !data.every(isSessionSummary)) {
    throw new SessionListRequestError(
      "Session list response had an invalid envelope.",
      response.status,
    );
  }

  // 숫자로 온 ID 도 여기서 문자열로 통일해, 화면·라우팅이 한 가지 형태만 다루게 한다.
  return data.map((summary) => ({ ...summary, sessionId: String(summary.sessionId) }));
};
