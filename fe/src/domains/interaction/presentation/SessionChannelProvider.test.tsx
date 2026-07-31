import { render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const auth = vi.hoisted(() => ({ accessToken: "token-1" as string | null }));
vi.mock("@/domains/auth", () => ({ useAuth: () => auth }));

import type { LiveStateSnapshot } from "../infrastructure/liveStateApi";
import type { SessionChannelFactory } from "../infrastructure/sessionChannel";
import { SessionChannelProvider } from "./SessionChannelProvider";

const emptySnapshot: LiveStateSnapshot = {
  participants: [],
  chatMessages: [],
  raisedHandIdentities: [],
};

const channel = {
  getAccessToken: null as (() => string | null) | null,
  created: 0,
  deactivated: 0,
};

const channelFactory: SessionChannelFactory = ({ getAccessToken }) => {
  channel.getAccessToken = getAccessToken;
  channel.created += 1;
  return {
    publishChat: () => {},
    deactivate: () => {
      channel.deactivated += 1;
    },
  };
};

const loadLiveState = vi.fn(async () => emptySnapshot);

const renderProvider = () =>
  render(
    <SessionChannelProvider
      sessionId="s1"
      channelFactory={channelFactory}
      loadLiveState={loadLiveState}
    >
      <div />
    </SessionChannelProvider>,
  );

beforeEach(() => {
  auth.accessToken = "token-1";
  channel.getAccessToken = null;
  channel.created = 0;
  channel.deactivated = 0;
  loadLiveState.mockClear();
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("SessionChannelProvider 토큰 전달", () => {
  it("값이 아니라 읽는 함수를 채널에 넘긴다", () => {
    renderProvider();

    expect(channel.getAccessToken).toBeTypeOf("function");
    expect(channel.getAccessToken?.()).toBe("token-1");
  });

  /**
   * 자동 재연결은 채널을 만든 지 한참 뒤에 일어난다. 그 사이 토큰이 갱신되는데(액세스 토큰 1시간,
   * 만료 60초 전 갱신), 생성 시점 값을 굳혀두면 재연결이 만료된 토큰을 보내 거절당하고 채팅이 죽는다.
   */
  it("토큰이 갱신되면 재연결 시점에 새 값을 읽는다", () => {
    const view = renderProvider();

    auth.accessToken = "token-2";
    view.rerender(
      <SessionChannelProvider
        sessionId="s1"
        channelFactory={channelFactory}
        loadLiveState={loadLiveState}
      >
        <div />
      </SessionChannelProvider>,
    );

    expect(channel.getAccessToken?.()).toBe("token-2");
  });

  /** 갱신마다 채널을 다시 만들면 수업 중 채팅이 주기적으로 끊긴다. */
  it("토큰이 갱신돼도 채널을 다시 만들지 않는다", () => {
    const view = renderProvider();
    expect(channel.created).toBe(1);

    auth.accessToken = "token-2";
    view.rerender(
      <SessionChannelProvider
        sessionId="s1"
        channelFactory={channelFactory}
        loadLiveState={loadLiveState}
      >
        <div />
      </SessionChannelProvider>,
    );

    expect(channel.created).toBe(1);
    expect(channel.deactivated).toBe(0);
  });

  it("언마운트하면 채널을 정리한다", () => {
    renderProvider().unmount();

    expect(channel.deactivated).toBe(1);
  });
});
