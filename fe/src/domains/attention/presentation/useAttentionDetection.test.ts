import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi, type Mock } from "vitest";

import type {
  AttentionFeatureDetector,
  AttentionFrame,
  AttentionFrameSourceHandlers,
  AttentionInferenceClient,
  AttentionInferenceFailure,
} from "../application/attentionDetectionPorts";
import type { AttentionPrediction, AttentionStatus } from "../domain/attentionPrediction";
import type { DetectorOutput, DetectorReport } from "../domain/detectionOutcome";
import { useAttentionDetection } from "./useAttentionDetection";

/**
 * 판정 흐름 자체는 `application/attentionDetectionSession.test.ts` 가 검증한다.
 * 여기서는 훅이 책임지는 것만 본다 — 카메라 상태에 따른 세션 수명주기, 렌더 상태 파생,
 * 세션 간 결과 격리, 상위 통지.
 */

function detectedFeatures(): Float32Array {
  return new Float32Array(49).fill(1);
}

function manualFrameSource() {
  let options: AttentionFrameSourceHandlers | null = null;
  const stop = vi.fn<() => void>(() => {
    options = null;
  });
  return {
    createFrameSource(next: AttentionFrameSourceHandlers) {
      options = next;
      return { stop };
    },
    stop,
    get pending() {
      return options === null ? 0 : 1;
    },
    emit(timestampMs: number) {
      const frame: AttentionFrame = { close: vi.fn<() => void>() };
      options?.onFrame(frame, timestampMs);
    },
  };
}

const PREDICTION: AttentionPrediction = {
  label: "Engaged",
  probabilities: [0.1, 0.1, 0.7, 0.1],
};

