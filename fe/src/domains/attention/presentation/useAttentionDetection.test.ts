import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { AttentionPrediction } from "../domain/attentionPrediction";
import type { FrameLandmarkerValues } from "../infrastructure/frameContracts";
import type {
  AttentionInferenceClient,
  AttentionInferenceFailure,
} from "../infrastructure/attentionInferenceClient";
import { BLENDSHAPE_NAMES } from "../infrastructure/frameFeatures";
import { useAttentionDetection, type FrameScheduler } from "./useAttentionDetection";

/**
 * 판정 흐름 자체는 `application/attentionDetectionSession.test.ts` 가 검증한다.
 * 여기서는 훅이 책임지는 것만 본다 — 카메라 상태에 따른 세션 수명주기, 렌더 상태 파생,
 * 세션 간 결과 격리, 상위 통지.
 */

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

const PREDICTION: AttentionPrediction = {
  label: "Engaged",
  probabilities: [0.1, 0.1, 0.7, 0.1],
};

describe("useAttentionDetection", () => {
  let frames: ReturnType<typeof manualScheduler>;
  let close: ReturnType<typeof vi.fn>;
  let terminate: ReturnType<typeof vi.fn>;
  let createLandmarker: ReturnType<typeof vi.fn>;
  let onPrediction: ReturnType<typeof vi.fn>;
  let onStatusChange: ReturnType<typeof vi.fn>;
  let emitPrediction: (prediction: AttentionPrediction) => void;
  let renderCount: number;

  beforeEach(() => {
    frames = manualScheduler();
    close = vi.fn();
    terminate = vi.fn();
    createLandmarker = vi.fn(async () => ({ detect: () => detectedFace(), close }));
    onPrediction = vi.fn();
    onStatusChange = vi.fn();
    renderCount = 0;
  });

  function createInferenceClient(handlers: {
    onPrediction(prediction: AttentionPrediction): void;
    onFailure(failure: AttentionInferenceFailure): void;
  }): AttentionInferenceClient {
    emitPrediction = handlers.onPrediction;
    return { submit: vi.fn(), terminate };
  }

  function render(camera: "on" | "off" | "denied" = "on") {
    const videoRef = readyVideoRef();
    return renderHook(
      (props: { camera: "on" | "off" | "denied" }) => {
        renderCount += 1;
        return useAttentionDetection({
          videoRef,
          camera: props.camera,
          onPrediction,
          onStatusChange,
          scheduler: frames.scheduler,
          createLandmarker,
          createInferenceClient,
        });
      },
      { initialProps: { camera } },
    );
  }

  async function advance(untilMs: number, fromMs = 0) {
    for (let timestamp = fromMs; timestamp <= untilMs; timestamp += 100) {
      await act(async () => frames.tick(timestamp));
    }
  }

  it("stays idle and starts no session while the camera is off", async () => {
    const { result } = render("off");
    await act(async () => {});

    expect(result.current.status).toBe("idle");
    expect(createLandmarker).not.toHaveBeenCalled();
    expect(frames.pending).toBe(0);
  });

  it("reports a denied camera permission without starting a session", async () => {
    const { result } = render("denied");
    await act(async () => {});

    expect(result.current.status).toBe("permissionDenied");
    expect(createLandmarker).not.toHaveBeenCalled();
  });

  it("exposes the status the session reports", async () => {
    const { result } = render();
    await act(async () => {});
    await advance(1_000);

    expect(result.current.status).toBe("collecting");
  });

  it("surfaces the judgement the session produces", async () => {
    const { result } = render();
    await act(async () => {});
    await advance(10_000);

    await act(async () => emitPrediction(PREDICTION));

    expect(result.current.prediction).toEqual(PREDICTION);
    expect(result.current.status).toBe("measuring");
  });

  it("notifies the caller of predictions and status changes", async () => {
    render();
    await act(async () => {});
    await advance(1_000);

    await act(async () => emitPrediction(PREDICTION));

    // 창 메타는 제출을 거친 판정에만 붙는다. 이 테스트는 결과를 직접 흘려보내므로 null 이다.
    expect(onPrediction).toHaveBeenCalledWith(PREDICTION, null);
    expect(onStatusChange).toHaveBeenCalledWith("collecting");
    expect(onStatusChange).toHaveBeenCalledWith("measuring");
  });

  it("does not re-render once per sample while the status is unchanged", async () => {
    render();
    await act(async () => {});
    const before = renderCount;

    // 표본 99회. 세션은 표본마다 상태를 보고하지만 값이 그대로면 렌더가 따라 늘면 안 된다.
    await advance(9_900, 100);

    // React 는 같은 값을 돌려주는 첫 setState 에서 한 번은 렌더하고 그 뒤로 건너뛴다.
    expect(renderCount - before).toBeLessThanOrEqual(1);
  });

  it("does not surface the previous session's judgement after the camera is turned back on", async () => {
    const view = render();
    await act(async () => {});
    await advance(10_000);
    await act(async () => emitPrediction(PREDICTION));
    expect(view.result.current.prediction).toEqual(PREDICTION);

    await act(async () => view.rerender({ camera: "off" }));
    await act(async () => view.rerender({ camera: "on" }));

    expect(view.result.current.prediction).toBeNull();
    expect(view.result.current.status).toBe("collecting");
  });

  it("stops the session when the camera is turned off", async () => {
    const view = render();
    await act(async () => {});
    await advance(1_000);

    await act(async () => view.rerender({ camera: "off" }));

    expect(close).toHaveBeenCalledTimes(1);
    expect(terminate).toHaveBeenCalledTimes(1);
    expect(view.result.current.status).toBe("idle");
    expect(frames.pending).toBe(0);
  });

  it("stops the session on unmount", async () => {
    const view = render();
    await act(async () => {});
    await advance(1_000);

    view.unmount();

    expect(close).toHaveBeenCalledTimes(1);
    expect(terminate).toHaveBeenCalledTimes(1);
  });
});
