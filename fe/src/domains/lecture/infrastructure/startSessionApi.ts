export type StartedSession = {
  /** TSID 라 JS 안전 정수 범위를 넘는다. 문자열로만 다뤄야 값이 깨지지 않는다. */
  sessionId: string;
  /** 시작 후 상태. 정상이면 `LIVE`. */
  status: string;
  /** 이 시점부터 학생 입장에 쓸 수 있는 초대 코드. */
  inviteCode: string;
  /** 자동 종료 예정 시각(시작 + 3시간). */
  expiresAt: string | null;
  /** 이번 요청으로 시작되었으면 true, 이미 진행 중이었으면 false. */
  started: boolean;
};

export class StartSessionRequestError extends Error {
  /** HTTP 상태. 응답을 받지 못했으면 0. */
  readonly status: number;
  /** 서버가 돌려준 업무 오류 코드. 없으면 null. */
  readonly code: string | null;

  constructor(message: string, status: number, code: string | null = null) {
    super(message);
    this.name = "StartSessionRequestError";
    this.status = status;
    this.code = code;
  }
}

export type SessionStarter = (
  sessionId: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<StartedSession>;

/**
 * 서버가 TSID 를 숫자로 내보내는 구성도 있어 둘 다 받는다. 숫자로 파싱된 값은 JS 안전 정수 범위를 넘겨
 * 이미 반올림돼 있을 수 있으므로, 문자열로 오는 편이 정확하다(서버 쪽 개선 대상).
 */
const idOf = (value: unknown): string | null => {
  if (typeof value === "string" && value.length > 0) return value;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return null;
};

const isStartedSession = (value: unknown): value is StartedSession => {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const result = value as Record<string, unknown>;
  return (
    idOf(result.sessionId) !== null &&
    typeof result.status === "string" &&
    typeof result.inviteCode === "string" &&
    typeof result.started === "boolean"
  );
};

const errorCodeOf = async (response: Response): Promise<string | null> => {
  try {
    const envelope: unknown = await response.json();
    if (typeof envelope === "object" && envelope !== null) {
      const code = (envelope as { code?: unknown }).code;
      return typeof code === "string" ? code : null;
    }
  } catch {
    // 본문이 JSON 이 아니면 코드 없이 상태만으로 판단한다.
  }
  return null;
};

/**
 * 준비된 수업을 실제로 시작한다(POST /api/v1/sessions/{sessionId}/start).
 *
 * <p>이 호출 전까지 초대 코드는 유효하지 않아 학생이 입장할 수 없다. 멱등하므로 두 번 눌러도 시작 시각이 밀리지 않는다.
 *
 * <p>서버가 준비 단계를 두지 않는 구성(생성 즉시 `LIVE`)에서는 이 API 가 없을 수 있다. 호출자는 생성 응답의 상태를 보고 필요할 때만 호출한다.
 */
export const startSession: SessionStarter = async (sessionId, accessToken, signal) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(
    `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/start`,
    {
      method: "POST",
      headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` },
      credentials: "include",
      signal,
    },
  );

  if (!response.ok) {
    throw new StartSessionRequestError(
      `Start session failed with status ${response.status}.`,
      response.status,
      await errorCodeOf(response),
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new StartSessionRequestError(
      "Start session response was not valid JSON.",
      response.status,
    );
  }

  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    !isStartedSession((envelope as { data?: unknown }).data)
  ) {
    throw new StartSessionRequestError(
      "Start session response had an invalid envelope.",
      response.status,
    );
  }

  const data = (envelope as { data: StartedSession }).data;
  return { ...data, sessionId: String(data.sessionId), expiresAt: data.expiresAt ?? null };
};
