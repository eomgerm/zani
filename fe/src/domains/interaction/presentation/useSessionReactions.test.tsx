import { act, renderHook } from "@testing-library/react";
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
import { REACTION_FLOAT_MS, useSessionReactions } from "./useSessionReactions";

const emptySnapshot: LiveStateSnapshot = {
  participants: [],
  chatMessages: [],
  raisedHandIdentities: [],
};

const channel = {
  handlers: null as SessionChannelHandlers | null,
  published: [] as { clientEventId: string; reaction: string }[],
};

const channelFactory: SessionChannelFactory = ({ handlers }) => {
  channel.handlers = handlers;
  return {
    publishChat: () => {},
    publishHand: () => {},
    publishReaction: (clientEventId, reaction) =>
      channel.published.push({ clientEventId, reaction }),
    deactivate: () => {},
  };
};

const loadLiveState = vi.fn(async () => emptySnapshot);

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
const renderReactions = () =>
  renderHook(
    () =>
      useSessionReactions({
        createClientEventId: () => {
          sequence += 1;
          return `r-${sequence}`;
        },
        random: () => 0.5,
      }),
    { wrapper },
  );

const connect = async () => {
  await act(async () => {
    channel.handlers?.onStateChange("connected");
  });
};

const receive = async (eventId: string, reaction: unknown) => {
  const event: SessionEventEnvelope = {
    eventId,
    clientEventId: null,
    type: "REACTION",
    sender: { identity: "p-22", displayName: "김민수", role: "STUDENT" },
    occurredOffsetMs: 1_000,
    deliveredAt: "2026-07-30T09:00:01Z",
    payload: { reaction },
  };
  await act(async () => {
    channel.handlers?.onEvent(event);
  });
};

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  auth.accessToken = "test-access-token";
  channel.handlers = null;
  channel.published = [];
  sequence = 0;
  loadLiveState.mockClear();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useSessionReactions", () => {
  it("받은 반응을 이모지로 띄우고 애니메이션이 끝나면 지운다", async () => {
    const { result } = renderReactions();
    await connect();

    await receive("5001", "CLAP");

    expect(result.current.reactions).toEqual([{ key: "5001", emoji: "👏", left: 50 }]);

    await act(async () => {
      vi.advanceTimersByTime(REACTION_FLOAT_MS);
    });
    expect(result.current.reactions).toEqual([]);
  });

  /** 배포 시점이 어긋나면 이 화면이 모르는 종류가 먼저 올 수 있다. 화면이 죽지 않고 그냥 건너뛴다. */
  it("모르는 종류는 그리지 않는다", async () => {
    const { result } = renderReactions();
    await connect();

    await receive("5001", "TROPHY");
    await receive("5002", 7);

    expect(result.current.reactions).toEqual([]);
  });

  /** 서버가 재시도에 알림을 다시 보낼 수 있다. 타이머를 새로 걸면 먼저 건 타이머가 잊혀 영원히 남는다. */
  it("같은 이벤트가 두 번 와도 하나만 뜬다", async () => {
    const { result } = renderReactions();
    await connect();

    await receive("5001", "CLAP");
    await receive("5001", "CLAP");

    expect(result.current.reactions).toHaveLength(1);

    await act(async () => {
      vi.advanceTimersByTime(REACTION_FLOAT_MS);
    });
    expect(result.current.reactions).toEqual([]);
  });

  /**
   * 낙관적으로 그리지 않는다. 그러면 서버가 연타 제한으로 거절한 반응이 내 화면에만 뜨고,
   * echo 가 도착하면 같은 반응이 두 번 떠오른다.
   */
  it("보낸 직후에는 아무것도 뜨지 않는다", async () => {
    const { result } = renderReactions();
    await connect();

    act(() => result.current.react("HEART"));

    expect(channel.published).toEqual([{ clientEventId: "r-1", reaction: "HEART" }]);
    expect(result.current.reactions).toEqual([]);
  });

  it("연결 전에는 보내지 않는다", () => {
    const { result } = renderReactions();

    expect(result.current.canReact).toBe(false);
    act(() => result.current.react("LIKE"));
    expect(channel.published).toEqual([]);
  });
});
