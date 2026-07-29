import { act, cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

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

// 로컬 카메라 훅은 진짜를 쓴다. 이 테스트가 지키려는 것이 "판정이 실제 트랙을 받는가" 다.
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

// 판정 엔진 자체는 attention 도메인 테스트가 검증한다. 여기서는 무엇을 넘기는지만 본다.
const attention = vi.hoisted(() => ({
  calls: [] as Array<{ camera: string; track: MediaStreamTrack | null }>,
  status: "measuring" as string,
}));
vi.mock("@/domains/attention", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/domains/attention")>();
  return {
    ...actual,
    useAttentionDetection: (options: { camera: string; track: MediaStreamTrack | null }) => {
      attention.calls.push({ camera: options.camera, track: options.track });
      return { status: attention.status, prediction: null };
    },
  };
});

import { AttentionCameraSource } from "./AttentionCameraSource";

/** 판정 훅이 마지막으로 받은 인자. */
const lastCall = () => attention.calls.at(-1);

/** 지연시켜 둔 초기 동기화를 흘려보낸다(트랙을 여기서 찾는다). */
const flushInitialSync = () => act(() => vi.advanceTimersByTime(0));

beforeEach(() => {
  vi.useFakeTimers();
  hoisted.room = new FakeRoom(new FakeVideoTrack("camera"));
  attention.calls = [];
  attention.status = "measuring";
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("AttentionCameraSource 가용 상태 알림", () => {
  it("reports the analysis as running while detection works", () => {
    const onAvailabilityChange = vi.fn();

    render(<AttentionCameraSource active onAvailabilityChange={onAvailabilityChange} />);
    flushInitialSync();

    expect(onAvailabilityChange).toHaveBeenLastCalledWith("ACTIVE");
  });

  // 검출기 실패는 카메라 문제와 다르게 다뤄야 한다(§76 완료 조건).
  it("separates a detector failure from a paused camera", () => {
    attention.status = "unavailable";
    const onAvailabilityChange = vi.fn();

    render(<AttentionCameraSource active onAvailabilityChange={onAvailabilityChange} />);
    flushInitialSync();

    expect(onAvailabilityChange).toHaveBeenLastCalledWith("UNAVAILABLE");
  });

  it("reports a paused analysis when the camera gives no frames", () => {
    attention.status = "idle";
    const onAvailabilityChange = vi.fn();

    render(<AttentionCameraSource active={false} onAvailabilityChange={onAvailabilityChange} />);
    flushInitialSync();

    expect(onAvailabilityChange).toHaveBeenLastCalledWith("PAUSED");
  });

  // 얼굴이 안 잡히는 것은 분석이 도는 중이다. 알림이 흔들리면 상위 화면이 계속 다시 그려진다.
  it("keeps reporting ACTIVE while the face is not detected", () => {
    attention.status = "unmeasurable";
    const onAvailabilityChange = vi.fn();

    render(<AttentionCameraSource active onAvailabilityChange={onAvailabilityChange} />);
    flushInitialSync();

    expect(onAvailabilityChange).toHaveBeenLastCalledWith("ACTIVE");
  });
});

describe("AttentionCameraSource", () => {
  it("hands the published camera track to detection", () => {
    const track = new FakeVideoTrack("camera");
    hoisted.room = new FakeRoom(track);

    render(<AttentionCameraSource active />);
    flushInitialSync();

    expect(lastCall()?.track).toBe(track.mediaStreamTrack);
  });

  it("renders no element because frames are read from the track, not the DOM", () => {
    // 프레임은 Worker 가 트랙에서 직접 읽는다. 화면에 붙일 video 요소가 필요 없다.
    const { container } = render(<AttentionCameraSource active />);
    flushInitialSync();

    expect(container).toBeEmptyDOMElement();
  });

  it("runs detection once the camera track is published", () => {
    render(<AttentionCameraSource active />);
    flushInitialSync();

    expect(lastCall()?.camera).toBe("on");
  });

  it("stops detection while the room is not usable or the camera is off", () => {
    render(<AttentionCameraSource active={false} />);
    flushInitialSync();

    expect(lastCall()?.camera).toBe("off");
  });

  it("stops detection while no camera track is published", () => {
    hoisted.room = new FakeRoom();

    render(<AttentionCameraSource active />);
    flushInitialSync();

    expect(lastCall()?.camera).toBe("off");
    expect(lastCall()?.track).toBeNull();
  });

  it("reports a denied camera apart from a camera the student turned off", () => {
    render(<AttentionCameraSource active denied />);
    flushInitialSync();

    expect(lastCall()?.camera).toBe("denied");
  });
});
