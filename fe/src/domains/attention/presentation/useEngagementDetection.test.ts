import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { EngagementPrediction } from "../domain/engagementPrediction";
import type { FrameLandmarkerValues } from "../infrastructure/frameContracts";
import type {
  EngagementInferenceClient,
  EngagementInferenceFailure,
} from "../infrastructure/engagementInferenceClient";
import { BLENDSHAPE_NAMES } from "../infrastructure/frameFeatures";
import { useEngagementDetection, type FrameScheduler } from "./useEngagementDetection";

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

function readyVideoRef() {
  const video = document.createElement("video");
  Object.defineProperty(video, "readyState", { value: 2, configurable: true });
  return { current: video };
}

describe("useEngagementDetection", () => {
  let frames: ReturnType<typeof manualScheduler>;
  let detect: ReturnType<typeof vi.fn>;
  let close: ReturnType<typeof vi.fn>;
  let submit: ReturnType<typeof vi.fn>;
  let terminate: ReturnType<typeof vi.fn>;
  let createLandmarker: ReturnType<typeof vi.fn>;
  let emitPrediction: (prediction: EngagementPrediction) => void;
  let emitFailure: (failure: EngagementInferenceFailure) => void;
  let fetchSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    frames = manualScheduler();
    detect = vi.fn(() => detectedFace());
    close = vi.fn();
    submit = vi.fn();
    terminate = vi.fn();
    createLandmarker = vi.fn(async () => ({ detect, close }));
    fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("{}"));
  });

  afterEach(() => {
    fetchSpy.mockRestore();
  });

  function createInferenceClient(handlers: {
    onPrediction(prediction: EngagementPrediction): void;
    onFailure(failure: EngagementInferenceFailure): void;
  }): EngagementInferenceClient {
    emitPrediction = handlers.onPrediction;
    emitFailure = handlers.onFailure;
    return { submit, terminate };
  }

  function render(camera: "on" | "off" | "denied" = "on") {
    const videoRef = readyVideoRef();
    return renderHook(
      (props: { camera: "on" | "off" | "denied" }) =>
        useEngagementDetection({
          videoRef,
          camera: props.camera,
          scheduler: frames.scheduler,
          createLandmarker,
          createInferenceClient,
        }),
      { initialProps: { camera } },
    );
  }

  /** 지정한 시간만큼 100ms 간격으로 프레임을 흘려보낸다. */
  async function advance(untilMs: number, fromMs = 0) {
    for (let timestamp = fromMs; timestamp <= untilMs; timestamp += 100) {
      await act(async () => frames.tick(timestamp));
    }
  }

  it("stays idle and starts nothing while the camera is off", async () => {
    const { result } = render("off");
    await act(async () => {});

    expect(result.current.status).toBe("idle");
    expect(createLandmarker).not.toHaveBeenCalled();
    expect(frames.pending).toBe(0);
  });

  it("reports a denied camera permission without starting detection", async () => {
    const { result } = render("denied");
    await act(async () => {});

    expect(result.current.status).toBe("permissionDenied");
    expect(createLandmarker).not.toHaveBeenCalled();
  });

  it("collects the first window before producing anything", async () => {
    const { result } = render();
    await act(async () => {});
    await advance(5_000);

    expect(result.current.status).toBe("collecting");
    expect(submit).not.toHaveBeenCalled();
  });

  it("submits a 20×98 token window once ten seconds are collected", async () => {
    render();
    await act(async () => {});
    await advance(10_000);

    expect(submit).toHaveBeenCalledTimes(1);
    expect(submit.mock.calls[0]?.[0]).toHaveLength(20 * 98);
  });

  it("keeps producing a judgement every ten seconds", async () => {
    render();
    await act(async () => {});
    // 첫 창은 10초에, 두 번째 창은 그 뒤 다시 10초를 모은 20.1초에 완성된다.
    await advance(20_100);

    expect(submit).toHaveBeenCalledTimes(2);
  });

  it("exposes the prediction the worker returns", async () => {
    const prediction: EngagementPrediction = {
      label: "Engaged",
      probabilities: [0.1, 0.1, 0.7, 0.1],
    };
    const { result } = render();
    await act(async () => {});
    await advance(10_000);

    await act(async () => emitPrediction(prediction));

    expect(result.current.prediction).toEqual(prediction);
    expect(result.current.status).toBe("measuring");
  });

  it("never sends frames or landmarks to a server", async () => {
    render();
    await act(async () => {});
    await advance(10_000);

    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("drops backlogged frames and samples at the configured interval only", async () => {
    render();
    await act(async () => {});
    // 10ms 간격으로 프레임이 밀려 들어와도 100ms 주기로만 표본을 뽑는다.
    for (let timestamp = 0; timestamp <= 500; timestamp += 10) {
      await act(async () => frames.tick(timestamp));
    }

    expect(detect).toHaveBeenCalledTimes(6);
  });

  it("becomes unmeasurable while no face is detected", async () => {
    detect.mockReturnValue(null);
    const { result } = render();
    await act(async () => {});
    await advance(1_000);

    expect(result.current.status).toBe("unmeasurable");
    expect(submit).not.toHaveBeenCalled();
  });

  it("disables judgement but keeps the loop torn down when the model is unavailable", async () => {
    const { result } = render();
    await act(async () => {});
    await advance(10_000);

    await act(async () => emitFailure({ kind: "modelUnavailable", message: "모델 없음" }));

    expect(result.current.status).toBe("unavailable");
    expect(frames.pending).toBe(0);
  });

  it("keeps judging after a single failed inference", async () => {
    const { result } = render();
    await act(async () => {});
    await advance(10_000);

    await act(async () => emitFailure({ kind: "inferenceFailed", message: "추론 실패" }));
    await advance(20_100, 10_100);

    expect(result.current.status).not.toBe("unavailable");
    expect(submit).toHaveBeenCalledTimes(2);
  });

  it("stops detection and releases the worker when the camera is turned off", async () => {
    const view = render();
    await act(async () => {});
    await advance(1_000);

    await act(async () => view.rerender({ camera: "off" }));

    expect(close).toHaveBeenCalledTimes(1);
    expect(terminate).toHaveBeenCalledTimes(1);
    expect(view.result.current.status).toBe("idle");
    expect(frames.pending).toBe(0);
  });

  it("releases MediaPipe and the worker on unmount", async () => {
    const view = render();
    await act(async () => {});
    await advance(1_000);

    view.unmount();

    expect(close).toHaveBeenCalledTimes(1);
    expect(terminate).toHaveBeenCalledTimes(1);
  });
});
