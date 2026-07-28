import { renderHook, act } from "@testing-library/react";
import { RoomEvent } from "livekit-client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useLocalCameraVideo } from "./useLocalCameraVideo";

class FakeVideoTrack {
  attached: HTMLMediaElement[] = [];
  detached: HTMLMediaElement[] = [];

  attach(element: HTMLMediaElement) {
    this.attached.push(element);
    return element;
  }

  detach(element: HTMLMediaElement) {
    this.detached.push(element);
    return element;
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

/** 훅이 반환한 ref 에 실제 video 요소를 연결한 뒤 재동기화를 트리거한다. */
const attachElement = (result: { current: ReturnType<typeof useLocalCameraVideo> }) => {
  const element = document.createElement("video");
  act(() => {
    result.current.videoRef.current = element;
  });
  return element;
};

/** 지연시켜 둔 초기 동기화를 흘려보낸다. */
const flushInitialSync = () => act(() => vi.advanceTimersByTime(0));

beforeEach(() => {
  vi.useFakeTimers();
  hoisted.room = null;
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useLocalCameraVideo", () => {
  it("attaches the published local camera track to the video element", () => {
    const track = new FakeVideoTrack();
    hoisted.room = new FakeRoom(track);

    const { result } = renderHook(() => useLocalCameraVideo());
    const element = attachElement(result);
    act(() => hoisted.room!.emit(RoomEvent.LocalTrackPublished));

    expect(track.attached).toEqual([element]);
    expect(result.current.attached).toBe(true);
  });

  it("attaches an already published track on mount without waiting for an event", () => {
    // 실제로는 room 연결이 끝난 뒤 화면이 뜨므로, 이벤트가 아니라 이 지연 동기화가 트랙을 붙인다.
    const track = new FakeVideoTrack();
    hoisted.room = new FakeRoom(track);

    const { result } = renderHook(() => useLocalCameraVideo());
    const element = attachElement(result);
    flushInitialSync();

    expect(track.attached).toEqual([element]);
    expect(result.current.attached).toBe(true);
  });

  it("re-attaches a newly published track after the camera is turned back on", () => {
    const first = new FakeVideoTrack();
    hoisted.room = new FakeRoom(first);
    const { result } = renderHook(() => useLocalCameraVideo());
    const element = attachElement(result);
    act(() => hoisted.room!.emit(RoomEvent.LocalTrackPublished));

    hoisted.room!.publication = undefined;
    act(() => hoisted.room!.emit(RoomEvent.LocalTrackUnpublished));
    const second = new FakeVideoTrack();
    hoisted.room!.publication = { videoTrack: second };
    act(() => hoisted.room!.emit(RoomEvent.LocalTrackPublished));

    expect(second.attached).toEqual([element]);
    expect(result.current.attached).toBe(true);
  });

  it("reports no attachment while the camera track is not published", () => {
    hoisted.room = new FakeRoom();

    const { result } = renderHook(() => useLocalCameraVideo());
    attachElement(result);
    act(() => hoisted.room!.emit(RoomEvent.LocalTrackUnpublished));

    expect(result.current.attached).toBe(false);
  });

  it("detaches when the camera track is unpublished", () => {
    const track = new FakeVideoTrack();
    hoisted.room = new FakeRoom(track);
    const { result } = renderHook(() => useLocalCameraVideo());
    const element = attachElement(result);
    act(() => hoisted.room!.emit(RoomEvent.LocalTrackPublished));

    hoisted.room!.publication = undefined;
    act(() => hoisted.room!.emit(RoomEvent.LocalTrackUnpublished));

    expect(track.detached).toEqual([element]);
    expect(result.current.attached).toBe(false);
  });

  it("attaches only once while the same track stays published", () => {
    const track = new FakeVideoTrack();
    hoisted.room = new FakeRoom(track);
    const { result } = renderHook(() => useLocalCameraVideo());
    attachElement(result);

    act(() => hoisted.room!.emit(RoomEvent.LocalTrackPublished));
    act(() => hoisted.room!.emit(RoomEvent.TrackMuted));
    act(() => hoisted.room!.emit(RoomEvent.TrackUnmuted));

    expect(track.attached).toHaveLength(1);
  });

  it("does nothing while the room is not connected", () => {
    const { result } = renderHook(() => useLocalCameraVideo());

    expect(result.current.attached).toBe(false);
  });

  it("detaches the track and removes every listener on unmount", () => {
    const track = new FakeVideoTrack();
    hoisted.room = new FakeRoom(track);
    const { result, unmount } = renderHook(() => useLocalCameraVideo());
    const element = attachElement(result);
    act(() => hoisted.room!.emit(RoomEvent.LocalTrackPublished));

    unmount();

    expect(track.detached).toEqual([element]);
    expect(hoisted.room!.handlerCount()).toBe(0);
  });
});
