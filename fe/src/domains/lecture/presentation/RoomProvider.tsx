"use client";

import { createContext, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { RoomEvent } from "livekit-client";
import type { Room } from "livekit-client";

import { useAuth } from "@/domains/auth";
import {
  createLiveKitRoom,
  type LiveKitRoomFactory,
} from "../infrastructure/liveKitRoom";
import {
  requestMediaToken,
  type MediaTokenRequester,
} from "../infrastructure/mediaTokenApi";

export type RoomConnectionState = "connecting" | "connected" | "error";

export type RoomConnectionContextValue = {
  room: Room | null;
  connectionState: RoomConnectionState;
  error: string | null;
  /** 서버가 알려준 수업 자동 종료 예정 시각(ISO-8601). 연결 전이거나 실패했으면 null. */
  sessionExpiresAt: string | null;
  retry: () => void;
};

export type RoomProviderProps = {
  sessionId: string;
  children: ReactNode;
  requestToken?: MediaTokenRequester;
  roomFactory?: LiveKitRoomFactory;
};

const RoomConnectionContext = createContext<RoomConnectionContextValue | null>(null);

type ConnectionKey = {
  attempt: number;
  requestToken: MediaTokenRequester;
  roomFactory: LiveKitRoomFactory;
  sessionId: string;
  /** 로그인 토큰을 한 번이라도 확보했는지. false→true 로만 바뀐다(아래 래치 설명 참고). */
  tokenAvailable: boolean;
};

type ConnectionSnapshot = Omit<RoomConnectionContextValue, "retry"> & {
  key: ConnectionKey | null;
};

const connectingSnapshot: Omit<RoomConnectionContextValue, "retry"> = {
  room: null,
  connectionState: "connecting",
  error: null,
  sessionExpiresAt: null,
};

const connectionFailureMessage = "실시간 강의 연결에 실패했습니다.";

const connectionErrorMessage = (error: unknown) =>
  error instanceof Error && error.message
    ? error.message
    : connectionFailureMessage;

export function RoomProvider({
  sessionId,
  children,
  requestToken = requestMediaToken,
  roomFactory = createLiveKitRoom,
}: RoomProviderProps) {
  const [attempt, setAttempt] = useState(0);
  const { accessToken } = useAuth();
  // 토큰 값 자체는 연결 키에 넣지 않는다. 넣으면 주기적인 토큰 갱신마다 강의실이 재연결되어 수업이 끊긴다.
  // 발급 요청 시점의 최신 값만 필요하므로 ref 로 따라가게 한다.
  const accessTokenRef = useRef(accessToken);
  useEffect(() => {
    accessTokenRef.current = accessToken;
  }, [accessToken]);

  // 토큰을 확보한 적이 있는지를 나타내는 래치. 강의실 경로는 인증 가드 밖이라, 방 안에서 새로고침하면
  // 세션 복원이 끝나기 전에 연결을 시도해 토큰이 없다는 이유로 멈춘다. 이 값이 false→true 로 바뀔 때
  // 연결 키가 달라져 자동으로 다시 시도한다.
  //
  // 한 방향으로만 바뀌는 게 중요하다. true→false 도 허용하면 토큰 갱신이 실패해 로그아웃될 때 진행 중인
  // 강의실 연결까지 끊는다 — LiveKit 토큰은 따로라 그때도 수업은 이어질 수 있어야 한다.
  const [tokenAvailable, setTokenAvailable] = useState(accessToken !== null);
  useEffect(() => {
    if (accessToken !== null && !tokenAvailable) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setTokenAvailable(true);
    }
  }, [accessToken, tokenAvailable]);

  const connectionKey = useMemo<ConnectionKey>(
    () => ({ attempt, requestToken, roomFactory, sessionId, tokenAvailable }),
    [attempt, requestToken, roomFactory, sessionId, tokenAvailable],
  );
  const [connection, setConnection] = useState<ConnectionSnapshot>({
    ...connectingSnapshot,
    key: null,
  });

  useEffect(() => {
    let isCurrent = true;
    const abortController = new AbortController();
    const room = connectionKey.roomFactory();
    // 재연결·종료 이벤트에서도 유지해야 하는 값이라 effect 스코프에 담아둔다.
    let sessionExpiresAt: string | null = null;
    const handleReconnecting = () => {
      if (!isCurrent) return;

      setConnection({
        room,
        connectionState: "connecting",
        error: null,
        sessionExpiresAt,
        key: connectionKey,
      });
    };
    const handleReconnected = () => {
      if (!isCurrent) return;

      setConnection({
        room,
        connectionState: "connected",
        error: null,
        sessionExpiresAt,
        key: connectionKey,
      });
    };
    const handleDisconnected = () => {
      if (!isCurrent) return;

      setConnection({
        room: null,
        connectionState: "error",
        error: connectionFailureMessage,
        sessionExpiresAt,
        key: connectionKey,
      });
    };

    const connect = async () => {
      try {
        const currentAccessToken = accessTokenRef.current;
        if (currentAccessToken === null) {
          // 로그인 없이는 미디어 토큰을 받을 수 없다. 401 을 유발하는 대신 여기서 멈춘다.
          handleDisconnected();
          return;
        }
        const mediaToken = await connectionKey.requestToken(
          connectionKey.sessionId,
          currentAccessToken,
          abortController.signal,
        );
        if (!isCurrent) return;

        sessionExpiresAt = mediaToken.sessionExpiresAt;
        await room.connect(mediaToken.liveKitUrl, mediaToken.accessToken);
        if (!isCurrent) return;

        room.on(RoomEvent.Reconnecting, handleReconnecting);
        room.on(RoomEvent.Reconnected, handleReconnected);
        room.on(RoomEvent.Disconnected, handleDisconnected);
        setConnection({
          room,
          connectionState: "connected",
          error: null,
          sessionExpiresAt,
          key: connectionKey,
        });
      } catch (error) {
        if (!isCurrent) return;

        setConnection({
          room: null,
          connectionState: "error",
          error: connectionErrorMessage(error),
          sessionExpiresAt,
          key: connectionKey,
        });
      }
    };

    void connect();

    return () => {
      isCurrent = false;
      abortController.abort();
      room.off(RoomEvent.Reconnecting, handleReconnecting);
      room.off(RoomEvent.Reconnected, handleReconnected);
      room.off(RoomEvent.Disconnected, handleDisconnected);
      room.disconnect();
    };
  }, [connectionKey]);

  const currentConnection = connection.key === connectionKey ? connection : connectingSnapshot;

  const value = useMemo<RoomConnectionContextValue>(
    () => ({ ...currentConnection, retry: () => setAttempt((current) => current + 1) }),
    [currentConnection],
  );

  return <RoomConnectionContext.Provider value={value}>{children}</RoomConnectionContext.Provider>;
}

export function useRoomConnection(): RoomConnectionContextValue {
  const context = useContext(RoomConnectionContext);
  if (context === null) {
    throw new Error("useRoomConnection must be used within a RoomProvider.");
  }

  return context;
}
