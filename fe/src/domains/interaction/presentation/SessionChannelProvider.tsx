"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";

import { useAuth } from "@/domains/auth";
import {
  fetchLiveState,
  type LiveStateFetcher,
  type LiveStateSnapshot,
} from "../infrastructure/liveStateApi";
import {
  createSessionChannel,
  type SessionChannel,
  type SessionChannelFactory,
  type SessionChannelState,
} from "../infrastructure/sessionChannel";
import type {
  SessionEventEnvelope,
  SessionEventRejection,
} from "../infrastructure/sessionEvent";

type EventListener = (event: SessionEventEnvelope) => void;
type RejectionListener = (rejection: SessionEventRejection) => void;

export interface SessionChannelContextValue {
  readonly state: SessionChannelState;
  /**
   * 재연결 직후의 현재 상태. 연결마다 새로 받으므로 재연결하면 새 객체가 온다 — 소비자는 이 값이
   * 바뀌면 이력을 다시 맞춘다.
   */
  readonly snapshot: LiveStateSnapshot | null;
  publishChat: (clientEventId: string, content: string) => void;
  publishHand: (clientEventId: string, raised: boolean) => void;
  publishReaction: (clientEventId: string, reaction: string) => void;
  /** 업무 이벤트 구독. 정리 함수를 돌려준다. 65~66 이 같은 방식으로 붙는다. */
  addEventListener: (listener: EventListener) => () => void;
  /** 내 전송이 거절됐다는 통지 구독. */
  addRejectionListener: (listener: RejectionListener) => () => void;
}

export interface SessionChannelProviderProps {
  readonly sessionId: string;
  readonly children: ReactNode;
  channelFactory?: SessionChannelFactory;
  loadLiveState?: LiveStateFetcher;
}

const SessionChannelContext = createContext<SessionChannelContextValue | null>(null);

export function SessionChannelProvider({
  sessionId,
  children,
  channelFactory = createSessionChannel,
  loadLiveState = fetchLiveState,
}: SessionChannelProviderProps) {
  const { accessToken } = useAuth();
  const [state, setState] = useState<SessionChannelState>("connecting");
  const [snapshot, setSnapshot] = useState<LiveStateSnapshot | null>(null);
  /** 연결이 성립한 횟수. 재연결마다 늘어나 스냅샷을 다시 받게 한다. */
  const [connectedCount, setConnectedCount] = useState(0);

  // 토큰 값 자체를 연결 키에 넣지 않는다. 넣으면 주기적인 토큰 갱신마다 채널이 재연결된다.
  // RoomProvider 와 같은 이유·같은 방식이다.
  //
  // 대신 채널에는 **값이 아니라 읽는 함수**를 넘긴다. 자동 재연결은 채널을 만든 지 한참 뒤에
  // 일어날 수 있어서, 그때 이 ref 가 들고 있는 최신 토큰이 실려야 한다.
  const accessTokenRef = useRef(accessToken);
  useEffect(() => {
    accessTokenRef.current = accessToken;
  }, [accessToken]);
  const getAccessToken = useCallback(() => accessTokenRef.current, []);

  // 토큰을 확보한 적이 있는지를 나타내는 래치. 강의실 경로는 인증 가드 밖이라 방 안에서 새로고침하면
  // 세션 복원이 끝나기 전에 연결을 시도한다. false→true 로만 바뀌어야 한다 — 반대도 허용하면 토큰
  // 갱신 실패가 진행 중인 수업의 채팅까지 끊는다.
  const [tokenAvailable, setTokenAvailable] = useState(accessToken !== null);
  useEffect(() => {
    if (accessToken !== null && !tokenAvailable) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setTokenAvailable(true);
    }
  }, [accessToken, tokenAvailable]);

  const eventListeners = useRef(new Set<EventListener>());
  const rejectionListeners = useRef(new Set<RejectionListener>());
  const channelRef = useRef<SessionChannel | null>(null);

  useEffect(() => {
    if (!tokenAvailable) return;

    let isCurrent = true;
    const channel = channelFactory({
      sessionId,
      getAccessToken,
      handlers: {
        // 핸들러는 채널 수명 동안 고정이라 목록을 ref 로 읽는다. 구독이 늘고 줄어도 채널을 다시 만들지 않는다.
        onEvent: (event) => {
          if (!isCurrent) return;
          eventListeners.current.forEach((listener) => listener(event));
        },
        onRejection: (rejection) => {
          if (!isCurrent) return;
          rejectionListeners.current.forEach((listener) => listener(rejection));
        },
        onStateChange: (next) => {
          if (!isCurrent) return;
          setState(next);
          // 채널 팩토리는 주제 구독을 끝낸 뒤에 connected 를 알린다. 그래서 이 시점부터 스냅샷을
          // 받아도 두 호출 사이의 메시지를 놓치지 않는다.
          if (next === "connected") setConnectedCount((count) => count + 1);
        },
      },
    });
    channelRef.current = channel;

    return () => {
      isCurrent = false;
      channelRef.current = null;
      channel.deactivate();
    };
  }, [sessionId, channelFactory, tokenAvailable, getAccessToken]);

  useEffect(() => {
    if (connectedCount === 0) return;
    const currentAccessToken = accessTokenRef.current;
    if (currentAccessToken === null) return;

    let isCurrent = true;
    const abortController = new AbortController();
    void loadLiveState(sessionId, currentAccessToken, abortController.signal)
      .then((loaded) => {
        if (isCurrent) setSnapshot(loaded);
      })
      .catch(() => {
        // 스냅샷을 못 받아도 실시간 채팅은 이어진다. 이력만 비어 있게 두고 다음 재연결에서 다시 받는다.
      });

    return () => {
      isCurrent = false;
      abortController.abort();
    };
  }, [connectedCount, sessionId, loadLiveState]);

  const addEventListener = useCallback((listener: EventListener) => {
    eventListeners.current.add(listener);
    return () => {
      eventListeners.current.delete(listener);
    };
  }, []);

  const addRejectionListener = useCallback((listener: RejectionListener) => {
    rejectionListeners.current.add(listener);
    return () => {
      rejectionListeners.current.delete(listener);
    };
  }, []);

  const publishChat = useCallback((clientEventId: string, content: string) => {
    channelRef.current?.publishChat(clientEventId, content);
  }, []);

  const publishHand = useCallback((clientEventId: string, raised: boolean) => {
    channelRef.current?.publishHand(clientEventId, raised);
  }, []);

  const publishReaction = useCallback((clientEventId: string, reaction: string) => {
    channelRef.current?.publishReaction(clientEventId, reaction);
  }, []);

  const value = useMemo<SessionChannelContextValue>(
    () => ({
      state,
      snapshot,
      publishChat,
      publishHand,
      publishReaction,
      addEventListener,
      addRejectionListener,
    }),
    [
      state,
      snapshot,
      publishChat,
      publishHand,
      publishReaction,
      addEventListener,
      addRejectionListener,
    ],
  );

  return <SessionChannelContext.Provider value={value}>{children}</SessionChannelContext.Provider>;
}

export function useSessionChannel(): SessionChannelContextValue {
  const context = useContext(SessionChannelContext);
  if (context === null) {
    throw new Error("useSessionChannel must be used within a SessionChannelProvider.");
  }

  return context;
}
