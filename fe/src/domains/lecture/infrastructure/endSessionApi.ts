export type EndSessionResult = {
  sessionId: number;
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

const isEndSessionResult = (value: unknown): value is EndSessionResult => {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const result = value as Record<string, unknown>;
  return (
    typeof result.sessionId === "number" &&
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

  return (envelope as { data: EndSessionResult }).data;
};
