export type CreatedSession = {
  /** TSID 라 JS 안전 정수 범위를 넘는다. 문자열로만 다뤄야 값이 깨지지 않는다. */
  sessionId: string;
  /** 학생에게 공유할 8자리 초대 코드. 생성 응답으로만 알 수 있다. */
  inviteCode: string;
  /** 생성 직후 세션 상태. 서버 구현에 따라 `PREPARING`(시작 대기) 또는 `LIVE`(즉시 시작)다. */
  status: string;
  role: string;
  /**
   * 최대 수업 시간(3시간)에 도달해 자동 종료될 시각.
   *
   * <p>아직 시작되지 않은 세션에는 없으므로 null 이 올 수 있다. 시작 시점에 정해진다.
   */
  expiresAt: string | null;
};

export class CreateSessionRequestError extends Error {
  /** HTTP 상태. 응답을 받지 못했으면 0. */
  readonly status: number;
  /** 서버가 돌려준 업무 오류 코드(예: SESSION_APP_001). 없으면 null. */
  readonly code: string | null;

  constructor(message: string, status: number, code: string | null = null) {
    super(message);
    this.name = "CreateSessionRequestError";
    this.status = status;
    this.code = code;
  }
}

export type SessionCreator = (
  title: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<CreatedSession>;

/**
 * 서버가 TSID 를 숫자로 내보내는 구성도 있어 둘 다 받는다. 숫자로 파싱된 값은 JS 안전 정수 범위를 넘겨
 * 이미 반올림돼 있을 수 있으므로, 문자열로 오는 편이 정확하다(서버 쪽 개선 대상).
 */
const idOf = (value: unknown): string | null => {
  if (typeof value === "string" && value.length > 0) return value;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return null;
};

const isCreatedSession = (value: unknown): value is CreatedSession => {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const result = value as Record<string, unknown>;
  return (
    idOf(result.sessionId) !== null &&
    typeof result.inviteCode === "string" &&
    result.inviteCode.length > 0 &&
    typeof result.status === "string" &&
    typeof result.role === "string" &&
    (result.expiresAt === null ||
      result.expiresAt === undefined ||
      typeof result.expiresAt === "string")
  );
};

/** 오류 응답 본문에서 업무 코드만 꺼낸다. 화면이 안내 문구를 고르는 근거로 쓴다. */
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
 * 강사가 수업방을 만든다(POST /api/v1/sessions).
 *
 * <p>초대 코드는 이 응답으로만 알 수 있다. 서버가 생성하므로 화면이 미리 만들어 보여줄 수 없다.
 *
 * <p>토큰은 인증 컨텍스트(useAuth)가 메모리에만 들고 있는 값을 그대로 전달받는다(저장·로그 금지).
 */
export const createSession: SessionCreator = async (title, accessToken, signal) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(`${apiBaseUrl}/api/v1/sessions`, {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      Authorization: `Bearer ${accessToken}`,
    },
    credentials: "include",
    body: JSON.stringify({ title }),
    signal,
  });

  if (!response.ok) {
    throw new CreateSessionRequestError(
      `Create session failed with status ${response.status}.`,
      response.status,
      await errorCodeOf(response),
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new CreateSessionRequestError(
      "Create session response was not valid JSON.",
      response.status,
    );
  }

  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    !isCreatedSession((envelope as { data?: unknown }).data)
  ) {
    throw new CreateSessionRequestError(
      "Create session response had an invalid envelope.",
      response.status,
    );
  }

  const data = (envelope as { data: CreatedSession }).data;
  return { ...data, sessionId: String(data.sessionId), expiresAt: data.expiresAt ?? null };
};
