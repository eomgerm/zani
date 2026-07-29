import { renderHook, act } from "@testing-library/react";
import { RoomEvent } from "livekit-client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useParticipantVideos } from "./useParticipantVideos";

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

class FakeParticipant {
  constructor(
    readonly identity: string,
    public videoTrack?: FakeVideoTrack,
  ) {}

  getTrackPublication(source: string) {
    return source === "camera" && this.videoTrack ? { videoTrack: this.videoTrack } : undefined;
  }
}

class FakeRoom {
  remoteParticipants = new Map<string, FakeParticipant>();
  private handlers = new Map<string, Set<() => void>>();

  constructor(public localParticipant: FakeParticipant) {}

  addRemote(participant: FakeParticipant) {
    this.remoteParticipants.set(participant.identity, participant);
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
      sessionExpiresAt: null,
      retry: () => {},
    }),
  };
});

const video = () => document.createElement("video");

/** 초기 동기화는 setTimeout(0) 으로 미뤄져 있다. */
const settle = async () => {
  await act(async () => {
    vi.advanceTimersByTime(1);
  });
};

let localTrack: FakeVideoTrack;
let remoteTrack: FakeVideoTrack;
let room: FakeRoom;

beforeEach(() => {
  vi.useFakeTimers();
  localTrack = new FakeVideoTrack();
  remoteTrack = new FakeVideoTrack();
  room = new FakeRoom(new FakeParticipant("p-me", localTrack));
  hoisted.room = room;
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useParticipantVideos", () => {
  /** 이 훅의 존재 이유. 원격 트랙을 붙이지 않으면 남의 타일이 영원히 아바타로만 남는다. */
  it("원격 참가자의 카메라도 요소에 붙인다", async () => {
    room.addRemote(new FakeParticipant("p-other", remoteTrack));
    const { result } = renderHook(() => useParticipantVideos());

    const mine = video();
    const theirs = video();
    act(() => {
      result.current.refFor("p-me")(mine);
      result.current.refFor("p-other")(theirs);
    });
    await settle();

    expect(localTrack.attached).toEqual([mine]);
    expect(remoteTrack.attached).toEqual([theirs]);
  });

  /** 함수 identity 가 렌더마다 바뀌면 React 가 ref 를 떼었다 붙여 화면이 깜빡인다. */
  it("같은 참가자에게는 같은 ref 함수를 돌려준다", () => {
    const { result } = renderHook(() => useParticipantVideos());

    expect(result.current.refFor("p-me")).toBe(result.current.refFor("p-me"));
    expect(result.current.refFor("p-me")).not.toBe(result.current.refFor("p-other"));
  });

  it("트랙이 붙기 전에 요소가 생겨도 이벤트가 오면 붙인다", async () => {
    const late = new FakeParticipant("p-late");
    room.addRemote(late);
    const { result } = renderHook(() => useParticipantVideos());

    const element = video();
    act(() => result.current.refFor("p-late")(element));
    await settle();
    expect(remoteTrack.attached).toEqual([]);

    late.videoTrack = remoteTrack;
    await act(async () => room.emit(RoomEvent.TrackSubscribed));

    expect(remoteTrack.attached).toEqual([element]);
  });

  it("같은 짝에 두 번 붙이지 않는다", async () => {
    const { result } = renderHook(() => useParticipantVideos());
    const element = video();
    act(() => result.current.refFor("p-me")(element));
    await settle();

    await act(async () => room.emit(RoomEvent.TrackMuted));
    await act(async () => room.emit(RoomEvent.TrackUnmuted));

    expect(localTrack.attached).toEqual([element]);
    expect(localTrack.detached).toEqual([]);
  });

  /** 타일이 사라졌는데 붙여 두면 트랙이 죽은 요소를 계속 물고 있다. */
  it("요소가 사라지면 떼어낸다", async () => {
    const { result } = renderHook(() => useParticipantVideos());
    const element = video();
    act(() => result.current.refFor("p-me")(element));
    await settle();

    act(() => result.current.refFor("p-me")(null));

    expect(localTrack.detached).toEqual([element]);
  });

  it("참가자가 나가면 떼어낸다", async () => {
    const other = new FakeParticipant("p-other", remoteTrack);
    room.addRemote(other);
    const { result } = renderHook(() => useParticipantVideos());
    const element = video();
    act(() => result.current.refFor("p-other")(element));
    await settle();

    room.remoteParticipants.delete("p-other");
    await act(async () => room.emit(RoomEvent.ParticipantDisconnected));

    expect(remoteTrack.detached).toEqual([element]);
  });

  it("언마운트하면 리스너와 부착을 모두 정리한다", async () => {
    const { result, unmount } = renderHook(() => useParticipantVideos());
    const element = video();
    act(() => result.current.refFor("p-me")(element));
    await settle();
    expect(room.handlerCount()).toBeGreaterThan(0);

    unmount();

    expect(room.handlerCount()).toBe(0);
    expect(localTrack.detached).toEqual([element]);
  });
});
