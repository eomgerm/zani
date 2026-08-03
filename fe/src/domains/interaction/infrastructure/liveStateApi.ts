/**
 * 재연결 직후 화면을 되돌리는 스냅샷 어댑터.
 *
 * 이력을 STOMP 로 재생하지 않는 이유: 소켓으로 흘리면 "재생 완료" 표시가 필요하고, 재생 중 도착한
 * 실시간 메시지와의 중복도 이쪽이 따로 처리해야 한다. REST 로 한 번 받고 `eventId` 로 겹치는
 * 것만 걸러내는 편이 단순하다.
 *
 * <b>호출 순서가 중요하다</b> — 세션 주제를 먼저 구독하고 그다음 이 스냅샷을 받아야 한다. 순서를
 * 뒤집으면 두 호출 사이에 도착한 메시지가 어디에도 남지 않는다.
 */

export interface LiveStateParticipant {
  /** LiveKit participant identity 와 같은 값. */
  readonly identity: string;
  readonly displayName: string;
  readonly role: "INSTRUCTOR" | "STUDENT";
}

export interface LiveStateChatMessage {
  /** 실시간 스트림의 `eventId` 와 같은 값. 중복 제거 기준. */
  readonly eventId: string;
  readonly senderIdentity: string;
  readonly occurredOffsetMs: number;
  readonly content: string;
}

export interface LiveStateSnapshot {
  /**
   * 세션 참가자 디렉터리. **이미 퇴장한 참가자도 들어 있다** — 채팅 이력에는 떠난 사람의 메시지가
   * 남아 있어 LiveKit 참가자 목록만으로는 발신자 이름을 채울 수 없다.
   */
  readonly participants: readonly LiveStateParticipant[];
  readonly chatMessages: readonly LiveStateChatMessage[];
  /** 지금 손을 든 참가자. 손들기(64)가 채운다. */
  readonly raisedHandIdentities: readonly string[];
}

export type LiveStateFetcher = (
  sessionId: string,
  accessToken: string,
  signal?: AbortSignal,
) => Promise<LiveStateSnapshot>;

export class LiveStateFetchError extends Error {
  /** HTTP 상태. 네트워크 실패 등 응답이 없으면 0. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "LiveStateFetchError";
    this.status = status;
  }
}

const isNonBlankString = (value: unknown): value is string =>
  typeof value === "string" && value.trim().length > 0;

const parseParticipant = (value: unknown): LiveStateParticipant | null => {
  if (typeof value !== "object" || value === null) return null;
  const participant = value as Record<string, unknown>;
  if (!isNonBlankString(participant.identity) || !isNonBlankString(participant.displayName)) {
    return null;
  }
  return {
    identity: participant.identity,
    displayName: participant.displayName,
    role: participant.role === "INSTRUCTOR" ? "INSTRUCTOR" : "STUDENT",
  };
};

const parseChatMessage = (value: unknown): LiveStateChatMessage | null => {
  if (typeof value !== "object" || value === null) return null;
  const message = value as Record<string, unknown>;
  if (
    !isNonBlankString(message.eventId) ||
    !isNonBlankString(message.senderIdentity) ||
    typeof message.occurredOffsetMs !== "number" ||
    typeof message.content !== "string"
  ) {
    return null;
  }
  return {
    eventId: message.eventId,
    senderIdentity: message.senderIdentity,
    occurredOffsetMs: message.occurredOffsetMs,
    content: message.content,
  };
};

/** 읽을 수 없는 항목만 버리고 나머지는 살린다. 한 건이 깨졌다고 이력 전체를 잃을 이유가 없다. */
const parseList = <T>(value: unknown, parse: (item: unknown) => T | null): T[] =>
  Array.isArray(value) ? value.map(parse).filter((item): item is T => item !== null) : [];

/**
 * 서버는 Access Token 으로 세션 멤버십을 판단한다. 쿠키는 refresh 전용이라 API 인증 경로가
 * 아니므로 Bearer 헤더가 없으면 401 이다.
 */
export const fetchLiveState: LiveStateFetcher = async (sessionId, accessToken, signal) => {
  const apiBaseUrl = (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");

  let response: Response;
  try {
    response = await fetch(
      `${apiBaseUrl}/api/v1/sessions/${encodeURIComponent(sessionId)}/live-state`,
      {
        method: "GET",
        headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` },
        credentials: "include",
        signal,
      },
    );
  } catch (error) {
    throw new LiveStateFetchError(`Live state request failed: ${String(error)}`, 0);
  }

  if (!response.ok) {
    throw new LiveStateFetchError(
      `Live state failed with status ${response.status}.`,
      response.status,
    );
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new LiveStateFetchError("Live state response was not valid JSON.", response.status);
  }

  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true ||
    typeof (envelope as { data?: unknown }).data !== "object" ||
    (envelope as { data?: unknown }).data === null
  ) {
    throw new LiveStateFetchError("Live state response had an invalid envelope.", response.status);
  }

  const data = (envelope as { data: Record<string, unknown> }).data;
  return {
    participants: parseList(data.participants, parseParticipant),
    chatMessages: parseList(data.chatMessages, parseChatMessage),
    raisedHandIdentities: Array.isArray(data.raisedHandIdentities)
      ? data.raisedHandIdentities.filter(isNonBlankString)
      : [],
  };
};
