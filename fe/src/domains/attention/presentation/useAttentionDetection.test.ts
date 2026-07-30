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
    /** 창을 모으는 도중 카메라 프레임 공급이 끊긴 상황. */
    fail(message: string) {
      options?.onFailure(message);
    },
  };
}

/** jsdom 의 `document.hidden` 은 getter 라 spy 로 덮는다. */
function hideTab() {
  return vi.spyOn(document, "hidden", "get").mockReturnValue(true);
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

    expect(onReport).toHaveBeenNthCalledWith(1, {
      outcome: "CAMERA_OFF",
      observedAtMs: 0,
    });

    act(() => vi.advanceTimersByTime(10_000));
    expect(onReport).toHaveBeenNthCalledWith(2, {
      outcome: "CAMERA_OFF",
      observedAtMs: 10_000,
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
    });

    act(() => vi.advanceTimersByTime(10_000));
    expect(onReport).toHaveBeenNthCalledWith(2, {
      outcome: "DETECTOR_UNAVAILABLE",
      observedAtMs: 10_000,
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

  /**
   * 서버로 나가는 보고 경계(티켓 83). 순수 매핑은 `domain/detectionOutcome.test.ts` 가,
   * 반복 스케줄은 `application/detectionReportController.test.ts` 가 이미 고정한다.
   * 여기서는 배선을 통과한 뒤에도 그 계약이 유지되는지만 본다.
   */
  describe("서버 보고 경계", () => {
    it("reports the server outcome for a four-class window instead of the local label", async () => {
      vi.useFakeTimers();
      vi.setSystemTime(0);
      const view = render();
      await act(async () => {});
      await advance(10_000);

      await act(async () => emitPrediction(PREDICTION));

      // 로컬 라벨("Engaged")이 그대로 나가면 서버 enum 과 어긋난다.
      expect(onReport).toHaveBeenCalledWith({ outcome: "ENGAGED", observedAtMs: 0 });
      expect(onReport).not.toHaveBeenCalledWith(
        expect.objectContaining({ outcome: "Engaged" }),
      );

      view.unmount();
      vi.useRealTimers();
    });

    it("never lets probabilities reach the report boundary", async () => {
      const view = render();
      await act(async () => {});
      await advance(10_000);

      await act(async () => emitPrediction(PREDICTION));

      // 확률은 로컬 프롬프트 판정 전용이다. 원본 영상을 보관하지 않으므로 서버에 쓸 곳도 없다.
      expect(onDetection).toHaveBeenCalledWith(
        expect.objectContaining({ probabilities: PREDICTION.probabilities }),
      );
      for (const [report] of onReport.mock.calls) {
        expect(Object.keys(report).sort()).toEqual(["observedAtMs", "outcome"]);
      }

      view.unmount();
    });

    it("reports UNMEASURABLE when the window collected too few usable frames", async () => {
      vi.useFakeTimers();
      vi.setSystemTime(0);
      createFeatureDetector.mockResolvedValue({ detect: () => null, close });

      const view = render();
      await act(async () => {});
      await advance(10_000);

      expect(onReport).toHaveBeenCalledWith({ outcome: "UNMEASURABLE", observedAtMs: 0 });

      view.unmount();
      vi.useRealTimers();
    });

    it("keeps the judgement loop running while the tab is hidden but sends no report", async () => {
      vi.useFakeTimers();
      vi.setSystemTime(0);
      const hidden = hideTab();

      const view = render();
      await act(async () => {});
      await advance(10_000);
      await act(async () => emitPrediction(PREDICTION));

      // 루프는 계속 돈다 — 프레임 소스가 살아 있고 로컬 판정도 그대로 나온다.
      expect(frames.pending).toBe(1);
      expect(onDetection).toHaveBeenCalledWith(
        expect.objectContaining({ outcome: "Engaged" }),
      );
      // 보이지 않는 탭의 관측은 서버로 보내지 않는다.
      expect(onReport).not.toHaveBeenCalled();

      // 다시 보이면 그 다음 창부터 보고가 살아난다.
      hidden.mockReturnValue(false);
      await advance(20_000, 10_100);
      await act(async () => emitPrediction(PREDICTION));

      expect(onReport).toHaveBeenCalledWith({ outcome: "ENGAGED", observedAtMs: 0 });

      view.unmount();
      hidden.mockRestore();
      vi.useRealTimers();
    });

    it("reports CAMERA_OFF rather than UNMEASURABLE while a reconnect leaves the track ended", async () => {
      // 재연결 구간의 연결 측정 불가(useRoomReconnect 의 UNMEASURABLE)와 얼굴을 못 잡은
      // 검출기 UNMEASURABLE 은 다른 축이다. 트랙이 끊긴 것은 카메라가 영상을 못 주는 것이다.
      const ended = { readyState: "ended", muted: false } as MediaStreamTrack;

      const view = render("on", ended);
      await act(async () => {});

      expect(onReport).toHaveBeenCalledWith(
        expect.objectContaining({ outcome: "CAMERA_OFF" }),
      );
      expect(onReport).not.toHaveBeenCalledWith(
        expect.objectContaining({ outcome: "UNMEASURABLE" }),
      );
      expect(createFeatureDetector).not.toHaveBeenCalled();

      view.unmount();
    });

    it("reports CAMERA_OFF rather than UNMEASURABLE while another app holds the camera", async () => {
      const muted = { readyState: "live", muted: true } as MediaStreamTrack;

      const view = render("on", muted);
      await act(async () => {});

      expect(onReport).toHaveBeenCalledWith(
        expect.objectContaining({ outcome: "CAMERA_OFF" }),
      );
      expect(onReport).not.toHaveBeenCalledWith(
        expect.objectContaining({ outcome: "UNMEASURABLE" }),
      );

      view.unmount();
    });

    it("discards the in-progress window when the camera frame supply dies mid-window", async () => {
      vi.useFakeTimers();
      vi.setSystemTime(0);
      const warn = vi.spyOn(console, "warn").mockImplementation(() => {});

      const view = render();
      await act(async () => {});
      // 창의 절반만 모은 상태에서 프레임 공급이 끊긴다.
      await advance(5_000);
      await act(async () => frames.fail("camera lost"));

      // 모아둔 절반은 버린다. 창이 끝나는 시각을 지나도 창 보고는 나오지 않는다.
      await advance(15_000, 5_100);

      expect(frames.pending).toBe(0);
      expect(onReport.mock.calls.length).toBeGreaterThan(0);
      for (const [report] of onReport.mock.calls) {
        expect(report.outcome).toBe("DETECTOR_UNAVAILABLE");
      }

      view.unmount();
      warn.mockRestore();
      vi.useRealTimers();
    });

    it("stops reporting after unmount", async () => {
      vi.useFakeTimers();
      vi.setSystemTime(0);
      const view = render("off");
      await act(async () => {});
      expect(onReport).toHaveBeenCalledTimes(1);

      view.unmount();
      act(() => vi.advanceTimersByTime(60_000));

      // 반복 보고 타이머가 언마운트 뒤에도 살아 있으면 떠난 학생의 관측이 계속 쌓인다.
      expect(onReport).toHaveBeenCalledTimes(1);
      vi.useRealTimers();
    });
  });
});