describe("useAttentionDetection", () => {
  let frames: ReturnType<typeof manualFrameSource>;
  let close: Mock<() => void>;
  let terminate: Mock<AttentionInferenceClient["terminate"]>;
  let createFeatureDetector: Mock<() => Promise<AttentionFeatureDetector>>;
  let onPrediction: Mock<(prediction: AttentionPrediction) => void>;
  let onStatusChange: Mock<(status: AttentionStatus) => void>;
  let onDetection: Mock<(output: DetectorOutput) => void>;
  let onReport: ReturnType<typeof vi.fn<(report: DetectorReport) => void>>;
  let emitPrediction: (prediction: AttentionPrediction) => void;
  let renderCount: number;

  beforeEach(() => {
    frames = manualFrameSource();
    close = vi.fn();
    terminate = vi.fn();
    createFeatureDetector = vi.fn(async () => ({ detect: detectedFeatures, close }));
    onPrediction = vi.fn();
    onStatusChange = vi.fn();
    onDetection = vi.fn();
    onReport = vi.fn();
    renderCount = 0;
  });

  function createInferenceClient(handlers: {
    onPrediction(prediction: AttentionPrediction): void;
    onFailure(failure: AttentionInferenceFailure): void;
  }): AttentionInferenceClient {
    emitPrediction = handlers.onPrediction;
    return { submit: vi.fn(), terminate };
  }

  function render(
    camera: "on" | "off" | "denied" = "on",
    track: MediaStreamTrack | null = {
      readyState: "live",
      muted: false,
    } as MediaStreamTrack,
  ) {
    return renderHook(
      (props: { camera: "on" | "off" | "denied" }) => {
        renderCount += 1;
        return useAttentionDetection({
          camera: props.camera,
          track,
          onPrediction,
          onStatusChange,
          onDetection,
          onReport,
          createFrameSource: frames.createFrameSource,
          createFeatureDetector,
          createInferenceClient,
        });
      },
      { initialProps: { camera } },
    );
  }

  async function advance(untilMs: number, fromMs = 0) {
    for (let timestamp = fromMs; timestamp <= untilMs; timestamp += 100) {
      await act(async () => frames.emit(timestamp));
    }
  }

  it("stays idle and starts no session while the camera is off", async () => {
    const { result } = render("off");
    await act(async () => {});

    expect(result.current.status).toBe("idle");
    expect(createFeatureDetector).not.toHaveBeenCalled();
    expect(frames.pending).toBe(0);
  });

  it("reports CAMERA_OFF immediately and every ten seconds", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(0);
    const view = render("off");
    await act(async () => {});

    // 카메라 OFF 는 10초 창을 관측한 것이 아니라 창 시작 시각이 없다.
    expect(onReport).toHaveBeenNthCalledWith(1, {
      outcome: "CAMERA_OFF",
      observedAtMs: 0,
      clientEventId: expect.any(String),
    });

    act(() => vi.advanceTimersByTime(10_000));
    expect(onReport).toHaveBeenNthCalledWith(2, {
      outcome: "CAMERA_OFF",
      observedAtMs: 10_000,
      clientEventId: expect.any(String),
    });

    view.unmount();
    vi.useRealTimers();
  });

  it("notifies the local coaching pipeline when the camera turns off", async () => {
    render("off");
    await act(async () => {});

    expect(onDetection).toHaveBeenCalledWith({ outcome: "CAMERA_OFF" });
  });

  it("treats a missing local camera track as CAMERA_OFF", async () => {
    const { result } = render("on", null);
    await act(async () => {});

    expect(result.current.status).toBe("idle");
    expect(createFeatureDetector).not.toHaveBeenCalled();
    expect(onReport).toHaveBeenCalledWith(
      expect.objectContaining({ outcome: "CAMERA_OFF" }),
    );
  });

  it("reports detector startup failure immediately and every ten seconds", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(0);
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    createFeatureDetector.mockRejectedValue(new Error("wasm 404"));

    const view = render("on");
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(onReport).toHaveBeenNthCalledWith(1, {
      outcome: "DETECTOR_UNAVAILABLE",
      observedAtMs: 0,
      clientEventId: expect.any(String),
    });

    act(() => vi.advanceTimersByTime(10_000));
    expect(onReport).toHaveBeenNthCalledWith(2, {
      outcome: "DETECTOR_UNAVAILABLE",
      observedAtMs: 10_000,
      clientEventId: expect.any(String),
    });

    view.unmount();
    warn.mockRestore();
    vi.useRealTimers();
  });

  it("reports a denied camera permission without starting a session", async () => {
    const { result } = render("denied");
    await act(async () => {});

    expect(result.current.status).toBe("permissionDenied");
    expect(createFeatureDetector).not.toHaveBeenCalled();
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

    expect(onPrediction).toHaveBeenCalledWith(PREDICTION);
    expect(onStatusChange).toHaveBeenCalledWith("collecting");
    expect(onStatusChange).toHaveBeenCalledWith("measuring");
  });

  // 서버는 이 관측이 어느 10초 구간을 잰 것인지 알아야 수업 후 리포트의 근거로 쓸 수 있다.
  it("reports a judged window with the window it observed", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(0);
    const view = render();
    await act(async () => {});
    await advance(10_000);
    vi.setSystemTime(10_000);

    await act(async () => emitPrediction(PREDICTION));

    expect(onReport).toHaveBeenLastCalledWith({
      outcome: "ENGAGED",
      observedAtMs: 10_000,
      windowStartedAtMs: 0,
      clientEventId: expect.any(String),
    });

    view.unmount();
    vi.useRealTimers();
  });

  // 확률은 로컬 판정용이다. 서버 보고에 새어 들어가면 계약 밖 필드로 400 을 받는다.
  it("never carries probabilities into a server report", async () => {
    const view = render();
    await act(async () => {});
    await advance(10_000);

    await act(async () => emitPrediction(PREDICTION));

    expect(onReport).toHaveBeenCalled();
    onReport.mock.calls.forEach(([report]) => {
      expect(report).not.toHaveProperty("probabilities");
    });

    view.unmount();
  });

  it("notifies the local coaching pipeline with probabilities", async () => {
    render();
    await act(async () => {});
    await advance(10_000);

    await act(async () => emitPrediction(PREDICTION));

    expect(onDetection).toHaveBeenCalledWith({
      outcome: "Engaged",
      probabilities: [0.1, 0.1, 0.7, 0.1],
    });
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
