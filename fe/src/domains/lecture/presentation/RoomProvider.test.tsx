import { act, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { RoomEvent } from "livekit-client";
import { describe, expect, it, vi } from "vitest";

import type { MediaToken } from "../infrastructure/mediaTokenApi";
import type { LiveKitRoomFactory } from "../infrastructure/liveKitRoom";
import { RoomProvider, useRoomConnection } from "./RoomProvider";

const token: MediaToken = {
  liveKitUrl: "wss://livekit.example.com",
  accessToken: "signed-token",
  roomName: "session-55",
  participantIdentity: "user-42",
  sessionExpiresAt: "2026-07-24T15:00:00Z",
  expiresAt: "2026-07-23T15:00:00Z",
};

type ObservedRoomEvent =
  | RoomEvent.Reconnecting
  | RoomEvent.Reconnected
  | RoomEvent.Disconnected;

type FakeRoom = {
  connect: ReturnType<typeof vi.fn>;
  disconnect: ReturnType<typeof vi.fn>;
  on: ReturnType<typeof vi.fn>;
  off: ReturnType<typeof vi.fn>;
  emit: (event: ObservedRoomEvent) => void;
};

const createFakeRoom = (): FakeRoom => {
  const listeners = new Map<ObservedRoomEvent, Set<() => void>>();
  const room = {
    connect: vi.fn().mockResolvedValue(undefined),
    disconnect: vi.fn(),
    on: vi.fn((event: ObservedRoomEvent, listener: () => void) => {
      const eventListeners = listeners.get(event) ?? new Set<() => void>();
      eventListeners.add(listener);
      listeners.set(event, eventListeners);
      return room;
    }),
    off: vi.fn((event: ObservedRoomEvent, listener: () => void) => {
      listeners.get(event)?.delete(listener);
      return room;
    }),
    emit: (event: ObservedRoomEvent) => {
      listeners.get(event)?.forEach((listener) => listener());
    },
  };

  return room as unknown as FakeRoom;
};

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
};

function Probe() {
  const { connectionState, error, retry } = useRoomConnection();
  return (
    <>
      <output data-testid="state">{connectionState}</output>
      <output data-testid="error">{error}</output>
      <button onClick={retry}>retry</button>
    </>
  );
}

function renderProvider({
  requestToken = vi.fn().mockResolvedValue(token),
  roomFactory = vi.fn(() => createFakeRoom()),
  children = <Probe />,
}: {
  requestToken?: (sessionId: string, signal?: AbortSignal) => Promise<MediaToken>;
  roomFactory?: ReturnType<typeof vi.fn>;
  children?: ReactNode;
} = {}) {
  return {
    requestToken,
    roomFactory,
    ...render(
      <RoomProvider
        sessionId="55"
        requestToken={requestToken}
        roomFactory={roomFactory as LiveKitRoomFactory}
      >
        {children}
      </RoomProvider>,
    ),
  };
}

describe("RoomProvider", () => {
  it("connects a newly created room with the requested media token", async () => {
    const room = createFakeRoom();
    const roomFactory = vi.fn(() => room);

    renderProvider({ roomFactory });

    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("connected"));

    expect(room.connect).toHaveBeenCalledWith("wss://livekit.example.com", "signed-token");
    expect(roomFactory).toHaveBeenCalledTimes(1);
  });

  it("disconnects the active room on unmount", async () => {
    const room = createFakeRoom();
    const { unmount } = renderProvider({ roomFactory: vi.fn(() => room) });

    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("connected"));
    unmount();

    expect(room.disconnect).toHaveBeenCalledTimes(1);
  });

  it("shows connecting while LiveKit is reconnecting", async () => {
    const room = createFakeRoom();
    renderProvider({ roomFactory: vi.fn(() => room) });

    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("connected"));
    act(() => room.emit(RoomEvent.Reconnecting));

    expect(screen.getByTestId("state")).toHaveTextContent("connecting");
  });

  it("shows connected when LiveKit reconnects", async () => {
    const room = createFakeRoom();
    renderProvider({ roomFactory: vi.fn(() => room) });

    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("connected"));
    act(() => room.emit(RoomEvent.Reconnecting));
    act(() => room.emit(RoomEvent.Reconnected));

    expect(screen.getByTestId("state")).toHaveTextContent("connected");
  });

  it("shows a safe error when LiveKit disconnects", async () => {
    const room = createFakeRoom();
    renderProvider({ roomFactory: vi.fn(() => room) });

    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("connected"));
    act(() => room.emit(RoomEvent.Disconnected));

    expect(screen.getByTestId("state")).toHaveTextContent("error");
    expect(screen.getByTestId("error")).toHaveTextContent(
      "실시간 강의 연결에 실패했습니다.",
    );
  });

  it("removes LiveKit lifecycle listeners during cleanup", async () => {
    const room = createFakeRoom();
    const { unmount } = renderProvider({ roomFactory: vi.fn(() => room) });

    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("connected"));
    unmount();

    expect(room.off).toHaveBeenCalledWith(RoomEvent.Reconnecting, expect.any(Function));
    expect(room.off).toHaveBeenCalledWith(RoomEvent.Reconnected, expect.any(Function));
    expect(room.off).toHaveBeenCalledWith(RoomEvent.Disconnected, expect.any(Function));
  });

  it("exposes the token request error message", async () => {
    renderProvider({ requestToken: vi.fn().mockRejectedValue(new Error("Token expired")) });

    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("error"));

    expect(screen.getByTestId("error")).toHaveTextContent("Token expired");
  });

  it("uses the safe fallback message for a non-Error token failure", async () => {
    renderProvider({ requestToken: vi.fn().mockRejectedValue("token failure") });

    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("error"));

    expect(screen.getByTestId("error")).toHaveTextContent("실시간 강의 연결에 실패했습니다.");
  });

  it("replaces a failed room when retrying and connects the new room", async () => {
    const failedRoom = createFakeRoom();
    failedRoom.connect.mockRejectedValueOnce(new Error("Room unavailable"));
    const replacementRoom = createFakeRoom();
    const roomFactory = vi.fn().mockReturnValueOnce(failedRoom).mockReturnValueOnce(replacementRoom);

    renderProvider({ roomFactory });

    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("error"));
    await act(async () => screen.getByRole("button", { name: "retry" }).click());
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("connected"));

    expect(failedRoom.disconnect).toHaveBeenCalledTimes(1);
    expect(replacementRoom.connect).toHaveBeenCalledWith(
      "wss://livekit.example.com",
      "signed-token",
    );
  });

  it("does not continue a token request after unmount", async () => {
    const pendingToken = deferred<MediaToken>();
    const room = createFakeRoom();
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const { unmount } = renderProvider({
      requestToken: vi.fn().mockReturnValue(pendingToken.promise),
      roomFactory: vi.fn(() => room),
    });

    unmount();
    await act(async () => pendingToken.resolve(token));

    expect(room.connect).not.toHaveBeenCalled();
    expect(consoleError).not.toHaveBeenCalled();
    consoleError.mockRestore();
  });
});
