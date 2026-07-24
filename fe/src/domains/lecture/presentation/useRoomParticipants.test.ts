import { renderHook, act } from "@testing-library/react";
import { RoomEvent } from "livekit-client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useRoomParticipants } from "./useRoomParticipants";

type FakeParticipant = {
  identity: string;
  name: string;
  attributes: Record<string, string>;
  isCameraEnabled: boolean;
  isMicrophoneEnabled: boolean;
};

const participant = (
  identity: string,
  overrides: Partial<FakeParticipant> = {},
): FakeParticipant => ({
  identity,
  name: identity,
  attributes: {},
  isCameraEnabled: true,
  isMicrophoneEnabled: true,
  ...overrides,
});

class FakeRoom {
  localParticipant: FakeParticipant | null;
  remoteParticipants = new Map<string, FakeParticipant>();
  private handlers = new Map<string, Set<() => void>>();

  constructor(local: FakeParticipant | null) {
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
    this.handlers.get(event)?.forEach((h) => h());
  }

  handlerCount() {
    let n = 0;
    this.handlers.forEach((set) => (n += set.size));
    return n;
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

beforeEach(() => {
  hoisted.room = null;
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useRoomParticipants", () => {
  it("로컬+원격 참가자를 타일 데이터로 스냅샷하고 localParticipantId를 노출한다", () => {
    const room = new FakeRoom(participant("host", { attributes: { role: "INSTRUCTOR" } }));
    room.remoteParticipants.set("s1", participant("s1", { name: "학생1" }));
    hoisted.room = room;

    const { result } = renderHook(() => useRoomParticipants());
    act(() => {
      vi.advanceTimersByTime(0);
    });

    expect(result.current.participants).toHaveLength(2);
    expect(result.current.localParticipantId).toBe("host");
    const host = result.current.participants.find((p) => p.id === "host");
    expect(host?.role).toBe("instructor");
    const s1 = result.current.participants.find((p) => p.id === "s1");
    expect(s1?.role).toBe("student");
    expect(s1?.name).toBe("학생1");
  });

  it("ParticipantConnected/Disconnected로 타일이 추가·제거된다", () => {
    const room = new FakeRoom(participant("host"));
    hoisted.room = room;
    const { result } = renderHook(() => useRoomParticipants());
    act(() => vi.advanceTimersByTime(0));
    expect(result.current.participants).toHaveLength(1);

    act(() => {
      room.remoteParticipants.set("s1", participant("s1"));
      room.emit(RoomEvent.ParticipantConnected);
    });
    expect(result.current.participants).toHaveLength(2);

    act(() => {
      room.remoteParticipants.delete("s1");
      room.emit(RoomEvent.ParticipantDisconnected);
    });
    expect(result.current.participants).toHaveLength(1);
  });

  it("TrackMuted/Unmuted로 카메라·마이크 상태가 갱신된다", () => {
    const remote = participant("s1");
    const room = new FakeRoom(participant("host"));
    room.remoteParticipants.set("s1", remote);
    hoisted.room = room;
    const { result } = renderHook(() => useRoomParticipants());
    act(() => vi.advanceTimersByTime(0));
    expect(result.current.participants.find((p) => p.id === "s1")?.cameraEnabled).toBe(true);

    act(() => {
      remote.isCameraEnabled = false;
      room.emit(RoomEvent.TrackMuted);
    });
    expect(result.current.participants.find((p) => p.id === "s1")?.cameraEnabled).toBe(false);
  });

  it("언마운트 시 모든 리스너를 해제한다", () => {
    const room = new FakeRoom(participant("host"));
    hoisted.room = room;
    const { unmount } = renderHook(() => useRoomParticipants());
    act(() => vi.advanceTimersByTime(0));
    expect(room.handlerCount()).toBeGreaterThan(0);

    unmount();
    expect(room.handlerCount()).toBe(0);
  });

  it("room이 없으면 빈 목록과 null id를 반환한다", () => {
    hoisted.room = null;
    const { result } = renderHook(() => useRoomParticipants());
    act(() => vi.advanceTimersByTime(0));
    expect(result.current.participants).toEqual([]);
    expect(result.current.localParticipantId).toBeNull();
  });
});
