import { StrictMode, type ReactNode } from "react";
import { act, renderHook } from "@testing-library/react";
import { RoomEvent, Track, TrackEvent } from "livekit-client";
import type { Room } from "livekit-client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  RECORDER_AUDIO_BITS_PER_SECOND,
  RECORDER_TIMESLICE_MS,
  useInstructorAudioBuffer,
} from "./useInstructorAudioBuffer";

// ---- 가짜 WebM 바이트 -------------------------------------------------------
// 첫 dataavailable 조각 = [헤더 7바이트][Cluster ID+size+Timecode 8바이트][오디오]
const HEADER_BYTES = [0x1a, 0x45, 0xdf, 0xa3, 0x86, 0x81, 0x01];
const CLUSTER_BYTES = [0x1f, 0x43, 0xb6, 0x75, 0xff, 0xe7, 0x81, 0x00];
const HEADER_SIZE = HEADER_BYTES.length;

const firstChunkBytes = (audioSize: number) =>
  new Uint8Array([...HEADER_BYTES, ...CLUSTER_BYTES, ...new Array<number>(audioSize).fill(0)]);
const firstChunkBodySize = (audioSize: number) => CLUSTER_BYTES.length + audioSize;

/** 헤더 확정 이후에 오는 일반 오디오 조각. 내용은 크기  산술에만 쓴다. */
const payload = (size: number) => new Uint8Array(size);

// ---- 가짜 LiveKit ----------------------------------------------------------
class FakeEmitter {
  private handlers = new Map<string, Set<(...args: unknown[]) => void>>();

  on(event: string, handler: (...args: unknown[]) => void) {
    if (!this.handlers.has(event)) this.handlers.set(event, new Set());
    this.handlers.get(event)?.add(handler);
    return this;
  }

  off(event: string, handler: (...args: unknown[]) => void) {
    this.handlers.get(event)?.delete(handler);
    return this;
  }

  emit(event: string, ...args: unknown[]) {
    // 실제 EventEmitter처럼 emit 시작 시점의 스냅샷을 순회한다. Set.forEach를 그대로 쓰면
    // 핸들러가 자신을 off→on 재등록할 때(재시작 흐름) 같은 emit에서 다시 방문돼 무한 루프가 된다.
    const handlers = this.handlers.get(event);
    if (!handlers) return;
    [...handlers].forEach((handler) => handler(...args));
  }

  handlerCount() {
    let count = 0;
    this.handlers.forEach((set) => {
      count += set.size;
    });
    return count;
  }
}

class FakeTrack extends FakeEmitter {
  constructor(public mediaStreamTrack: { readyState: string }) {
    super();
  }
}

class FakePublication {
  constructor(
    public source: Track.Source,
    public track: FakeTrack,
    public isMuted: boolean,
  ) {}
}

class FakeLocalParticipant {
  constructor(public micPublication: FakePublication | null) {}

  getTrackPublication(source: Track.Source): FakePublication | undefined {
    return source === Track.Source.Microphone ? (this.micPublication ?? undefined) : undefined;
  }
}

class FakeRoom extends FakeEmitter {
  localParticipant: FakeLocalParticipant;

  constructor(micPublication: FakePublication | null) {
    super();
    this.localParticipant = new FakeLocalParticipant(micPublication);
  }

  asRoom(): Room {
    return this as unknown as Room;
  }
}

// ---- 가짜 MediaRecorder / MediaStream --------------------------------------
class FakeMediaStream {
  constructor(readonly tracks: unknown[]) {}
}

class FakeMediaRecorder {
  static instances: FakeMediaRecorder[] = [];

  static isTypeSupported = (type: string) => type === "audio/webm;codecs=opus";

  state: "inactive" | "recording" | "paused" = "inactive";

  ondataavailable: ((event: BlobEvent) => void) | null = null;

  readonly mimeType: string;

  startTimeslice: number | undefined;

  constructor(
    readonly stream: FakeMediaStream,
    readonly options: MediaRecorderOptions,
  ) {
    this.mimeType = options.mimeType ?? "";
    FakeMediaRecorder.instances.push(this);
  }

  start(timeslice?: number) {
    this.startTimeslice = timeslice;
    this.state = "recording";
  }

  stop() {
    this.state = "inactive";
  }

  pause() {
    this.state = "paused";
  }

