import { canonicalInviteCode } from "@/domains/lecture/domain/inviteCode";
import type { JoinFailureReason } from "@/domains/lecture/domain/joinFailure";

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

export type JoinableSession = {
  /** TSID 라 JS 안전 정수 범위를 넘는다. 문자열로만 다뤄야 값이 깨지지 않는다. */
  sessionId: string;
  inviteCode: string;
  status: string;
  /** 확인한 순간의 남은 자리. 실제 입장까지 줄어들 수 있다. */
  remainingSeats: number;
};

export type JoinableChecker = (
  inviteCode: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<JoinableSession>;

const isJoinableSession = (value: unknown): value is JoinableSession => {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const result = value as Record<string, unknown>;
  return (
    idOf(result.sessionId) !== null &&
    typeof result.inviteCode === "string" &&
    typeof result.status === "string" &&
    typeof result.remainingSeats === "number"
  );
};

/**
 * 초대 코드로 들어갈 수 있는지만 확인한다(POST /api/v1/sessions/joinable). **참가자를 만들지 않는다.**
 *
 * <p>입장 전 점검으로 넘어가기 전에 쓴다. 이 확인을 입장 API 로 대신하면 참가자 행이 먼저 생겨 정원을 선점한다 — 학생이 장치 점검에서 이탈해도 자리가 돌아오지 않는다.
 *
 * <p>실패는 입장과 같은 오류 타입·코드로 올라오므로 문구 매핑을 공유한다.
 */
export const checkJoinable: JoinableChecker = async (inviteCode, accessToken, signal) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(`${apiBaseUrl}/api/v1/sessions/joinable`, {
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
      `Joinable check failed with status ${response.status}.`,
      response.status,
      await errorCodeOf(response),
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new JoinSessionRequestError(
      "Joinable check response was not valid JSON.",
      response.status,
    );
  }

  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    !isJoinableSession((envelope as { data?: unknown }).data)
  ) {
    throw new JoinSessionRequestError(
      "Joinable check response had an invalid envelope.",
      response.status,
    );
  }

  const data = (envelope as { data: JoinableSession }).data;
  return { ...data, sessionId: String(data.sessionId) };
};

/** 백엔드 {@code SessionApplicationErrorCode} 의 값. 여기를 고칠 때는 그쪽과 대조해야 한다. */
const SESSION_NOT_STARTED_CODE = "SESSION_APP_007";
const SESSION_ENDED_CODE = "SESSION_APP_008";
const SESSION_FULL_CODE = "SESSION_APP_009";

/**
 * 입장 실패를 도메인이 이해하는 이유로 옮긴다.
 *
 * <p>업무 코드를 HTTP 상태보다 먼저 본다. 같은 409 안에 시작 전·종료·정원 초과가 함께 들어 있어 상태만으로는 구분되지 않는다.
 *
 * <p>이 번역이 infrastructure 에 있는 이유: 상태 코드와 업무 코드는 이 어댑터가 서버와 주고받는 약속이다. 도메인은 그 약속을 몰라야 전송 방식이 바뀔 때 문구가 함께 흔들리지 않는다.
 */
export const joinFailureReasonOf = (error: unknown): JoinFailureReason => {
  if (!(error instanceof JoinSessionRequestError)) {
    return "UNKNOWN";
  }

  switch (error.code) {
    case SESSION_NOT_STARTED_CODE:
      return "NOT_STARTED";
    case SESSION_ENDED_CODE:
      return "ENDED";
    case SESSION_FULL_CODE:
      return "FULL";
    default:
      break;
  }

  switch (error.status) {
    case 404:
      return "NOT_FOUND";
    case 400:
      return "INVALID_CODE";
    case 401:
      return "SIGNED_OUT";
    default:
      return "UNKNOWN";
  }
};
