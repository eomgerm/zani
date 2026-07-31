import { renderHook, act } from "@testing-library/react";
import { RoomEvent, Track } from "livekit-client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useRemoteAudio } from "./useRemoteAudio";

class FakeAudioTrack {
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
    public audioTrack?: FakeAudioTrack,
  ) {}

  get trackPublications() {
    const publications = new Map<string, { kind: Track.Kind; audioTrack?: FakeAudioTrack }>();
    if (this.audioTrack) {
      publications.set("mic", { kind: Track.Kind.Audio, audioTrack: this.audioTrack });
    }
    return publications;
  }
}

class FakeRoom {
  remoteParticipants = new Map<string, FakeParticipant>();
  canPlaybackAudio = true;
  startAudio = vi.fn().mockResolvedValue(undefined);
  private handlers = new Map<string, Set<() => void>>();

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

/** 초기 동기화는 setTimeout(0) 으로 미뤄져 있다. */
const settle = async () => {
  await act(async () => {
    vi.advanceTimersByTime(1);
  });
};

const audioCount = () => document.querySelectorAll("audio").length;

let room: FakeRoom;

beforeEach(() => {
  vi.useFakeTimers();
  room = new FakeRoom();
});

afterEach(() => {
  vi.useRealTimers();
  document.body.innerHTML = "";
});

describe("useRemoteAudio", () => {
  /** 이 훅의 존재 이유. 원격 오디오를 붙이지 않으면 마이크를 켜도 서로의 소리가 나지 않는다. */
  it("원격 참가자의 오디오를 요소에 붙여 재생한다", async () => {
    const track = new FakeAudioTrack();
    room.addRemote(new FakeParticipant("p-other", track));
    renderHook(() => useRemoteAudio(room as never));

    await settle();

    expect(track.attached).toHaveLength(1);
    expect(audioCount()).toBe(1);
  });

  it("트랙이 붙은 뒤 이벤트가 오면 붙인다", async () => {
    const late = new FakeParticipant("p-late");
    room.addRemote(late);
    renderHook(() => useRemoteAudio(room as never));
    await settle();
    expect(audioCount()).toBe(0);

    const track = new FakeAudioTrack();
    late.audioTrack = track;
    await act(async () => room.emit(RoomEvent.TrackSubscribed));

    expect(track.attached).toHaveLength(1);
    expect(audioCount()).toBe(1);
  });

  it("같은 트랙에 두 번 붙이지 않는다", async () => {
    const track = new FakeAudioTrack();
    room.addRemote(new FakeParticipant("p-other", track));
    renderHook(() => useRemoteAudio(room as never));
    await settle();

    await act(async () => room.emit(RoomEvent.TrackPublished));

    expect(track.attached).toHaveLength(1);
    expect(track.detached).toHaveLength(0);
  });

  it("참가자가 나가면 떼어내고 요소를 제거한다", async () => {
    const track = new FakeAudioTrack();
    room.addRemote(new FakeParticipant("p-other", track));
    renderHook(() => useRemoteAudio(room as never));
    await settle();

    room.remoteParticipants.delete("p-other");
    await act(async () => room.emit(RoomEvent.ParticipantDisconnected));

    expect(track.detached).toHaveLength(1);
    expect(audioCount()).toBe(0);
  });

  /** 자동재생이 막혀 있으면 풀어 본다. 이게 없으면 붙여도 소리가 나지 않을 수 있다. */
  it("재생이 막혀 있으면 startAudio 로 풀어 본다", async () => {
    room.canPlaybackAudio = false;
    room.addRemote(new FakeParticipant("p-other", new FakeAudioTrack()));
    renderHook(() => useRemoteAudio(room as never));

    await settle();

    expect(room.startAudio).toHaveBeenCalled();
  });

  it("붙일 원격 오디오가 없으면 startAudio 를 부르지 않는다", async () => {
    room.canPlaybackAudio = false;
    renderHook(() => useRemoteAudio(room as never));

    await settle();

    expect(room.startAudio).not.toHaveBeenCalled();
  });

  it("언마운트하면 리스너와 부착을 모두 정리한다", async () => {
    const track = new FakeAudioTrack();
    room.addRemote(new FakeParticipant("p-other", track));
    const { unmount } = renderHook(() => useRemoteAudio(room as never));
    await settle();
    expect(room.handlerCount()).toBeGreaterThan(0);

    unmount();

    expect(room.handlerCount()).toBe(0);
    expect(track.detached).toHaveLength(1);
    expect(audioCount()).toBe(0);
  });

  it("room 이 없으면 아무것도 하지 않는다", async () => {
    const { unmount } = renderHook(() => useRemoteAudio(null));
    await settle();

    expect(audioCount()).toBe(0);
    expect(() => unmount()).not.toThrow();
  });
});