  resume() {
    this.state = "recording";
  }

  /** 실제 recorder처럼 미회수분을 dataavailable로 내보낸다. 기본은 빈 조각. */
  requestData() {
    this.emitChunk(new Uint8Array(0));
  }

  emitChunk(bytes: Uint8Array<ArrayBuffer>) {
    const blob = new Blob([bytes], { type: this.mimeType });
    this.ondataavailable?.({ data: blob } as unknown as BlobEvent);
  }

  static last(): FakeMediaRecorder {
    const instance = FakeMediaRecorder.instances[FakeMediaRecorder.instances.length - 1];
    if (!instance) throw new Error("생성된 FakeMediaRecorder가 없습니다.");
    return instance;
  }
}

// ---- 헬퍼 -------------------------------------------------------------------
/** 헤더 분리(splitFirstWebmChunk)의 Blob.arrayBuffer 비동기 처리까지 흘려보낸다. */
const flushAsync = () =>
  act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
    await new Promise((resolve) => setTimeout(resolve, 0));
  });

interface SetupOptions {
  windowMs?: number;
  muted?: boolean;
  withMicPublication?: boolean;
  strictMode?: boolean;
}

const setup = ({
  windowMs = 60_000,
  muted = false,
  withMicPublication = true,
  strictMode = false,
}: SetupOptions = {}) => {
  const clock = { nowMs: 0 };
  const track = new FakeTrack({ readyState: "live" });
  const publication = new FakePublication(Track.Source.Microphone, track, muted);
  const room = new FakeRoom(withMicPublication ? publication : null);

  const wrapper = strictMode
    ? ({ children }: { children: ReactNode }) => <StrictMode>{children}</StrictMode>
    : undefined;
  const view = renderHook(
    () => useInstructorAudioBuffer(room.asRoom(), { now: () => clock.nowMs, windowMs }),
    { wrapper },
  );

  return { clock, track, publication, room, ...view };
};

