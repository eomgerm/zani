import { renderHook, act } from "@testing-library/react";
import { RoomEvent } from "livekit-client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useRoomMediaControls } from "./useRoomMediaControls";

class FakeLocalParticipant {
  isMicrophoneEnabled = true;
  isCameraEnabled = true;
  microphoneFailure: Error | null = null;

  setMicrophoneEnabled(enabled: boolean) {
    if (this.microphoneFailure) {
      return Promise.reject(this.microphoneFailure);
    }
    this.isMicrophoneEnabled = enabled;
    return Promise.resolve();
  }

  setCameraEnabled(enabled: boolean) {
    this.isCameraEnabled = enabled;
    return Promise.resolve();
  }
}

class FakeRoom {
  localParticipant: FakeLocalParticipant | null;
  private handlers = new Map<string, Set<() => void>>();

  constructor(local: FakeLocalParticipant | null) {
    this.localParticipant = local;
  }

  on(event: string, handler: () => void) {
    if (!this.handlers.has(event)) this.handlers.set(event, new Set());
    this.handlers.get(event)!.add(handler);
    return this;
  }

  off(event: string, handler: () => void) {
    this.handlers.get(event)?.delete(handler);
    return this;
  }

  emit(event: RoomEvent) {
    this.handlers.get(event)?.forEach((handler) => handler());
  }

  handlerCount() {
    let count = 0;
    this.handlers.forEach((set) => (count += set.size));
    return count;
  }
}

const hoisted = vi.hoisted(() => ({ room: null as FakeRoom | null }));

vi.mock("./RoomProvider", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./RoomProvider")>();
  return {
    ...actual,
    useRoomConnection: () => ({
      room: hoisted.room,
      connectionState: hoisted.room ? "connected" : "connecting",
      error: null,
      retry: () => {},
    }),
  };
});

const connectedRoom = () => {
  const room = new FakeRoom(new FakeLocalParticipant());
  hoisted.room = room;
  return room;
};

beforeEach(() => {
  hoisted.room = null;
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useRoomMediaControls", () => {
  it("mirrors the local participant publish state once connected", () => {
    const room = connectedRoom();
    room.localParticipant!.isCameraEnabled = false;

    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    expect(result.current.ready).toBe(true);
    expect(result.current.microphoneEnabled).toBe(true);
    expect(result.current.cameraEnabled).toBe(false);
  });

  it("is not ready while the room is still connecting", () => {
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    expect(result.current.ready).toBe(false);
    expect(result.current.microphoneEnabled).toBe(false);
  });

  it("stops publishing the microphone when toggled off", async () => {
    const room = connectedRoom();
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    await act(async () => result.current.toggleMicrophone());

    expect(room.localParticipant!.isMicrophoneEnabled).toBe(false);
    expect(result.current.microphoneEnabled).toBe(false);
  });

  it("publishes the camera again when toggled back on", async () => {
    const room = connectedRoom();
    room.localParticipant!.isCameraEnabled = false;
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    await act(async () => result.current.toggleCamera());

    expect(room.localParticipant!.isCameraEnabled).toBe(true);
    expect(result.current.cameraEnabled).toBe(true);
  });

  it("reports an error when the device cannot be published", async () => {
    const room = connectedRoom();
    room.localParticipant!.microphoneFailure = new Error("device busy");
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    await act(async () => result.current.toggleMicrophone());

    expect(result.current.mediaError).not.toBeNull();
    expect(result.current.microphoneEnabled).toBe(true);
  });

  it("clears the error after the next successful toggle", async () => {
    const room = connectedRoom();
    room.localParticipant!.microphoneFailure = new Error("device busy");
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));
    await act(async () => result.current.toggleMicrophone());

    room.localParticipant!.microphoneFailure = null;
    await act(async () => result.current.toggleMicrophone());

    expect(result.current.mediaError).toBeNull();
    expect(result.current.microphoneEnabled).toBe(false);
  });

  it("re-reads the publish state when a track is muted elsewhere", () => {
    const room = connectedRoom();
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    act(() => {
      room.localParticipant!.isMicrophoneEnabled = false;
      room.emit(RoomEvent.TrackMuted);
    });

    expect(result.current.microphoneEnabled).toBe(false);
  });

  it("ignores toggles while no local participant exists", async () => {
    hoisted.room = new FakeRoom(null);
    const { result } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));

    await act(async () => result.current.toggleCamera());

    expect(result.current.mediaError).toBeNull();
    expect(result.current.ready).toBe(false);
  });

  it("removes every room listener on unmount", () => {
    const room = connectedRoom();
    const { unmount } = renderHook(() => useRoomMediaControls());
    act(() => vi.advanceTimersByTime(0));
    expect(room.handlerCount()).toBeGreaterThan(0);

    unmount();

    expect(room.handlerCount()).toBe(0);
  });
});
