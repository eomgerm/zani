"use client";

import { createContext, useContext, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import type { Room } from "livekit-client";

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
};

const connectionErrorMessage = (error: unknown) =>
  error instanceof Error && error.message
    ? error.message
    : "실시간 강의 연결에 실패했습니다.";

export function RoomProvider({
  sessionId,
  children,
  requestToken = requestMediaToken,
  roomFactory = createLiveKitRoom,
}: RoomProviderProps) {
  const [attempt, setAttempt] = useState(0);
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

    const connect = async () => {
      try {
        const mediaToken = await connectionKey.requestToken(
          connectionKey.sessionId,
          abortController.signal,
        );
        if (!isCurrent) return;

        await room.connect(mediaToken.liveKitUrl, mediaToken.accessToken);
        if (!isCurrent) return;

        setConnection({ room, connectionState: "connected", error: null, key: connectionKey });
      } catch (error) {
        if (!isCurrent) return;

        setConnection({
          room: null,
          connectionState: "error",
          error: connectionErrorMessage(error),
          key: connectionKey,
        });
      }
    };

    void connect();

    return () => {
      isCurrent = false;
      abortController.abort();
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
