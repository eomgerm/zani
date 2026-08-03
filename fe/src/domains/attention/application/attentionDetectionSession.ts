import type { AttentionPrediction, AttentionStatus } from "../domain/attentionPrediction";
import type { DetectorOutput } from "../domain/detectionOutcome";
import {
  type AttentionFeatureDetector,
  type AttentionInferenceFailure,
  type CreateAttentionFeatureDetector,
  type CreateAttentionFrameSource,
  type CreateAttentionInferenceClient,
  type AttentionFrameSource,
  type AttentionFrame,
} from "./attentionDetectionPorts";
import {
  RollingFeatureWindow,
} from "../domain/rollingFeatureWindow";

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
  onStatus(status: AttentionStatus): void;
  onPrediction(prediction: AttentionPrediction): void;
  /** 7종 검출기 출력. 확률은 로컬 소비자에게만 제공한다. */
  onDetection?(output: DetectorOutput): void;
  readonly createFrameSource: CreateAttentionFrameSource;
  readonly createFeatureDetector: CreateAttentionFeatureDetector;
  readonly createInferenceClient: CreateAttentionInferenceClient;
}

export interface AttentionDetectionSession {
  /** 루프를 끊고 MediaPipe·Worker 자원을 놓는다. 여러 번 불러도 안전하다. */
  stop(): void;
}

export function startAttentionDetection(
  options: AttentionDetectionSessionOptions,
): AttentionDetectionSession {
  const {
    onStatus,
    onPrediction,
    onDetection,
    createFrameSource,
    createFeatureDetector,
    createInferenceClient,
  } = options;

  let stopped = false;
  let featureDetector: AttentionFeatureDetector | null = null;
  let frameSource: AttentionFrameSource | null = null;
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
        // 재시도가 남아 있거나 이번 추론만 실패한 경우다. 다음 창에서 다시 시도한다.
        console.warn(
          failure.kind === "modelLoadRetrying"
            ? "[attention] 참여도 모델 로드 재시도 대기"
            : "[attention] 참여도 추론 실패",
          failure.message,
        );
        return;
      }
      // 재시도까지 다 실패했으면 판정만 비활성화하고 수업은 계속한다.
      disableJudgement();
    },
  });

  function sample(
    active: AttentionFeatureDetector,
    frame: AttentionFrame,
    timestampMs: number,
  ): void {
    let values: Float32Array | null;
    try {
      values = active.detect(frame, timestampMs);
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
      const created = await createFeatureDetector();
      if (stopped) {
        created.close();
        return;
      }
      featureDetector = created;
      onStatus("collecting");
      frameSource = createFrameSource({
        onFrame(frame, timestampMs) {
          if (stopped || judgementDisabled || featureDetector === null) {
            frame.close();
            return;
          }
          try {
            sample(featureDetector, frame, timestampMs);
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
      featureDetector?.close();
      featureDetector = null;
      inference.terminate();
      featureWindow.clear();
    },
  };
}
