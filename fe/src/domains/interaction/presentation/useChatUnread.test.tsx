import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const auth = vi.hoisted(() => ({ accessToken: "token-1" as string | null }));
vi.mock("@/domains/auth", () => ({ useAuth: () => auth }));

import type { LiveStateSnapshot } from "../infrastructure/liveStateApi";
import type { SessionChannelFactory, SessionChannelHandlers } from "../infrastructure/sessionChannel";
import type { SessionEventEnvelope } from "../infrastructure/sessionEvent";
import { SessionChannelProvider } from "./SessionChannelProvider";
import { useChatUnread } from "./useChatUnread";

const emptySnapshot: LiveStateSnapshot = {
  participants: [],
  chatMessages: [],
  raisedHandIdentities: [],
};

/** 실시간 프레임을 테스트가 직접 흘려 넣기 위해 채널 핸들러를 붙잡아 둔다. */
const channel = { handlers: null as SessionChannelHandlers | null };

const channelFactory: SessionChannelFactory = ({ handlers }) => {
  channel.handlers = handlers;
  return {
    publishChat: () => {},
    publishHand: () => {},
    publishReaction: () => {},
    deactivate: () => {},
  };
};

const chatEvent = (overrides: Partial<SessionEventEnvelope> = {}): SessionEventEnvelope => ({
  eventId: "e-1",
  clientEventId: null,
  type: "CHAT_MESSAGE",
  sender: { identity: "p-2", displayName: "이지은", role: "STUDENT" },
  occurredOffsetMs: 1_000,
  deliveredAt: "2026-08-01T10:00:00Z",
  payload: { content: "안녕하세요" },
  ...overrides,
});

function Probe({ myIdentity, chatVisible }: { myIdentity: string | null; chatVisible: boolean }) {
  const unread = useChatUnread({ myIdentity, chatVisible });
  return <output data-testid="unread">{String(unread)}</output>;
}

const renderProbe = (props: { myIdentity: string | null; chatVisible: boolean }) =>
  render(
    <SessionChannelProvider
      sessionId="s1"
      channelFactory={channelFactory}
      loadLiveState={async () => emptySnapshot}
    >
      <Probe {...props} />
    </SessionChannelProvider>,
  );

const unreadValue = () => screen.getByTestId("unread").textContent;

beforeEach(() => {
  auth.accessToken = "token-1";
  channel.handlers = null;
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("useChatUnread", () => {
  it("채팅이 보이지 않는 동안 남이 보낸 메시지가 오면 읽지 않음이 된다", () => {
    renderProbe({ myIdentity: "p-1", chatVisible: false });

    act(() => channel.handlers?.onEvent(chatEvent()));

    expect(unreadValue()).toBe("true");
  });

  it("채팅이 보이는 동안 도착한 메시지는 세지 않는다", () => {
    renderProbe({ myIdentity: "p-1", chatVisible: true });

    act(() => channel.handlers?.onEvent(chatEvent()));

    expect(unreadValue()).toBe("false");
  });

  it("내가 보낸 메시지의 echo 는 세지 않는다", () => {
    renderProbe({ myIdentity: "p-1", chatVisible: false });

    act(() =>
      channel.handlers?.onEvent(
        chatEvent({ sender: { identity: "p-1", displayName: "나", role: "STUDENT" } }),
      ),
    );

    expect(unreadValue()).toBe("false");
  });

  it("채팅을 열면 읽지 않음이 사라진다", () => {
    const view = renderProbe({ myIdentity: "p-1", chatVisible: false });
    act(() => channel.handlers?.onEvent(chatEvent()));
    expect(unreadValue()).toBe("true");

    view.rerender(
      <SessionChannelProvider
        sessionId="s1"
        channelFactory={channelFactory}
        loadLiveState={async () => emptySnapshot}
      >
        <Probe myIdentity="p-1" chatVisible />
      </SessionChannelProvider>,
    );

    expect(unreadValue()).toBe("false");
  });

  it("채팅 외 이벤트(손들기 등)는 세지 않는다", () => {
    renderProbe({ myIdentity: "p-1", chatVisible: false });

    act(() => channel.handlers?.onEvent(chatEvent({ type: "HAND_RAISED", payload: {} })));

    expect(unreadValue()).toBe("false");
  });

  it("재입장 스냅샷으로 복원된 이력은 세지 않는다", async () => {
    // 스냅샷은 이벤트 리스너가 아니라 REST(getlivestate)로 흐른다. 이 훅은 리스너만 구독하므로
    // 이력이 실린 스냅샷이 도착해도 읽지 않음이 켜지지 않아야 한다.
    const restoredSnapshot: LiveStateSnapshot = {
      participants: [],
      chatMessages: [
        { eventId: "e-0", senderIdentity: "p-2", occurredOffsetMs: 500, content: "지난 메시지" },
      ],
      raisedHandIdentities: [],
    };

    render(
      <SessionChannelProvider
        sessionId="s1"
        channelFactory={channelFactory}
        loadLiveState={async () => restoredSnapshot}
      >
        <Probe myIdentity="p-1" chatVisible={false} />
      </SessionChannelProvider>,
    );

    // 연결 완료를 알려야 provider 가 스냅샷을 읽으러 간다.
    act(() => channel.handlers?.onStateChange("connected"));
    await act(async () => {});

    expect(unreadValue()).toBe("false");
  });
});
