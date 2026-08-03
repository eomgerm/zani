import { describe, expect, it } from "vitest";

import type { LiveStateSnapshot } from "../infrastructure/liveStateApi";
import type { SessionEventEnvelope } from "../infrastructure/sessionEvent";
import {
  chatMessageViews,
  chatReducer,
  initialChatState,
  type ChatAction,
  type ChatState,
} from "./chatMessages";

const ME = "p-11";
const OTHER = "p-22";

const snapshot = (
  overrides: Partial<LiveStateSnapshot> = {},
): LiveStateSnapshot => ({
  participants: [
    { identity: ME, displayName: "김민수", role: "STUDENT" },
    { identity: OTHER, displayName: "박강사", role: "INSTRUCTOR" },
  ],
  chatMessages: [
    { eventId: "5001", senderIdentity: OTHER, occurredOffsetMs: 1_000, content: "안녕하세요" },
    { eventId: "5002", senderIdentity: ME, occurredOffsetMs: 2_000, content: "질문 있습니다" },
  ],
  raisedHandIdentities: [],
  ...overrides,
});

const event = (overrides: Partial<SessionEventEnvelope> = {}): SessionEventEnvelope => ({
  eventId: "5003",
  clientEventId: null,
  type: "CHAT_MESSAGE",
  sender: { identity: OTHER, displayName: "박강사", role: "INSTRUCTOR" },
  occurredOffsetMs: 3_000,
  deliveredAt: "2026-07-30T09:00:03Z",
  payload: { content: "네 시작할게요" },
  ...overrides,
});

const reduceAll = (actions: readonly ChatAction[], from: ChatState = initialChatState) =>
  actions.reduce(chatReducer, from);

describe("chatReducer 스냅샷", () => {
  it("이력에 디렉터리의 이름과 역할을 붙인다", () => {
    const state = chatReducer(initialChatState, { type: "snapshot", snapshot: snapshot() });

    expect(state.entries.map((entry) => [entry.authorName, entry.isInstructor])).toEqual([
      ["박강사", true],
      ["김민수", false],
    ]);
    expect(state.entries.every((entry) => entry.status === "sent")).toBe(true);
  });

  // 디렉터리에 없는 발신자로도 화면이 죽지 않아야 한다.
  it("디렉터리에 없는 발신자는 알 수 없음으로 둔다", () => {
    const state = chatReducer(initialChatState, {
      type: "snapshot",
      snapshot: snapshot({ participants: [] }),
    });

    expect(state.entries[0].authorName).toBe("알 수 없음");
    expect(state.entries[0].isInstructor).toBe(false);
  });

  /**
   * 구독을 먼저 걸고 스냅샷을 나중에 받기 때문에, 스냅샷이 이미 받은 실시간 메시지를 포함할 수 있다.
   * 겹치는 것을 걸러내지 않으면 같은 메시지가 두 번 보인다.
   */
  it("늦게 온 스냅샷은 이미 받은 실시간 메시지와 겹치는 것을 걸러내고 이력을 앞에 놓는다", () => {
    const state = reduceAll([
      { type: "received", event: event({ eventId: "5002" }) },
      { type: "received", event: event({ eventId: "5003" }) },
      { type: "snapshot", snapshot: snapshot() },
    ]);

    expect(state.entries.map((entry) => entry.id)).toEqual(["5001", "5002", "5003"]);
  });

  it("아직 보내는 중인 내 전송은 스냅샷이 지우지 않는다", () => {
    const state = reduceAll([
      {
        type: "sending",
        clientEventId: "c-1",
        content: "보내는 중",
        authorName: "김민수",
        isInstructor: false,
      },
      { type: "snapshot", snapshot: snapshot() },
    ]);

    expect(state.entries.map((entry) => entry.id)).toEqual(["5001", "5002", "c-1"]);
    expect(state.entries.at(-1)?.status).toBe("sending");
  });
});

