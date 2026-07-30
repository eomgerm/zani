import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";

import type { AttentionPrediction, AttentionStatus } from "../domain/attentionPrediction";
import type { DetectorOutput } from "../domain/detectionOutcome";
import type {
  AttentionFeatureDetector,
  AttentionFrame,
  AttentionFrameSourceHandlers,
  AttentionInferenceClient,
  AttentionInferenceFailure,
} from "./attentionDetectionPorts";
import { startAttentionDetection } from "./attentionDetectionSession";

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
      return frame;
    },
  };
}

describe("startAttentionDetection", () => {
  let frames: ReturnType<typeof manualFrameSource>;
  let detect: Mock<AttentionFeatureDetector["detect"]>;
  let close: Mock<() => void>;
  let submit: Mock<AttentionInferenceClient["submit"]>;
  let terminate: Mock<AttentionInferenceClient["terminate"]>;
  let createFeatureDetector: Mock<() => Promise<AttentionFeatureDetector>>;
  let statuses: AttentionStatus[];
  let predictions: AttentionPrediction[];
  let detections: DetectorOutput[];
  let emitPrediction: (prediction: AttentionPrediction) => void;
  let emitFailure: (failure: AttentionInferenceFailure) => void;
  let fetchSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    frames = manualFrameSource();
    detect = vi.fn(() => detectedFeatures());
    close = vi.fn();
    submit = vi.fn();
    terminate = vi.fn();
    createFeatureDetector = vi.fn(async () => ({ detect, close }));
    statuses = [];
    predictions = [];
    detections = [];
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
      createFrameSource: frames.createFrameSource,
      createFeatureDetector,
      createInferenceClient,
      onStatus: (status) => statuses.push(status),
      onPrediction: (prediction) => predictions.push(prediction),
      onDetection: (output) => detections.push(output),
    });
  }

  /** MediaPipe 준비를 끝낸 뒤 100ms 간격으로 프레임을 흘려보낸다. */
  async function run(untilMs: number, fromMs = 0) {
    await Promise.resolve();
    for (let timestamp = fromMs; timestamp <= untilMs; timestamp += 100) {
      frames.emit(timestamp);
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
    expect(detections).toEqual([{ outcome: "Engaged", probabilities: prediction.probabilities }]);
    expect(lastStatus()).toBe("measuring");
  });

  it("never sends frames or landmarks to a server", async () => {
    start();
    await run(10_000);

    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("becomes unmeasurable while no face is detected", async () => {
    detect.mockReturnValue(null);
    start();
    await run(1_000);

    expect(lastStatus()).toBe("unmeasurable");
    expect(submit).not.toHaveBeenCalled();
  });

  it("consumes worker track frames while the page is hidden", async () => {
    Object.defineProperty(document, "hidden", { configurable: true, value: true });
    const sourceStop = vi.fn();
    let emitFrame: ((frame: AttentionFrame, timestampMs: number) => void) | undefined;
    const videoFrame: AttentionFrame = { close: vi.fn() };

    const session = startAttentionDetection({
      createFrameSource: ({ onFrame }) => {
        emitFrame = onFrame;
        return { stop: sourceStop };
      },
      createFeatureDetector,
      createInferenceClient,
      onStatus: (status) => statuses.push(status),
      onPrediction: (prediction) => predictions.push(prediction),
      onDetection: (output) => detections.push(output),
    });
    await Promise.resolve();
    await Promise.resolve();

    emitFrame?.(videoFrame, 0);

    expect(detect).toHaveBeenCalledWith(videoFrame, 0);
    expect(videoFrame.close).toHaveBeenCalledTimes(1);

    session.stop();
    expect(sourceStop).toHaveBeenCalledTimes(1);
  });

  it("discards an unmeasurable completed window before collecting the next window", async () => {
    let frameIndex = 0;
    detect.mockImplementation(() => {
      const current = frameIndex;
      frameIndex += 1;
      if (current >= 100) return detectedFeatures();
      const segment = Math.floor(current / 5);
      const offset = current % 5;
      const validFrames = segment < 9 ? 4 : 3;
      return offset < validFrames ? detectedFeatures() : null;
    });
    start();

    await run(10_000);
    expect(lastStatus()).toBe("unmeasurable");
    expect(detections).toEqual([{ outcome: "UNMEASURABLE" }]);
    expect(submit).not.toHaveBeenCalled();

    await run(20_000, 10_100);
    expect(submit).not.toHaveBeenCalled();

    await run(20_100, 20_100);
    expect(submit).toHaveBeenCalledTimes(1);
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

  it("disables judgement and stops the loop when the model is unavailable", async () => {
    start();
    await run(10_000);

    emitFailure({ kind: "modelUnavailable", message: "모델 없음" });

    expect(lastStatus()).toBe("unavailable");
    expect(detections).toEqual([{ outcome: "DETECTOR_UNAVAILABLE" }]);
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
    createFeatureDetector.mockRejectedValue(new Error("wasm 404"));
    start();
    await Promise.resolve();
    await Promise.resolve();

    expect(lastStatus()).toBe("unavailable");
    expect(detections).toEqual([{ outcome: "DETECTOR_UNAVAILABLE" }]);
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
