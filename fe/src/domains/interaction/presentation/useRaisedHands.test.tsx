import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const auth = vi.hoisted(() => ({ accessToken: "test-access-token" as string | null }));
vi.mock("@/domains/auth", () => ({ useAuth: () => auth }));

import type { LiveStateSnapshot } from "../infrastructure/liveStateApi";
import type {
  SessionChannelFactory,
  SessionChannelHandlers,
} from "../infrastructure/sessionChannel";
import type { SessionEventEnvelope, SessionEventType } from "../infrastructure/sessionEvent";
import { SessionChannelProvider } from "./SessionChannelProvider";
import { useRaisedHands } from "./useRaisedHands";

const ME = "p-11";
const OTHER = "p-22";

const emptySnapshot: LiveStateSnapshot = {
  participants: [],
  chatMessages: [],
  raisedHandIdentities: [],
};

const channel = {
  handlers: null as SessionChannelHandlers | null,
  hands: [] as { clientEventId: string; raised: boolean }[],
};

const channelFactory: SessionChannelFactory = ({ handlers }) => {
  channel.handlers = handlers;
  return {
    publishChat: () => {},
    publishHand: (clientEventId, raised) => channel.hands.push({ clientEventId, raised }),
    publishReaction: () => {},
    deactivate: () => {},
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
const renderHands = (myIdentity: string | null = ME) =>
  renderHook(
    () =>
      useRaisedHands({
        myIdentity,
        createClientEventId: () => {
          sequence += 1;
          return `h-${sequence}`;
        },
      }),
    { wrapper },
  );

const connect = async () => {
  await act(async () => {
    channel.handlers?.onStateChange("connected");
  });
};

const receive = async (type: SessionEventType, identity: string) => {
  const event: SessionEventEnvelope = {
    eventId: `e-${identity}-${type}`,
    clientEventId: null,
    type,
    sender: { identity, displayName: "김민수", role: "STUDENT" },
    occurredOffsetMs: 1_000,
    deliveredAt: "2026-07-30T09:00:01Z",
    payload: {},
  };
  await act(async () => {
    channel.handlers?.onEvent(event);
  });
};

beforeEach(() => {
  auth.accessToken = "test-access-token";
  channel.handlers = null;
  channel.hands = [];
  snapshots = [];
  sequence = 0;
  loadLiveState.mockClear();
});

describe("useRaisedHands", () => {
  it("스냅샷의 손든 순서를 그대로 받는다", async () => {
    snapshots = [{ ...emptySnapshot, raisedHandIdentities: [OTHER, ME] }];
    const { result } = renderHands();
    await connect();

    await waitFor(() => expect(result.current.raisedIdentities).toEqual([OTHER, ME]));
    expect(result.current.myHandRaised).toBe(true);
  });

  it("실시간 이벤트로 목록이 늘고 준다", async () => {
    const { result } = renderHands();
    await connect();

    await receive("HAND_RAISED", OTHER);
    await receive("HAND_RAISED", ME);
    expect(result.current.raisedIdentities).toEqual([OTHER, ME]);
    expect(result.current.myHandRaised).toBe(true);

    await receive("HAND_LOWERED", ME);
    expect(result.current.raisedIdentities).toEqual([OTHER]);
    expect(result.current.myHandRaised).toBe(false);
  });

  /**
   * 낙관적으로 그리지 않는다. 손들기는 상태라 틀리게 그렸다가 되돌리는 편이 더 어색하고,
   * 순번은 서버 수신 시각으로 정해지므로 내 화면이 앞서면 순번만 남과 달라진다.
   */
  it("보내기만 하고 확정이 오기 전에는 눌린 상태로 보이지 않는다", async () => {
    const { result } = renderHands();
    await connect();

    act(() => result.current.toggle());

    expect(channel.hands).toEqual([{ clientEventId: "h-1", raised: true }]);
    expect(result.current.myHandRaised).toBe(false);

    await receive("HAND_RAISED", ME);
    expect(result.current.myHandRaised).toBe(true);
  });

  it("손이 들려 있으면 내리기를 보낸다", async () => {
    const { result } = renderHands();
    await connect();
    await receive("HAND_RAISED", ME);

    act(() => result.current.toggle());

    expect(channel.hands).toEqual([{ clientEventId: "h-1", raised: false }]);
  });

  it("연결 전에는 바꿀 수 없다", () => {
    const { result } = renderHands();

    expect(result.current.canToggle).toBe(false);
    act(() => result.current.toggle());
    expect(channel.hands).toEqual([]);
  });

  /** identity 를 모르면 내 상태를 판정할 수 없어, 눌러도 무엇을 보낼지 정할 수 없다. */
  it("내 identity 를 모르면 바꿀 수 없다", async () => {
    const { result } = renderHands(null);
    await connect();

    expect(result.current.canToggle).toBe(false);
    act(() => result.current.toggle());
    expect(channel.hands).toEqual([]);
  });
});
