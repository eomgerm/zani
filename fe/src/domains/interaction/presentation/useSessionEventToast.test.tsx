import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const auth = vi.hoisted(() => ({ accessToken: "token-1" as string | null }));
vi.mock("@/domains/auth", () => ({ useAuth: () => auth }));

import type { LiveStateSnapshot } from "../infrastructure/liveStateApi";
import type { SessionChannelFactory, SessionChannelHandlers } from "../infrastructure/sessionChannel";
import type { SessionEventEnvelope } from "../infrastructure/sessionEvent";
import { SessionChannelProvider } from "./SessionChannelProvider";
import { useSessionEventToast } from "./useSessionEventToast";

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

const event = (overrides: Partial<SessionEventEnvelope> = {}): SessionEventEnvelope => ({
  eventId: "e-1",
  clientEventId: null,
  type: "CHAT_MESSAGE",
  sender: { identity: "p-2", displayName: "이지은", role: "STUDENT" },
  occurredOffsetMs: 1_000,
  deliveredAt: "2026-08-01T10:00:00Z",
  payload: { content: "안녕하세요" },
  ...overrides,
});

const handRaised = (identity = "p-2", displayName = "이지은"): SessionEventEnvelope =>
  event({
    eventId: `hand-${identity}`,
    type: "HAND_RAISED",
    sender: { identity, displayName, role: "STUDENT" },
    payload: {},
  });

interface ProbeProps {
  myIdentity: string | null;
  active: boolean;
  raisedIdentities: readonly string[];
}

function Probe({ myIdentity, active, raisedIdentities }: ProbeProps) {
  const toast = useSessionEventToast({ myIdentity, active, raisedIdentities });
  return <output data-testid="toast">{toast?.message ?? ""}</output>;
}

const probe = (props: Partial<ProbeProps> = {}) => (
  <SessionChannelProvider
    sessionId="s1"
    channelFactory={channelFactory}
    loadLiveState={async () => emptySnapshot}
  >
    <Probe myIdentity="p-1" active raisedIdentities={[]} {...props} />
  </SessionChannelProvider>
);

const renderProbe = (props: Partial<ProbeProps> = {}) => render(probe(props));

const toastText = () => screen.getByTestId("toast").textContent;

beforeEach(() => {
  vi.useFakeTimers();
  auth.accessToken = "token-1";
  channel.handlers = null;
});

afterEach(() => {
  vi.useRealTimers();
  vi.clearAllMocks();
});

describe("useSessionEventToast", () => {
  it("남이 손을 들면 토스트가 뜬다", () => {
    renderProbe();

    act(() => channel.handlers?.onEvent(handRaised()));

    expect(toastText()).toBe("이지은 님이 손을 들었어요");
  });

  it("남이 채팅을 보내면 보낸 사람과 본문이 뜬다", () => {
    renderProbe();

    act(() => channel.handlers?.onEvent(event()));

    expect(toastText()).toBe("이지은: 안녕하세요");
  });

  it("표시 시간이 지나면 저절로 사라진다", () => {
    renderProbe();
    act(() => channel.handlers?.onEvent(event()));
    expect(toastText()).toBe("이지은: 안녕하세요");

    act(() => vi.advanceTimersByTime(3_000));

    expect(toastText()).toBe("");
  });

  it("연달아 오면 최신 것이 대체하고 소멸 시점도 새로 센다", () => {
    renderProbe();
    act(() => channel.handlers?.onEvent(event({ eventId: "e-1" })));
    act(() => vi.advanceTimersByTime(2_000));

    act(() =>
      channel.handlers?.onEvent(event({ eventId: "e-2", payload: { content: "두 번째" } })),
    );
    expect(toastText()).toBe("이지은: 두 번째");

    // 첫 토스트의 타이머 시점(3초)이 지나도 새 토스트는 남아 있어야 한다.
    act(() => vi.advanceTimersByTime(1_500));
    expect(toastText()).toBe("이지은: 두 번째");

    act(() => vi.advanceTimersByTime(1_500));
    expect(toastText()).toBe("");
  });

  it("표면이 꺼져 있는 동안 도착한 이벤트는 버린다", () => {
    const view = renderProbe({ active: false });

    act(() => channel.handlers?.onEvent(event()));
    expect(toastText()).toBe("");

    // 나중에 표면이 다시 떠도 지난 알림을 되살리지 않는다.
    view.rerender(probe({ active: true }));
    expect(toastText()).toBe("");
  });

  it("표면이 닫히면 떠 있던 토스트도 사라지고, 다시 열어도 되살아나지 않는다", () => {
    const view = renderProbe();
    act(() => channel.handlers?.onEvent(event()));
    expect(toastText()).toBe("이지은: 안녕하세요");

    view.rerender(probe({ active: false }));
    expect(toastText()).toBe("");

    view.rerender(probe({ active: true }));
    expect(toastText()).toBe("");
  });

  it("내가 보낸 채팅의 echo 는 띄우지 않는다", () => {
    renderProbe();

    act(() =>
      channel.handlers?.onEvent(
        event({ sender: { identity: "p-1", displayName: "나", role: "INSTRUCTOR" } }),
      ),
    );

    expect(toastText()).toBe("");
  });

  it("내 손들기의 echo 는 띄우지 않는다", () => {
    renderProbe();

    act(() => channel.handlers?.onEvent(handRaised("p-1", "나")));

    expect(toastText()).toBe("");
  });

  it("이미 손을 든 참가자의 재수신(서버 재시도)은 다시 띄우지 않는다", () => {
    renderProbe({ raisedIdentities: ["p-2"] });

    act(() => channel.handlers?.onEvent(handRaised()));

    expect(toastText()).toBe("");
  });

  it("손 내리기·반응은 띄우지 않는다", () => {
    renderProbe();

    act(() => channel.handlers?.onEvent(event({ type: "HAND_LOWERED", payload: {} })));
    act(() =>
      channel.handlers?.onEvent(event({ type: "REACTION", payload: { reaction: "LIKE" } })),
    );

    expect(toastText()).toBe("");
  });

  it("본문이 없는 채팅 프레임은 띄우지 않는다", () => {
    renderProbe();

    act(() => channel.handlers?.onEvent(event({ payload: {} })));

    expect(toastText()).toBe("");
  });

  it("재입장 스냅샷으로 복원된 이력은 띄우지 않는다", async () => {
    // 스냅샷은 이벤트 리스너가 아니라 REST(getlivestate)로 흐른다. 이 훅은 리스너만 구독하므로
    // 이력이 실린 스냅샷이 도착해도 토스트가 뜨지 않아야 한다.
    const restoredSnapshot: LiveStateSnapshot = {
      participants: [],
      chatMessages: [
        { eventId: "e-0", senderIdentity: "p-2", occurredOffsetMs: 500, content: "지난 메시지" },
      ],
      raisedHandIdentities: ["p-3"],
    };

    render(
      <SessionChannelProvider
        sessionId="s1"
        channelFactory={channelFactory}
        loadLiveState={async () => restoredSnapshot}
      >
        <Probe myIdentity="p-1" active raisedIdentities={[]} />
      </SessionChannelProvider>,
    );

    // 연결 완료를 알려야 provider 가 스냅샷을 읽으러 간다.
    act(() => channel.handlers?.onStateChange("connected"));
    await act(async () => {});

    expect(toastText()).toBe("");
  });
});
