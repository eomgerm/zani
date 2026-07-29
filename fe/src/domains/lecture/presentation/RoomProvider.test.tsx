import { act, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { RoomEvent } from "livekit-client";
import { describe, expect, it, vi } from "vitest";

import type { MediaToken } from "../infrastructure/mediaTokenApi";
import type { LiveKitRoomFactory } from "../infrastructure/liveKitRoom";
import { RoomProvider, useRoomConnection } from "./RoomProvider";

const { authState } = vi.hoisted(() => ({
  authState: { accessToken: "test-access-token" as string | null },
}));

// 미디어 토큰 발급은 Bearer Access Token 이 필요하다. 인증 컨텍스트 전체를 띄우지 않고 토큰만 흉내 낸다.
vi.mock("@/domains/auth", () => ({
  useAuth: () => authState,
}));


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
  requestToken?: (
    sessionId: string,
    accessToken: string,
    signal?: AbortSignal,
  ) => Promise<MediaToken>;
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
        roomFactory={roomFactory as unknown as LiveKitRoomFactory}
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
  /**
   * 강의실 경로는 인증 가드 밖이라, 방 안에서 새로고침하면 세션 복원이 끝나기 전에 연결을 시도한다.
   * 그때 멈춘 연결이 토큰이 들어온 뒤에도 그대로면 강의실은 영구히 error 로 남는다.
   */
  it("세션 복원이 끝나 토큰이 들어오면 멈춘 연결을 다시 시도한다", async () => {
    authState.accessToken = null;
    const room = createFakeRoom();
    const roomFactory = vi.fn(() => room);
    const requestToken = vi.fn().mockResolvedValue(token);
    // 같은 엘리먼트 객체를 다시 넘기면 React 가 재조정을 건너뛴다. 매번 새로 만든다.
    const tree = () => (
      <RoomProvider
        sessionId="55"
        requestToken={requestToken}
        roomFactory={roomFactory as unknown as LiveKitRoomFactory}
      >
        <Probe />
      </RoomProvider>
    );
    const { rerender } = render(tree());
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("error"));
    expect(requestToken).not.toHaveBeenCalled();

    authState.accessToken = "test-access-token";
    rerender(tree());

    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("connected"));
    expect(requestToken).toHaveBeenCalledWith("55", "test-access-token", expect.anything());
  });

  /** 토큰 갱신 실패로 로그아웃돼도 진행 중인 수업을 끊지 않는다. LiveKit 토큰은 따로라 연결은 살아 있다. */
  it("토큰이 사라져도 이미 붙은 연결을 끊지 않는다", async () => {
    const room = createFakeRoom();
    const roomFactory = vi.fn(() => room);
    // requestToken 은 연결 키의 일부다. 팩토리 안에서 새로 만들면 키가 바뀌어 재연결이 일어난다.
    const requestToken = vi.fn().mockResolvedValue(token);
    const tree = () => (
      <RoomProvider
        sessionId="55"
        requestToken={requestToken}
        roomFactory={roomFactory as unknown as LiveKitRoomFactory}
      >
        <Probe />
      </RoomProvider>
    );
    const { rerender } = render(tree());
    await waitFor(() => expect(screen.getByTestId("state")).toHaveTextContent("connected"));

    authState.accessToken = null;
    rerender(tree());

    expect(screen.getByTestId("state")).toHaveTextContent("connected");
    expect(roomFactory).toHaveBeenCalledTimes(1);
  });
});
