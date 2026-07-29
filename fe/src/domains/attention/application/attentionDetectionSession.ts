import type { AttentionPrediction, AttentionStatus } from "../domain/attentionPrediction";
import type { DetectorOutput } from "../domain/detectionOutcome";
import {
  createBrowserFaceLandmarker,
  type BrowserFaceLandmarker,
} from "../infrastructure/faceLandmarker";
import {
  createAttentionInferenceClient,
  type AttentionInferenceClient,
  type AttentionInferenceFailure,
} from "../infrastructure/attentionInferenceClient";
import { extractFrameFeatures } from "../infrastructure/frameFeatures";
import { RollingFeatureWindow } from "../infrastructure/rollingFeatureWindow";
import {
  createTrackProcessorFrameSource,
  type TrackProcessorFrameSource,
  type TrackProcessorFrameSourceOptions,
} from "../infrastructure/trackProcessorFrameSource";

/**
 * 카메라 한 세션 동안의 판정 유스케이스.
 *
 * MediaPipe 표본 추출 → 10초 창 수집 → Worker 추론 제출까지의 흐름을 소유한다. React 를
 * 모르기 때문에 훅 없이 단위 테스트할 수 있고, presentation 은 이 세션을 켜고 끄면서
 * 보고만 받는다.
 *
 * 프레임·랜드마크는 이 안에서만 존재하며 밖으로 나가는 값은 상태와 판정 결과뿐이다.
 */

export interface AttentionDetectionSessionOptions {
  /** LiveKit 로컬 카메라 트랙. 복제본을 Worker의 TrackProcessor에서 읽는다. */
  readonly track: MediaStreamTrack;
  onStatus(status: AttentionStatus): void;
  onPrediction(prediction: AttentionPrediction): void;
  /** 7종 검출기 출력. 확률은 로컬 소비자에게만 제공한다. */
  onDetection?(output: DetectorOutput): void;
  readonly createFrameSource?: (
    options: TrackProcessorFrameSourceOptions,
  ) => TrackProcessorFrameSource;
  createLandmarker?: () => Promise<BrowserFaceLandmarker>;
  createInferenceClient?: (handlers: {
    onPrediction(prediction: AttentionPrediction): void;
    onFailure(failure: AttentionInferenceFailure): void;
  }) => AttentionInferenceClient;
}

export interface AttentionDetectionSession {
  /** 루프를 끊고 MediaPipe·Worker 자원을 놓는다. 여러 번 불러도 안전하다. */
  stop(): void;
}

export function startAttentionDetection(
  options: AttentionDetectionSessionOptions,
): AttentionDetectionSession {
  const {
    track,
    onStatus,
    onPrediction,
    onDetection,
    createFrameSource = createTrackProcessorFrameSource,
    createLandmarker = createBrowserFaceLandmarker,
    createInferenceClient = createAttentionInferenceClient,
  } = options;

  let stopped = false;
  let landmarker: BrowserFaceLandmarker | null = null;
  let frameSource: TrackProcessorFrameSource | null = null;
  let judgementDisabled = false;
  let hasPrediction = false;
  const featureWindow = new RollingFeatureWindow();

  const inference = createInferenceClient({
    onPrediction(next: AttentionPrediction) {
      if (stopped) return;
      hasPrediction = true;
      onPrediction(next);
      onDetection?.({ outcome: next.label, probabilities: next.probabilities });
      onStatus("measuring");
    },
    onFailure(failure: AttentionInferenceFailure) {
      if (stopped) return;
      if (failure.kind !== "modelUnavailable") {
        // 세션은 살아 있으므로 다음 창에서 다시 시도한다.
        console.warn("[attention] 참여도 추론 실패", failure.message);
        return;
      }
      // 모델을 못 불러왔으면 판정만 비활성화하고 수업은 계속한다.
      disableJudgement();
    },
  });

  function sample(
    active: BrowserFaceLandmarker,
    frame: TexImageSource,
    timestampMs: number,
  ): void {
    let values: Float32Array | null;
    try {
      const detected = active.detect(frame, timestampMs);
      values = detected === null ? null : extractFrameFeatures(detected);
    } catch (error) {
      // 한 프레임의 실패로 루프를 끊지 않는다.
      console.warn("[attention] 프레임 특징 추출 실패", error);
      return;
    }

    featureWindow.add(timestampMs, values);
    onStatus(values === null ? "unmeasurable" : hasPrediction ? "measuring" : "collecting");

    const evaluation = featureWindow.evaluate(timestampMs);
    if (evaluation.kind === "pending") return;
    // 측정 가능 여부와 무관하게 다음 10초 창을 처음부터 다시 모은다.
    featureWindow.clear();
    if (evaluation.kind === "unmeasurable") {
      onStatus("unmeasurable");
      onDetection?.({ outcome: "UNMEASURABLE" });
      return;
    }
    inference.submit(evaluation.tokens);
  }

  function stopFrameSource(): void {
    frameSource?.stop();
    frameSource = null;
  }

  function disableJudgement(): void {
    if (judgementDisabled || stopped) return;
    judgementDisabled = true;
    stopFrameSource();
    featureWindow.clear();
    onStatus("unavailable");
    onDetection?.({ outcome: "DETECTOR_UNAVAILABLE" });
  }

  async function begin(): Promise<void> {
    try {
      const created = await createLandmarker();
      if (stopped) {
        created.close();
        return;
      }
      landmarker = created;
      onStatus("collecting");
      frameSource = createFrameSource({
        track,
        onFrame(frame, timestampMs) {
          if (stopped || judgementDisabled || landmarker === null) {
            frame.close();
            return;
          }
          try {
            sample(landmarker, frame, timestampMs);
          } finally {
            frame.close();
          }
        },
        onFailure(message) {
          console.warn("[attention] 카메라 프레임 준비 실패", message);
          disableJudgement();
        },
      });
    } catch (error) {
      if (stopped) return;
      console.warn("[attention] MediaPipe 준비 실패", error);
      disableJudgement();
    }
  }

  void begin();

  return {
    stop(): void {
      stopped = true;
      stopFrameSource();
      landmarker?.close();
      landmarker = null;
      inference.terminate();
      featureWindow.clear();
    },
  };
}
