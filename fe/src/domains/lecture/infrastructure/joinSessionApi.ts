import { canonicalInviteCode } from "@/domains/lecture/domain/inviteCode";

export type JoinedSession = {
  /** TSID 라 JS 안전 정수 범위를 넘는다. 문자열로만 다뤄야 값이 깨지지 않는다. */
  sessionId: string;
  inviteCode: string;
  /** 입장한 세션의 현재 상태. */
  status: string;
  role: string;
};

export class JoinSessionRequestError extends Error {
  /** HTTP 상태. 응답을 받지 못했으면 0. */
  readonly status: number;
  /** 서버가 돌려준 업무 오류 코드(예: SESSION_APP_007). 없으면 null. */
  readonly code: string | null;

  constructor(message: string, status: number, code: string | null = null) {
    super(message);
    this.name = "JoinSessionRequestError";
    this.status = status;
    this.code = code;
  }
}

export type SessionJoiner = (
  inviteCode: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<JoinedSession>;

/**
 * 서버가 TSID 를 숫자로 내보내는 구성도 있어 둘 다 받는다. 숫자로 파싱된 값은 JS 안전 정수 범위를 넘겨
 * 이미 반올림돼 있을 수 있으므로, 문자열로 오는 편이 정확하다(서버 쪽 개선 대상).
 */
const idOf = (value: unknown): string | null => {
  if (typeof value === "string" && value.length > 0) return value;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return null;
};

const isJoinedSession = (value: unknown): value is JoinedSession => {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const result = value as Record<string, unknown>;
  return (
    idOf(result.sessionId) !== null &&
    typeof result.inviteCode === "string" &&
    typeof result.status === "string" &&
    typeof result.role === "string"
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
 * 학생이 초대 코드로 수업에 들어간다(POST /api/v1/sessions/join).
 *
 * <p>이 호출의 결과로 얻는 것은 강의실 화면에 들어갈 자격과 **세션 ID** 다. 초대 코드와 세션 ID 는 다른 값이므로, 이후 라우팅과 미디어 토큰 발급에는 반드시 응답의
 * `sessionId` 를 써야 한다.
 *
 * <p>같은 코드로 여러 번 호출해도 안전하다(서버가 멱등하게 기존 참가 관계를 돌려준다).
 */
export const joinSession: SessionJoiner = async (inviteCode, accessToken, signal) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(`${apiBaseUrl}/api/v1/sessions/join`, {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      Authorization: `Bearer ${accessToken}`,
    },
    credentials: "include",
    body: JSON.stringify({ inviteCode: canonicalInviteCode(inviteCode) }),
    signal,
  });

  if (!response.ok) {
    throw new JoinSessionRequestError(
      `Join session failed with status ${response.status}.`,
      response.status,
      await errorCodeOf(response),
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new JoinSessionRequestError(
      "Join session response was not valid JSON.",
      response.status,
    );
  }

  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    !isJoinedSession((envelope as { data?: unknown }).data)
  ) {
    throw new JoinSessionRequestError(
      "Join session response had an invalid envelope.",
      response.status,
    );
  }

  const data = (envelope as { data: JoinedSession }).data;
  return { ...data, sessionId: String(data.sessionId) };
};
