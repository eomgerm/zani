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
const presenceUrl = (sessionId: string): string => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");
  return `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/presence`;
};

const presenceHeaders = (accessToken: string): HeadersInit => ({
  Accept: "application/json",
  "Content-Type": "application/json",
  Authorization: `Bearer ${accessToken}`,
});

export const reportPresence: PresenceReporter = async (
  sessionId,
  heartbeat,
  accessToken,
  signal,
) => {
  const response = await fetch(presenceUrl(sessionId), {
    method: "POST",
    headers: presenceHeaders(accessToken),
    credentials: "include",
    body: JSON.stringify(heartbeat),
    signal,
  });

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

/** 강의실을 떠나는 순간 보내는 이탈 보고. 응답을 받을 수 없으므로 반환값이 없다. */
export type PresenceExitReporter = (sessionId: string, accessToken: string) => void;

/**
 * 탭이 닫히거나 다른 페이지로 넘어갈 때 서버에 이탈을 알린다.
 *
 * <p>보내지 않으면 브라우저를 그냥 닫은 강사의 이탈이 서버에 도달하지 않는다. heartbeat 는 탭과 함께 멈출 뿐 "끊겼다"를 알리지 않으므로, 5분 유예가 시작되지 않고 수업이 3시간 상한까지
 * LIVE 로 남는다.
 *
 * <p><b>{@code keepalive} 가 핵심이다.</b> 페이지가 사라지면 일반 fetch 는 그대로 취소된다. 이 옵션이 있어야 문서가 죽은 뒤에도 브라우저가 요청을 끝까지 보낸다.
 *
 * <p>{@code navigator.sendBeacon} 을 쓰지 않는 이유: 서버가 Bearer 토큰으로 사용자를 판별하는데 sendBeacon 은 헤더를 붙일 수 없어 무조건 401 이 된다.
 *
 * <p>응답은 기다리지 않는다. 이 시점의 화면은 곧 사라지므로 받아도 쓸 곳이 없고, 실패해도 재시도할 페이지가 없다.
 */
export const reportPresenceExit: PresenceExitReporter = (sessionId, accessToken) => {
  const heartbeat: PresenceHeartbeat = {
    heartbeatAt: new Date().toISOString(),
    connectionState: "DISCONNECTED",
  };
  void fetch(presenceUrl(sessionId), {
    method: "POST",
    headers: presenceHeaders(accessToken),
    credentials: "include",
    body: JSON.stringify(heartbeat),
    keepalive: true,
  }).catch(() => {
    // 떠나는 길이라 할 수 있는 것이 없다. 서버는 presence TTL 만료로 뒤늦게 알아차린다.
  });
};
