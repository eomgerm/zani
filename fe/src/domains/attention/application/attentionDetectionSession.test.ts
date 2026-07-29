import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { AttentionPrediction, AttentionStatus } from "../domain/attentionPrediction";
import type { FrameLandmarkerValues } from "../infrastructure/frameContracts";
import type {
  AttentionInferenceClient,
  AttentionInferenceFailure,
} from "../infrastructure/attentionInferenceClient";
import { BLENDSHAPE_NAMES } from "../infrastructure/frameFeatures";
import type { FrameScheduler } from "../infrastructure/frameScheduler";
import { startAttentionDetection } from "./attentionDetectionSession";

/** 얼굴이 정면을 보는 유효한 MediaPipe 출력 1장. */
function detectedFace(): FrameLandmarkerValues {
  const landmarks = Array.from({ length: 478 }, () => ({ x: 0, y: 0, z: 0 }));
  const set = (index: number, x: number, y: number) => {
    landmarks[index] = { x, y, z: 0 };
  };
  set(33, 0.1, 0.5); set(133, 0.3, 0.5); set(159, 0.2, 0.4); set(145, 0.2, 0.6);
  set(263, 0.9, 0.5); set(362, 0.7, 0.5); set(386, 0.8, 0.4); set(374, 0.8, 0.6);
  for (let index = 468; index < 473; index += 1) set(index, 0.2, 0.55);
  for (let index = 473; index < 478; index += 1) set(index, 0.8, 0.45);
  set(1, 0.4, 0.6);
  return {
    landmarks,
    transform: [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1],
    blendshapes: new Map(BLENDSHAPE_NAMES.map((name, index) => [name, index / 100])),
  };
}

function manualScheduler() {
  const callbacks = new Map<number, (timestampMs: number) => void>();
  let nextHandle = 1;
  const scheduler: FrameScheduler = {
    request(callback) {
      const handle = nextHandle;
      nextHandle += 1;
      callbacks.set(handle, callback);
      return handle;
    },
    cancel(handle) {
      callbacks.delete(handle);
    },
  };
  return {
    scheduler,
    get pending() {
      return callbacks.size;
    },
    tick(timestampMs: number) {
      const due = [...callbacks.values()];
      callbacks.clear();
      due.forEach((callback) => callback(timestampMs));
    },
  };
}

/**
 * 표본을 뜰 수 있는 상태의 video. jsdom 은 재생을 하지 않으므로 `readyState` 와 해상도를 손으로 세운다.
 *
 * <p>해상도가 필요한 이유: MediaPipe 는 크기가 0 인 프레임에 예외를 던지므로 세션이 해상도까지 확인한 뒤에 표본을 뜬다.
 */
function readyVideo(readyState = 2, size = { width: 640, height: 480 }) {
  const video = document.createElement("video");
  Object.defineProperty(video, "readyState", { value: readyState, configurable: true });
  Object.defineProperty(video, "videoWidth", { value: size.width, configurable: true });
  Object.defineProperty(video, "videoHeight", { value: size.height, configurable: true });
  return video;
}