describe("chatReducer 실시간 수신", () => {
  it("같은 eventId 를 두 번 받으면 한 번만 그린다", () => {
    const state = reduceAll([
      { type: "received", event: event() },
      { type: "received", event: event() },
    ]);

    expect(state.entries).toHaveLength(1);
  });

  it("본문이 없는 채팅 이벤트는 버린다", () => {
    const state = chatReducer(initialChatState, {
      type: "received",
      event: event({ payload: {} }),
    });

    expect(state.entries).toHaveLength(0);
  });

  /** 새로 붙이면 내가 보낸 메시지가 두 번 보인다. */
  it("내가 보낸 것이 돌아오면 낙관적으로 그려 둔 자리를 그대로 확정한다", () => {
    const state = reduceAll([
      {
        type: "sending",
        clientEventId: "c-1",
        content: "질문 있습니다",
        authorName: "김민수",
        isInstructor: false,
      },
      { type: "received", event: event({ eventId: "5009", clientEventId: "c-1" }) },
    ]);

    expect(state.entries).toHaveLength(1);
    expect(state.entries[0].id).toBe("5009");
    expect(state.entries[0].status).toBe("sent");
    expect(state.entries[0].senderIdentity).toBe(OTHER);
  });

  it("실시간 이벤트의 발신자 정보로 디렉터리를 채운다", () => {
    const state = chatReducer(initialChatState, { type: "received", event: event() });

    expect(state.directory[OTHER]).toEqual({ displayName: "박강사", isInstructor: true });
  });
});

describe("chatReducer 전송 상태", () => {
  const sending: ChatAction = {
    type: "sending",
    clientEventId: "c-1",
    content: "질문 있습니다",
    authorName: "김민수",
    isInstructor: false,
  };

  it("보내는 중 항목을 붙인다", () => {
    const state = chatReducer(initialChatState, sending);

    expect(state.entries[0]).toMatchObject({
      id: "c-1",
      clientEventId: "c-1",
      senderIdentity: null,
      status: "sending",
    });
  });

  it("거절 사유를 담아 실패로 표시한다", () => {
    const state = reduceAll([sending, { type: "failed", clientEventId: "c-1", reason: "EMPTY_CONTENT" }]);

    expect(state.entries[0].status).toBe("failed");
    expect(state.entries[0].failureReason).toBe("EMPTY_CONTENT");
  });

  it("다시 시도하면 보내는 중으로 되돌리고 사유를 지운다", () => {
    const state = reduceAll([
      sending,
      { type: "failed", clientEventId: "c-1", reason: "SEND_TIMEOUT" },
      { type: "retrying", clientEventId: "c-1" },
    ]);

    expect(state.entries[0].status).toBe("sending");
    expect(state.entries[0].failureReason).toBeNull();
  });

  // 확정된 메시지를 나중에 도착한 실패 통지가 되돌리면 안 된다.
  it("이미 확정된 메시지는 실패로 바뀌지 않는다", () => {
    const state = reduceAll([
      sending,
      { type: "received", event: event({ eventId: "5009", clientEventId: "c-1" }) },
      { type: "failed", clientEventId: "c-1", reason: "SEND_TIMEOUT" },
    ]);

    expect(state.entries[0].status).toBe("sent");
  });
});

describe("chatMessageViews", () => {
  it("내 identity 와 같은 발신자를 내 메시지로 본다", () => {
    const state = chatReducer(initialChatState, { type: "snapshot", snapshot: snapshot() });

    expect(chatMessageViews(state.entries, ME).map((view) => view.mine)).toEqual([false, true]);
  });

  it("아직 확정되지 않은 항목은 항상 내 메시지다", () => {
    const state = chatReducer(initialChatState, {
      type: "sending",
      clientEventId: "c-1",
      content: "보내는 중",
      authorName: "김민수",
      isInstructor: false,
    });

    expect(chatMessageViews(state.entries, null)[0].mine).toBe(true);
  });

  /**
   * LiveKit 연결이 스냅샷보다 늦으면 내 identity 를 모르는 채로 이력을 받는다. 판정을 굳혀 두면
   * 내 옛 메시지가 남의 것으로 남는데, 매번 다시 계산하므로 identity 가 늦게 와도 고쳐진다.
   */
  it("내 identity 를 늦게 알게 돼도 판정이 고쳐진다", () => {
    const state = chatReducer(initialChatState, { type: "snapshot", snapshot: snapshot() });

    expect(chatMessageViews(state.entries, null).map((view) => view.mine)).toEqual([false, false]);
    expect(chatMessageViews(state.entries, ME).map((view) => view.mine)).toEqual([false, true]);
  });
});
