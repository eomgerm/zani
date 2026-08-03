import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const auth = vi.hoisted(() => ({ accessToken: "test-access-token" as string | null }));
vi.mock("@/domains/auth", () => ({ useAuth: () => auth }));

import type { LiveStateSnapshot } from "../infrastructure/liveStateApi";
import type {
  SessionChannelFactory,
  SessionChannelHandlers,
} from "../infrastructure/sessionChannel";
import type { SessionEventEnvelope } from "../infrastructure/sessionEvent";
import { SessionChannelProvider } from "./SessionChannelProvider";
import { CHAT_SEND_TIMEOUT_MS, useSessionChat } from "./useSessionChat";

const ME = "p-11";
const OTHER = "p-22";

const emptySnapshot: LiveStateSnapshot = {
  participants: [],
  chatMessages: [],
  raisedHandIdentities: [],
};

/** 채널을 대신한다. 테스트가 연결·수신 시점을 직접 몰아준다. */
const channel = {
  handlers: null as SessionChannelHandlers | null,
  published: [] as { clientEventId: string; content: string }[],
  deactivated: 0,
};

const channelFactory: SessionChannelFactory = ({ handlers }) => {
  channel.handlers = handlers;
  return {
    publishChat: (clientEventId, content) => channel.published.push({ clientEventId, content }),
    publishHand: () => {},
    publishReaction: () => {},
    deactivate: () => {
      channel.deactivated += 1;
    },
  };
};

let snapshots: LiveStateSnapshot[] = [];
const loadLiveState = vi.fn(async () => snapshots.shift() ?? emptySnapshot);

function wrapper({ children }: { children: ReactNode }) {
  return (
    <SessionChannelProvider
      sessionId="s1"
      channelFactory={channelFactory}
      loadLiveState={loadLiveState}
    >
      {children}
    </SessionChannelProvider>
  );
}

let sequence = 0;
const renderChat = () =>
  renderHook(
    () =>
      useSessionChat({
        myIdentity: ME,
        myDisplayName: "김민수",
        amInstructor: false,
        createClientEventId: () => {
          sequence += 1;
          return `c-${sequence}`;
        },
      }),
    { wrapper },
  );

/** 채널이 붙었다고 알린다. 실제 팩토리는 주제 구독을 끝낸 뒤 이 신호를 낸다. */
const connect = async () => {
  await act(async () => {
    channel.handlers?.onStateChange("connected");
  });
};

const receive = async (event: SessionEventEnvelope) => {
  await act(async () => {
    channel.handlers?.onEvent(event);
  });
};

const chatEvent = (overrides: Partial<SessionEventEnvelope> = {}): SessionEventEnvelope => ({
  eventId: "5001",
  clientEventId: null,
  type: "CHAT_MESSAGE",
  sender: { identity: OTHER, displayName: "박강사", role: "INSTRUCTOR" },
  occurredOffsetMs: 1_000,
  deliveredAt: "2026-07-30T09:00:01Z",
  payload: { content: "안녕하세요" },
  ...overrides,
});

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  auth.accessToken = "test-access-token";
  channel.handlers = null;
  channel.published = [];
  channel.deactivated = 0;
  snapshots = [];
  sequence = 0;
  loadLiveState.mockClear();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useSessionChat 전송", () => {
  it("연결되기 전에는 보낼 수 없고 전송 시도를 무시한다", () => {
    const { result } = renderChat();

    expect(result.current.canSend).toBe(false);
    act(() => result.current.send("질문 있습니다"));
    expect(channel.published).toHaveLength(0);
    expect(result.current.messages).toHaveLength(0);
  });

  it("연결되면 발행하고 보내는 중으로 그린다", async () => {
    const { result } = renderChat();
    await connect();

    expect(result.current.canSend).toBe(true);
    act(() => result.current.send("  질문 있습니다  "));

    expect(channel.published).toEqual([{ clientEventId: "c-1", content: "질문 있습니다" }]);
    expect(result.current.messages).toMatchObject([
      { content: "질문 있습니다", status: "sending", mine: true, authorName: "김민수" },
    ]);
  });

  it("빈 본문은 보내지 않는다", async () => {
    const { result } = renderChat();
    await connect();

    act(() => result.current.send("   "));

    expect(channel.published).toHaveLength(0);
    expect(result.current.messages).toHaveLength(0);
  });

  it("내 메시지가 돌아오면 확정되고 두 번 보이지 않는다", async () => {
    const { result } = renderChat();
    await connect();
    act(() => result.current.send("질문 있습니다"));

    await receive(
      chatEvent({
        eventId: "5009",
        clientEventId: "c-1",
        sender: { identity: ME, displayName: "김민수", role: "STUDENT" },
        payload: { content: "질문 있습니다" },
      }),
    );

    expect(result.current.messages).toHaveLength(1);
    expect(result.current.messages[0]).toMatchObject({ id: "5009", status: "sent", mine: true });
  });

  it("다른 사람의 메시지도 목록에 들어온다", async () => {
    const { result } = renderChat();
    await connect();

    await receive(chatEvent());

    expect(result.current.messages).toMatchObject([
      { content: "안녕하세요", authorName: "박강사", isInstructor: true, mine: false },
    ]);
  });
});

