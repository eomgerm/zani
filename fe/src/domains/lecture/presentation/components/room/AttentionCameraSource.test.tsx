import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

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

  constructor(videoTrack?: FakeVideoTrack) {
    this.publication = videoTrack ? { videoTrack } : undefined;
  }

  localParticipant = {
    getTrackPublication: (source: string) => (source === "camera" ? this.publication : undefined),
  };

  on() {
    return this;
  }

  off() {
    return this;
  }
}

// 로컬 카메라 훅은 진짜를 쓴다. 이 테스트가 지키려는 것이 "요소가 실제로 트랙에 연결되는가" 다.
const hoisted = vi.hoisted(() => ({ room: null as FakeRoom | null }));
vi.mock("../../RoomProvider", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../RoomProvider")>();
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

// 판정 엔진 자체는 attention 도메인 테스트가 검증한다. 여기서는 어떤 카메라 상태를 넘기는지만 본다.
const attention = vi.hoisted(() => ({ cameras: [] as string[] }));
vi.mock("@/domains/attention", () => ({
  useAttentionDetection: (options: { camera: string }) => {
    attention.cameras.push(options.camera);
    return { status: "measuring", prediction: null };
  },
}));

import { AttentionCameraSource } from "./AttentionCameraSource";

/** 판정 훅이 마지막으로 받은 카메라 상태. */
const lastCamera = () => attention.cameras.at(-1);

/** 지연시켜 둔 초기 동기화를 흘려보낸다(트랙 연결이 여기서 일어난다). */
const flushInitialSync = () => act(() => vi.advanceTimersByTime(0));

beforeEach(() => {
  vi.useFakeTimers();
  hoisted.room = new FakeRoom(new FakeVideoTrack());
  attention.cameras = [];
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("AttentionCameraSource", () => {
  it("binds the hidden video element to the published camera track", () => {
    const track = new FakeVideoTrack();
    hoisted.room = new FakeRoom(track);

    render(<AttentionCameraSource active />);
    flushInitialSync();

    // ref 가 요소에 걸려 있지 않으면 판정이 조용히 멈춘다. 실제 연결을 확인한다.
    expect(track.attached).toEqual([screen.getByTestId("attention-camera-source")]);
  });

  it("keeps the video element rendered instead of hiding it from the compositor", () => {
    render(<AttentionCameraSource active />);

    // display:none 이면 브라우저가 프레임 갱신을 멈춘다.
    expect(screen.getByTestId("attention-camera-source").className).not.toContain("hidden");
  });

  it("runs detection once the camera track is attached", () => {
    render(<AttentionCameraSource active />);
    flushInitialSync();

    expect(lastCamera()).toBe("on");
  });

  it("stops detection while the room is not usable or the camera is off", () => {
    render(<AttentionCameraSource active={false} />);
    flushInitialSync();

    expect(lastCamera()).toBe("off");
  });

  it("stops detection while no camera track is published", () => {
    hoisted.room = new FakeRoom();

    render(<AttentionCameraSource active />);
    flushInitialSync();

    expect(lastCamera()).toBe("off");
  });

  it("reports a denied camera apart from a camera the student turned off", () => {
    render(<AttentionCameraSource active denied />);
    flushInitialSync();

    expect(lastCamera()).toBe("denied");
  });

  it("releases the camera track when the room screen unmounts", () => {
    const track = new FakeVideoTrack();
    hoisted.room = new FakeRoom(track);
    const { unmount } = render(<AttentionCameraSource active />);
    flushInitialSync();

    unmount();

    expect(track.detached).toEqual(track.attached);
  });
});
