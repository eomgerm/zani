/** 참가자가 서버에 보고하는 연결 상태(백엔드 ConnectionState 와 1:1). */
export type PresenceConnectionState = "CONNECTED" | "RECONNECTING" | "DISCONNECTED";

/** heartbeat 응답으로 받는 재연결·세션 신호(백엔드 ReconnectStatus 와 1:1). */
export type PresenceReconnectStatus =
  | "CONNECTED"
  | "RECONNECTED"
  | "GRACE_PERIOD"
  | "DISCONNECTED"
  | "SESSION_ENDED";

export type PresenceHeartbeat = {
  /** 클라이언트가 heartbeat를 만든 시각(UTC ISO-8601). */
  heartbeatAt: string;
  connectionState: PresenceConnectionState;
};

export type PresenceSnapshot = {
  reconnectStatus: PresenceReconnectStatus;
  /** 강사 미복귀로 세션이 종료되었는지. */
  sessionEnded: boolean;
};

export class PresenceReportError extends Error {
  /** HTTP 상태. 네트워크 실패 등 응답이 없으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "PresenceReportError";
    this.status = status;
  }
}

export type PresenceReporter = (
  sessionId: string,
  heartbeat: PresenceHeartbeat,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<PresenceSnapshot>;

const RECONNECT_STATUSES: readonly PresenceReconnectStatus[] = [
  "CONNECTED",
  "RECONNECTED",
  "GRACE_PERIOD",
  "DISCONNECTED",
  "SESSION_ENDED",
];

const isPresenceSnapshot = (value: unknown): value is PresenceSnapshot => {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const snapshot = value as Record<string, unknown>;
  return (
    RECONNECT_STATUSES.includes(snapshot.reconnectStatus as PresenceReconnectStatus) &&
    typeof snapshot.sessionEnded === "boolean"
  );
};

/**
 * presence heartbeat를 서버에 보고한다(POST /api/v1/sessions/{sessionId}/presence).
 *
 * <p>서버는 Bearer Access Token 으로 사용자를 판별한다. 쿠키에는 refresh 토큰만 있고 그마저 {@code /auth/refresh} 경로 전용이라, 헤더를 빼면 무조건 401 이
 * 되어 강사 이탈 감지가 동작하지 않는다.
 *
 * <p>실패는 상태 코드를 담은 오류로 올려 호출부가 중단·재시도를 판단하게 한다.
 */
export const reportPresence: PresenceReporter = async (
  sessionId,
  heartbeat,
  accessToken,
  signal,
) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  const response = await fetch(
    `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/presence`,
    {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        Authorization: `Bearer ${accessToken}`,
      },
      credentials: "include",
      body: JSON.stringify(heartbeat),
      signal,
    },
  );

  if (!response.ok) {
    throw new PresenceReportError(
      `Presence report failed with status ${response.status}.`,
      response.status,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new PresenceReportError("Presence response was not valid JSON.", response.status);
  }

  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    !isPresenceSnapshot((envelope as { data?: unknown }).data)
  ) {
    throw new PresenceReportError("Presence response had an invalid envelope.", response.status);
  }

  return (envelope as { data: PresenceSnapshot }).data;
};
