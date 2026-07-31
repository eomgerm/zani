/**
 * 업무 이벤트 봉투. 백엔드 `SessionEvent` 와 같은 형태다(티켓 63 계약).
 *
 * 세션 주제 하나(`/topic/sessions/{sessionId}`)로 모든 종류가 내려오고 `type` 으로 갈라 처리한다.
 * 손들기·반응(64), 화면 공유(65), 강제 음소거(66)가 같은 봉투에 종류를 추가한다.
 */

/** 지금은 채팅뿐이다. 64~66 이 값을 추가한다. */
export type SessionEventType = "CHAT_MESSAGE";

export interface SessionEventSender {
  /**
   * LiveKit participant identity 와 **같은 값**(`p-{participantId}`). 참가자 목록과 이 이벤트를
   * 이어 붙이는 키다. 이메일 등 개인 식별 정보는 담기지 않는다.
   */
  readonly identity: string;
  readonly displayName: string;
  readonly role: "INSTRUCTOR" | "STUDENT";
}

export interface SessionEventEnvelope {
  /** 서버가 부여한 식별자. 중복 제거 기준이고 값 자체가 발생 순서를 담는다. */
  readonly eventId: string;
  /** 내가 보낸 것이면 내가 만든 값이 그대로 돌아온다. 다른 사람의 이벤트에도 값이 실려 있다. */
  readonly clientEventId: string | null;
  readonly type: SessionEventType;
  readonly sender: SessionEventSender;
  /** 수업 시작 기준 발생 시각(ms). 리포트 타임라인과 같은 축이다. */
  readonly occurredOffsetMs: number;
  readonly deliveredAt: string;
  readonly payload: Readonly<Record<string, unknown>>;
}

/** 전송이 거절됐다는 통지. 보낸 사람에게만 온다(`/user/queue/errors`). */
export interface SessionEventRejection {
  readonly clientEventId: string;
  readonly reason: string;
}

const isNonBlankString = (value: unknown): value is string =>
  typeof value === "string" && value.trim().length > 0;

const parseSender = (value: unknown): SessionEventSender | null => {
  if (typeof value !== "object" || value === null) return null;
  const sender = value as Record<string, unknown>;
  if (!isNonBlankString(sender.identity) || !isNonBlankString(sender.displayName)) return null;
  return {
    identity: sender.identity,
    displayName: sender.displayName,
    role: sender.role === "INSTRUCTOR" ? "INSTRUCTOR" : "STUDENT",
  };
};

/**
 * 봉투를 읽는다. 형태가 어긋나면 null 을 준다 — 알 수 없는 프레임 하나로 수업 화면이 죽지 않게 한다.
 *
 * 모르는 `type` 도 null 이다. 64~66 이 배포되는 사이 이 화면이 먼저 떠 있을 수 있다.
 */
export function parseSessionEvent(body: string): SessionEventEnvelope | null {
  let raw: unknown;
  try {
    raw = JSON.parse(body);
  } catch {
    return null;
  }
  if (typeof raw !== "object" || raw === null) return null;

  const event = raw as Record<string, unknown>;
  const sender = parseSender(event.sender);
  if (
    !isNonBlankString(event.eventId) ||
    event.type !== "CHAT_MESSAGE" ||
    sender === null ||
    typeof event.occurredOffsetMs !== "number"
  ) {
    return null;
  }

  return {
    eventId: event.eventId,
    clientEventId: isNonBlankString(event.clientEventId) ? event.clientEventId : null,
    type: event.type,
    sender,
    occurredOffsetMs: event.occurredOffsetMs,
    deliveredAt: typeof event.deliveredAt === "string" ? event.deliveredAt : "",
    payload:
      typeof event.payload === "object" && event.payload !== null
        ? (event.payload as Record<string, unknown>)
        : {},
  };
}

export function parseSessionEventRejection(body: string): SessionEventRejection | null {
  let raw: unknown;
  try {
    raw = JSON.parse(body);
  } catch {
    return null;
  }
  if (typeof raw !== "object" || raw === null) return null;

  const rejection = raw as Record<string, unknown>;
  if (!isNonBlankString(rejection.clientEventId)) return null;
  return {
    clientEventId: rejection.clientEventId,
    reason: isNonBlankString(rejection.reason) ? rejection.reason : "SEND_FAILED",
  };
}

/** 채팅 본문. 계약상 `payload.content` 하나다. */
export function chatContentOf(event: SessionEventEnvelope): string | null {
  return isNonBlankString(event.payload.content) ? event.payload.content : null;
}
