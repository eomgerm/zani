export type EndSessionResult = {
  /** TSID 라 JS 안전 정수 범위를 넘는다. 문자열로만 다뤄야 값이 깨지지 않는다. */
  sessionId: string;
  /** 종료 후 세션 상태. */
  status: string;
  /** 이번 요청으로 종료되었는지. 이미 종료된 세션이면 false. */
  ended: boolean;
};

export class EndSessionRequestError extends Error {
  /** HTTP 상태. 응답을 받지 못했으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "EndSessionRequestError";
    this.status = status;
  }
}

export type SessionEnder = (
  sessionId: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<EndSessionResult>;

/**
 * 서버가 TSID 를 숫자로 내보내는 구성도 있어 둘 다 받는다. 숫자로 파싱된 값은 JS 안전 정수 범위를 넘겨
 * 이미 반올림돼 있을 수 있으므로, 문자열로 오는 편이 정확하다(서버 쪽 개선 대상).
 */
const idOf = (value: unknown): string | null => {
  if (typeof value === "string" && value.length > 0) return value;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return null;
};

const isEndSessionResult = (value: unknown): value is EndSessionResult => {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const result = value as Record<string, unknown>;
  return (
    idOf(result.sessionId) !== null &&
    typeof result.status === "string" &&
    typeof result.ended === "boolean"
  );
};

/**
 * 강사가 수업을 종료한다(POST /api/v1/sessions/{sessionId}/end).
 * 서버는 Access Token 의 사용자가 이 수업을 연 강사인지 확인하므로, 화면의 역할 표시와 무관하게 권한은 서버가 최종 판단한다.
 * 토큰은 인증 컨텍스트(useAuth)가 메모리에만 들고 있는 값을 그대로 전달받는다(저장·로그 금지).
 */
export const endSession: SessionEnder = async (sessionId, accessToken, signal) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(
    `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/end`,
    {
      method: "POST",
      headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` },
      credentials: "include",
      signal,
    },
  );

  if (!response.ok) {
    throw new EndSessionRequestError(
      `End session failed with status ${response.status}.`,
      response.status,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new EndSessionRequestError("End session response was not valid JSON.", response.status);
  }

  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    !isEndSessionResult((envelope as { data?: unknown }).data)
  ) {
    throw new EndSessionRequestError(
      "End session response had an invalid envelope.",
      response.status,
    );
  }

  const data = (envelope as { data: EndSessionResult }).data;
  return { ...data, sessionId: String(data.sessionId) };
};