describe("startAttentionDetection", () => {
  let frames: ReturnType<typeof manualScheduler>;
  let detect: ReturnType<typeof vi.fn>;
  let close: ReturnType<typeof vi.fn>;
  let submit: ReturnType<typeof vi.fn>;
  let terminate: ReturnType<typeof vi.fn>;
  let createLandmarker: ReturnType<typeof vi.fn>;
  let statuses: AttentionStatus[];
  let predictions: AttentionPrediction[];
  let video: HTMLVideoElement | null;
  let emitPrediction: (prediction: AttentionPrediction) => void;
  let emitFailure: (failure: AttentionInferenceFailure) => void;
  let fetchSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    frames = manualScheduler();
    detect = vi.fn(() => detectedFace());
    close = vi.fn();
    submit = vi.fn();
    terminate = vi.fn();
    createLandmarker = vi.fn(async () => ({ detect, close }));
    statuses = [];
    predictions = [];
    video = readyVideo();
    fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("{}"));
    vi.spyOn(console, "warn").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  function createInferenceClient(handlers: {
    onPrediction(prediction: AttentionPrediction): void;
    onFailure(failure: AttentionInferenceFailure): void;
  }): AttentionInferenceClient {
    emitPrediction = handlers.onPrediction;
    emitFailure = handlers.onFailure;
    return { submit, terminate };
  }

  function start() {
    return startAttentionDetection({
      videoSource: () => video,
      scheduler: frames.scheduler,
      createLandmarker,
      createInferenceClient,
      onStatus: (status) => statuses.push(status),
      onPrediction: (prediction) => predictions.push(prediction),
    });
  }

  /** MediaPipe 준비를 끝낸 뒤 100ms 간격으로 프레임을 흘려보낸다. */
  async function run(untilMs: number, fromMs = 0) {
    await Promise.resolve();
    for (let timestamp = fromMs; timestamp <= untilMs; timestamp += 100) {
      frames.tick(timestamp);
    }
  }

  const lastStatus = () => statuses.at(-1);

  it("collects before producing anything", async () => {
    start();
    await run(5_000);

    expect(lastStatus()).toBe("collecting");
    expect(submit).not.toHaveBeenCalled();
  });

  it("submits a 20×98 token window once ten seconds are collected", async () => {
    start();
    await run(10_000);

    expect(submit).toHaveBeenCalledTimes(1);
    expect(submit.mock.calls[0]?.[0]).toHaveLength(20 * 98);
  });

  it("keeps producing a judgement every ten seconds", async () => {
    start();
    await run(20_100);

    expect(submit).toHaveBeenCalledTimes(2);
  });

  it("reports the prediction and switches to measuring", async () => {
    const prediction: AttentionPrediction = {
      label: "Engaged",
      probabilities: [0.1, 0.1, 0.7, 0.1],
    };
    start();
    await run(10_000);

    emitPrediction(prediction);

    expect(predictions).toEqual([prediction]);
    expect(lastStatus()).toBe("measuring");
  });

  it("never sends frames or landmarks to a server", async () => {
    start();
    await run(10_000);

    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("drops backlogged frames and samples at the configured interval only", async () => {
    start();
    await Promise.resolve();
    // 10ms 간격으로 프레임이 밀려 들어와도 100ms 주기로만 표본을 뽑는다.
    for (let timestamp = 0; timestamp <= 500; timestamp += 10) {
      frames.tick(timestamp);
    }

    expect(detect).toHaveBeenCalledTimes(6);
  });

  /**
   * MediaPipe 는 크기가 0 인 프레임을 받으면 예외를 던진다. LiveKit 트랙을 갓 붙인 직후에 실제로
   * 재생은 시작됐지만 해상도가 아직 0 인 구간이 있어, `readyState` 만 보고 표본을 뜨면 매 프레임 실패한다.
   */
  it("해상도가 아직 0 인 프레임은 표본으로 뜨지 않는다", async () => {
    video = readyVideo(2, { width: 0, height: 0 });
    start();
    await run(1_000);

    expect(detect).not.toHaveBeenCalled();
  });

  /** 해상도가 잡히면 곧바로 표본을 뜬다 — 위 가드가 영구히 멈추게 만들지 않는지 확인한다. */
  it("해상도가 잡히면 다시 표본을 뜬다", async () => {
    video = readyVideo(2, { width: 0, height: 0 });
    start();
    await run(300);
    expect(detect).not.toHaveBeenCalled();

    video = readyVideo();
    await run(600);

    expect(detect).toHaveBeenCalled();
  });

  it("becomes unmeasurable while no face is detected", async () => {
    detect.mockReturnValue(null);
    start();
    await run(1_000);

    expect(lastStatus()).toBe("unmeasurable");
    expect(submit).not.toHaveBeenCalled();
  });

  it("keeps the loop alive when a single frame fails to convert", async () => {
    detect.mockImplementationOnce(() => {
      throw new Error("bad frame");
    });
    start();
    await run(1_000);

    expect(detect.mock.calls.length).toBeGreaterThan(1);
    expect(frames.pending).toBe(1);
  });

  it("skips sampling while the video has no frame yet", async () => {
    video = readyVideo(0);
    start();
    await run(1_000);

    expect(detect).not.toHaveBeenCalled();
    // 루프는 계속 돌며 비디오가 준비되기를 기다린다.
    expect(frames.pending).toBe(1);
  });

  it("disables judgement and stops the loop when the model is unavailable", async () => {
    start();
    await run(10_000);

    emitFailure({ kind: "modelUnavailable", message: "모델 없음" });

    expect(lastStatus()).toBe("unavailable");
    expect(frames.pending).toBe(0);
  });

  it("keeps judging after a single failed inference", async () => {
    start();
    await run(10_000);

    emitFailure({ kind: "inferenceFailed", message: "추론 실패" });
    await run(20_100, 10_100);

    expect(statuses).not.toContain("unavailable");
    expect(submit).toHaveBeenCalledTimes(2);
  });

  it("reports unavailable when MediaPipe cannot start", async () => {
    createLandmarker.mockRejectedValue(new Error("wasm 404"));
    start();
    await Promise.resolve();
    await Promise.resolve();

    expect(lastStatus()).toBe("unavailable");
    expect(frames.pending).toBe(0);
  });

  it("releases MediaPipe and the worker on stop", async () => {
    const session = start();
    await run(1_000);

    session.stop();

    expect(close).toHaveBeenCalledTimes(1);
    expect(terminate).toHaveBeenCalledTimes(1);
    expect(frames.pending).toBe(0);
  });

  it("closes a landmarker that finishes loading after the session stopped", async () => {
    const session = start();
    session.stop();
    await Promise.resolve();
    await Promise.resolve();

    expect(close).toHaveBeenCalledTimes(1);
    expect(frames.pending).toBe(0);
  });

  it("ignores worker messages that arrive after stop", async () => {
    const session = start();
    await run(10_000);
    session.stop();

    emitPrediction({ label: "Engaged", probabilities: [0, 0, 1, 0] });

    expect(predictions).toHaveLength(0);
  });
});
