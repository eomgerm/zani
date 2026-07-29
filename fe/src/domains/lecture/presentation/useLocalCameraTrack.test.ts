import { renderHook, act } from "@testing-library/react";
import { RoomEvent } from "livekit-client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useLocalCameraTrack } from "./useLocalCameraTrack";

function fakeMediaStreamTrack(label: string): MediaStreamTrack {
  return { label, readyState: "live", muted: false } as unknown as MediaStreamTrack;
}

class FakeVideoTrack {
  readonly mediaStreamTrack: MediaStreamTrack;

  constructor(label: string) {
    this.mediaStreamTrack = fakeMediaStreamTrack(label);
  }
}

class FakeRoom {
  publication: { videoTrack?: FakeVideoTrack } | undefined;
  private handlers = new Map<string, Set<() => void>>();

  constructor(videoTrack?: FakeVideoTrack) {
    this.publication = videoTrack ? { videoTrack } : undefined;
  }

  localParticipant = {
    getTrackPublication: (source: string) => (source === "camera" ? this.publication : undefined),
  };

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
      sessionExpiresAt: null,
      retry: () => {},
    }),
  };
});

/** 지연시켜 둔 초기 동기화를 흘려보낸다. */
const flushInitialSync = () => act(() => vi.advanceTimersByTime(0));

beforeEach(() => {
  vi.useFakeTimers();
  hoisted.room = null;
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useLocalCameraTrack", () => {
  it("exposes an already published camera track on mount without waiting for an event", () => {
    // 실제로는 room 연결이 끝난 뒤 화면이 뜨므로, 이벤트가 아니라 이 지연 동기화가 트랙을 찾는다.
    const track = new FakeVideoTrack("camera");
    hoisted.room = new FakeRoom(track);

    const { result } = renderHook(() => useLocalCameraTrack());
    flushInitialSync();

    expect(result.current.track).toBe(track.mediaStreamTrack);
  });

  it("exposes the camera track published after mount", () => {
    hoisted.room = new FakeRoom();
    const { result } = renderHook(() => useLocalCameraTrack());
    flushInitialSync();

    const track = new FakeVideoTrack("camera");
    hoisted.room.publication = { videoTrack: track };
    act(() => hoisted.room!.emit(RoomEvent.LocalTrackPublished));

    expect(result.current.track).toBe(track.mediaStreamTrack);
  });

  it("swaps to a newly published track after the camera is turned back on", () => {
    const first = new FakeVideoTrack("first");
    hoisted.room = new FakeRoom(first);
    const { result } = renderHook(() => useLocalCameraTrack());
    flushInitialSync();

    hoisted.room.publication = undefined;
    act(() => hoisted.room!.emit(RoomEvent.LocalTrackUnpublished));
    const second = new FakeVideoTrack("second");
    hoisted.room.publication = { videoTrack: second };
    act(() => hoisted.room!.emit(RoomEvent.LocalTrackPublished));

    expect(result.current.track).toBe(second.mediaStreamTrack);
  });

  it("reports no track while the camera is not published", () => {
    hoisted.room = new FakeRoom();

    const { result } = renderHook(() => useLocalCameraTrack());
    flushInitialSync();

    expect(result.current.track).toBeNull();
  });

  it("clears the track when the camera is unpublished", () => {
    const track = new FakeVideoTrack("camera");
    hoisted.room = new FakeRoom(track);
    const { result } = renderHook(() => useLocalCameraTrack());
    flushInitialSync();

    hoisted.room.publication = undefined;
    act(() => hoisted.room!.emit(RoomEvent.LocalTrackUnpublished));

    expect(result.current.track).toBeNull();
  });

  it("keeps the same track reference across repeated events", () => {
    // 판정 세션은 track 참조가 바뀌면 재시작한다. 같은 트랙에 대해 참조가 흔들리면 안 된다.
    const track = new FakeVideoTrack("camera");
    hoisted.room = new FakeRoom(track);
    const { result } = renderHook(() => useLocalCameraTrack());
    flushInitialSync();
    const first = result.current.track;

    act(() => hoisted.room!.emit(RoomEvent.TrackMuted));
    act(() => hoisted.room!.emit(RoomEvent.TrackUnmuted));
    act(() => hoisted.room!.emit(RoomEvent.ActiveDeviceChanged));

    expect(result.current.track).toBe(first);
  });

  it("reports no track while the room is not connected", () => {
    const { result } = renderHook(() => useLocalCameraTrack());
    flushInitialSync();

    expect(result.current.track).toBeNull();
  });

  it("removes every listener on unmount", () => {
    hoisted.room = new FakeRoom(new FakeVideoTrack("camera"));
    const { unmount } = renderHook(() => useLocalCameraTrack());
    flushInitialSync();

    unmount();

    expect(hoisted.room.handlerCount()).toBe(0);
  });
});