beforeEach(() => {
  FakeMediaRecorder.instances = [];
  vi.stubGlobal("MediaRecorder", FakeMediaRecorder);
  vi.stubGlobal("MediaStream", FakeMediaStream);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("useInstructorAudioBuffer", () => {
  it("마이크 트랙으로 recorder를 만들어 1초 timeslice·지정 비트레이트로 시작한다", () => {
    const { track, result } = setup();

    expect(FakeMediaRecorder.instances).toHaveLength(1);
    const recorder = FakeMediaRecorder.last();
    expect(recorder.startTimeslice).toBe(RECORDER_TIMESLICE_MS);
    expect(recorder.options.audioBitsPerSecond).toBe(RECORDER_AUDIO_BITS_PER_SECOND);
    expect(recorder.mimeType).toBe("audio/webm;codecs=opus");
    // publish 중인 mediaStreamTrack을 복제 없이 그대로 감싼다(프라이버시 이중 방어).
    expect(recorder.stream.tracks).toEqual([track.mediaStreamTrack]);
    expect(result.current.captureState).toBe("recording");
  });

  it("첫 조각에서 헤더를 분리해 고정하고, 본문은 만료돼도 헤더는 유지된다", async () => {
    const { clock, result } = setup({ windowMs: 5_000 });
    const recorder = FakeMediaRecorder.last();

    clock.nowMs = 1_000;
    recorder.emitChunk(firstChunkBytes(50)); // 본문 [0, 1000]
    await flushAsync();
    expect(result.current.availableMs()).toBe(1_000);

    clock.nowMs = 2_000;
    recorder.emitChunk(payload(30)); // [1000, 2000]
    await flushAsync();
    expect(result.current.availableMs()).toBe(2_000);

    // cutoff = 6500 - 5000 = 1500 → 첫 본문만 만료되고 헤더 + 두 번째 조각이 남는다.
    clock.nowMs = 6_500;
    const clip = await result.current.snapshot();
    expect(clip?.blob.size).toBe(HEADER_SIZE + 30);
    expect(clip?.capturedFromMs).toBe(1_000);
    expect(clip?.capturedToMs).toBe(2_000);
  });

  it("첫 조각에 Cluster가 없으면 전체를 헤더로 고정하고 다음 조각부터 오디오로 쌓는다", async () => {
    const { clock, result } = setup();
    const recorder = FakeMediaRecorder.last();

    clock.nowMs = 1_000;
    recorder.emitChunk(new Uint8Array(HEADER_BYTES)); // Cluster 없는 순수 헤더
    await flushAsync();
    expect(result.current.availableMs()).toBe(0);

    clock.nowMs = 2_000;
    recorder.emitChunk(payload(30));
    await flushAsync();

    const clip = await result.current.snapshot();
    expect(clip?.blob.size).toBe(HEADER_SIZE + 30);
    expect(clip?.capturedFromMs).toBe(1_000);
  });

  it("음소거 시 requestData→pause 순서로 미완성 조각을 회수해 멈추고, 해제 시 공백을 건너뛰고 재개한다", async () => {
    const { clock, publication, room, result } = setup();
    const recorder = FakeMediaRecorder.last();

    clock.nowMs = 1_000;
    recorder.emitChunk(firstChunkBytes(50)); // [0, 1000]
    await flushAsync();

    const callOrder: string[] = [];
    const originalRequestData = recorder.requestData.bind(recorder);
    recorder.requestData = () => {
      callOrder.push("requestData");
      originalRequestData();
    };
    const originalPause = recorder.pause.bind(recorder);
    recorder.pause = () => {
      callOrder.push("pause");
      originalPause();
    };

    clock.nowMs = 2_000;
    act(() => {
      room.emit(RoomEvent.TrackMuted, publication, room.localParticipant);
    });
    expect(callOrder).toEqual(["requestData", "pause"]);
    expect(recorder.state).toBe("paused");
    expect(result.current.captureState).toBe("paused");

    clock.nowMs = 7_000;
    act(() => {
      room.emit(RoomEvent.TrackUnmuted, publication, room.localParticipant);
    });
    expect(recorder.state).toBe("recording");
    expect(result.current.captureState).toBe("recording");

    clock.nowMs = 8_000;
    recorder.emitChunk(payload(30)); // 재개 후 [7000, 8000]
    await flushAsync();

    const clip = await result.current.snapshot();
    expect(clip?.segments).toEqual([
      { fromMs: 0, toMs: 1_000 },
      { fromMs: 7_000, toMs: 8_000 },
    ]);
    expect(clip?.durationMs).toBe(2_000);
  });

  it("다른 소스·다른 참가자의 mute 이벤트는 무시한다", () => {
    const { room, result } = setup();
    const otherTrack = new FakeTrack({ readyState: "live" });
    const cameraPublication = new FakePublication(Track.Source.Camera, otherTrack, false);

    act(() => {
      room.emit(RoomEvent.TrackMuted, cameraPublication, room.localParticipant);
      room.emit(
        RoomEvent.TrackMuted,
        new FakePublication(Track.Source.Microphone, otherTrack, false),
        { identity: "remote" },
      );
    });

    expect(result.current.captureState).toBe("recording");
    expect(FakeMediaRecorder.last().state).toBe("recording");
  });

  it("음소거 상태로 시작하면 즉시 일시정지한다", () => {
    const { result } = setup({ muted: true });

    expect(FakeMediaRecorder.last().state).toBe("paused");
    expect(result.current.captureState).toBe("paused");
  });

  it("장치 전환(TrackEvent.Restarted)이면 recorder와 버퍼를 함께 재시작한다", async () => {
    const { clock, track, result } = setup();
    const firstRecorder = FakeMediaRecorder.last();

    clock.nowMs = 1_000;
    firstRecorder.emitChunk(firstChunkBytes(50));
    await flushAsync();
    expect(result.current.availableMs()).toBe(1_000);

    act(() => {
      track.emit(TrackEvent.Restarted);
    });

    expect(FakeMediaRecorder.instances).toHaveLength(2);
    expect(firstRecorder.state).toBe("inactive");
    expect(result.current.captureState).toBe("recording");
    // 다른 인코더 초기화 구간과 섞이지 않도록 이전 오디오는 버린다.
    expect(result.current.availableMs()).toBe(0);

    const secondRecorder = FakeMediaRecorder.last();
    clock.nowMs = 2_000;
    secondRecorder.emitChunk(firstChunkBytes(20));
    await flushAsync();

    const clip = await result.current.snapshot();
    expect(clip?.blob.size).toBe(HEADER_SIZE + firstChunkBodySize(20));
    expect(clip?.capturedFromMs).toBe(1_000);
  });

  it("트랙이 끝나면(장치 분리) idle로 내려가고 버퍼를 비운다", async () => {
    const { clock, track, result } = setup();
    const recorder = FakeMediaRecorder.last();

    clock.nowMs = 1_000;
    recorder.emitChunk(firstChunkBytes(50));
    await flushAsync();
    expect(result.current.availableMs()).toBe(1_000);

    act(() => {
      track.emit(TrackEvent.Ended);
    });

    expect(result.current.captureState).toBe("idle");
    expect(recorder.state).toBe("inactive");
    expect(result.current.availableMs()).toBe(0);
    expect(await result.current.snapshot()).toBeNull();
  });

  it("마이크 unpublish면 idle, 다시 publish되면 새 recorder로 재개한다", () => {
    const { publication, room, result } = setup();

    act(() => {
      room.localParticipant.micPublication = null;
      room.emit(RoomEvent.LocalTrackUnpublished, publication, room.localParticipant);
    });
    expect(result.current.captureState).toBe("idle");
    expect(FakeMediaRecorder.instances[0].state).toBe("inactive");

    act(() => {
      room.localParticipant.micPublication = publication;
      room.emit(RoomEvent.LocalTrackPublished, publication, room.localParticipant);
    });
    expect(result.current.captureState).toBe("recording");
    expect(FakeMediaRecorder.instances).toHaveLength(2);
  });

  it("snapshot은 requestData로 요청 시점 직전의 미완성 조각까지 포함한다", async () => {
    const { clock, result } = setup();
    const recorder = FakeMediaRecorder.last();

    clock.nowMs = 1_000;
    recorder.emitChunk(firstChunkBytes(50));
    await flushAsync();

    clock.nowMs = 1_500;
    recorder.requestData = () => {
      recorder.emitChunk(payload(30)); // 미완성 조각 [1000, 1500]
    };

    const clip = await result.current.snapshot();
    expect(clip?.blob.size).toBe(HEADER_SIZE + firstChunkBodySize(50) + 30);
    expect(clip?.durationMs).toBe(1_500);
    expect(clip?.segments).toEqual([{ fromMs: 0, toMs: 1_500 }]);
  });

  it("마이크 publication이 없으면 recorder를 만들지 않고 idle로 대기한다", () => {
    const { result } = setup({ withMicPublication: false });

    expect(FakeMediaRecorder.instances).toHaveLength(0);
    expect(result.current.captureState).toBe("idle");
  });

  it("room이 없으면 idle이다", () => {
    const clock = { nowMs: 0 };
    const { result } = renderHook(() =>
      useInstructorAudioBuffer(null, { now: () => clock.nowMs }),
    );

    expect(result.current.captureState).toBe("idle");
    expect(FakeMediaRecorder.instances).toHaveLength(0);
  });

  it("MediaRecorder를 지원하지 않는 환경이면 idle이다", () => {
    vi.stubGlobal("MediaRecorder", undefined);
    const { result } = setup();

    expect(result.current.captureState).toBe("idle");
  });

  it("unmount 시 room·track 리스너를 모두 해제하고 recorder를 정지한다", () => {
    const { room, track, unmount } = setup();
    expect(room.handlerCount()).toBeGreaterThan(0);
    expect(track.handlerCount()).toBeGreaterThan(0);

    unmount();

    expect(room.handlerCount()).toBe(0);
    expect(track.handlerCount()).toBe(0);
    expect(FakeMediaRecorder.last().state).toBe("inactive");
  });

  it("StrictMode 이중 마운트에서도 리스너가 중복 등록되지 않는다", () => {
    const { room, track, result } = setup({ strictMode: true });

    // effect 이중 실행 여부는 React 빌드에 따라 다르므로 단정하지 않는다.
    // 불변식만 검증한다: 리스너는 정확히 한 벌, 활성 recorder는 마지막 하나뿐.
    expect(room.handlerCount()).toBe(4);
    expect(track.handlerCount()).toBe(2);
    FakeMediaRecorder.instances.slice(0, -1).forEach((instance) => {
      expect(instance.state).toBe("inactive");
    });
    expect(FakeMediaRecorder.last().state).toBe("recording");
    expect(result.current.captureState).toBe("recording");
  });
});
