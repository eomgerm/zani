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
  // 토큰을 연결 키에 넣지 않는다. 넣으면 주기적인 토큰 갱신마다 강의실이 재연결되어 수업이 끊긴다.
  // 발급 요청 시점의 최신 값만 필요하므로 ref 로 따라가게 한다.
  const accessTokenRef = useRef(accessToken);
  useEffect(() => {
    accessTokenRef.current = accessToken;
  }, [accessToken]);

  const connectionKey = useMemo<ConnectionKey>(
    () => ({ attempt, requestToken, roomFactory, sessionId }),
    [attempt, requestToken, roomFactory, sessionId],
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