describe("useSessionChat 실패와 재시도", () => {
  it("서버가 거절하면 사유를 담아 실패로 표시한다", async () => {
    const { result } = renderChat();
    await connect();
    act(() => result.current.send("질문 있습니다"));

    await act(async () => {
      channel.handlers?.onRejection({ clientEventId: "c-1", reason: "CONTENT_TOO_LONG" });
    });

    expect(result.current.messages[0]).toMatchObject({
      status: "failed",
      failureReason: "CONTENT_TOO_LONG",
    });
  });

  /**
   * STOMP 발행은 응답이 없다. 연결이 끊겨 프레임이 사라지면 아무 신호도 오지 않으므로, 제한 시간이
   * 없으면 보내는 중 상태로 영원히 남는다.
   */
  it("확정이 제한 시간 안에 오지 않으면 실패로 표시한다", async () => {
    const { result } = renderChat();
    await connect();
    act(() => result.current.send("질문 있습니다"));

    expect(result.current.messages[0].status).toBe("sending");
    await act(async () => {
      vi.advanceTimersByTime(CHAT_SEND_TIMEOUT_MS);
    });

    expect(result.current.messages[0]).toMatchObject({
      status: "failed",
      failureReason: "SEND_TIMEOUT",
    });
  });

  it("확정이 오면 제한 시간이 지나도 실패로 바뀌지 않는다", async () => {
    const { result } = renderChat();
    await connect();
    act(() => result.current.send("질문 있습니다"));
    await receive(chatEvent({ eventId: "5009", clientEventId: "c-1" }));

    await act(async () => {
      vi.advanceTimersByTime(CHAT_SEND_TIMEOUT_MS * 2);
    });

    expect(result.current.messages[0].status).toBe("sent");
  });

  /** 서버가 clientEventId 로 멱등 처리하므로, 첫 전송이 실제로 도착했더라도 두 번 저장되지 않는다. */
  it("다시 시도는 같은 clientEventId 로 발행한다", async () => {
    const { result } = renderChat();
    await connect();
    act(() => result.current.send("질문 있습니다"));
    await act(async () => {
      channel.handlers?.onRejection({ clientEventId: "c-1", reason: "SEND_FAILED" });
    });

    act(() => result.current.retry("c-1"));

    expect(channel.published).toEqual([
      { clientEventId: "c-1", content: "질문 있습니다" },
      { clientEventId: "c-1", content: "질문 있습니다" },
    ]);
    expect(result.current.messages[0].status).toBe("sending");
  });

  it("실패하지 않은 항목의 재시도는 무시한다", async () => {
    const { result } = renderChat();
    await connect();
    act(() => result.current.send("질문 있습니다"));

    act(() => result.current.retry("c-1"));

    expect(channel.published).toHaveLength(1);
  });
});

describe("useSessionChat 스냅샷", () => {
  it("연결된 뒤 스냅샷을 받아 이력을 채운다", async () => {
    snapshots = [
      {
        participants: [{ identity: OTHER, displayName: "박강사", role: "INSTRUCTOR" }],
        chatMessages: [
          { eventId: "4001", senderIdentity: OTHER, occurredOffsetMs: 500, content: "이전 메시지" },
        ],
        raisedHandIdentities: [],
      },
    ];
    const { result } = renderChat();
    await connect();

    await waitFor(() => expect(result.current.messages).toHaveLength(1));
    expect(result.current.messages[0]).toMatchObject({
      id: "4001",
      content: "이전 메시지",
      authorName: "박강사",
    });
  });

  /** 재연결하면 끊긴 동안의 메시지를 놓쳤을 수 있으므로 이력을 다시 맞춘다. */
  it("재연결하면 스냅샷을 다시 받는다", async () => {
    const { result } = renderChat();
    await connect();
    await waitFor(() => expect(loadLiveState).toHaveBeenCalledTimes(1));

    await act(async () => {
      channel.handlers?.onStateChange("connecting");
    });
    expect(result.current.canSend).toBe(false);

    await connect();
    await waitFor(() => expect(loadLiveState).toHaveBeenCalledTimes(2));
  });

  it("스냅샷을 못 받아도 실시간 채팅은 이어진다", async () => {
    loadLiveState.mockRejectedValueOnce(new Error("네트워크 실패"));
    const { result } = renderChat();
    await connect();

    await receive(chatEvent());

    expect(result.current.messages).toHaveLength(1);
    expect(result.current.canSend).toBe(true);
  });
});
