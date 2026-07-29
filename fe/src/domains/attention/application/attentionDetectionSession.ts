import type { AttentionPrediction, AttentionStatus } from "../domain/attentionPrediction";
import {
  createBrowserFaceLandmarker,
  type BrowserFaceLandmarker,
} from "../infrastructure/faceLandmarker";
import {
  createAttentionInferenceClient,
  type AttentionInferenceClient,
  type AttentionInferenceFailure,
} from "../infrastructure/attentionInferenceClient";
import {
  ANIMATION_FRAME_SCHEDULER,
  type FrameScheduler,
} from "../infrastructure/frameScheduler";
import { extractFrameFeatures } from "../infrastructure/frameFeatures";
import { RollingFeatureWindow } from "../infrastructure/rollingFeatureWindow";

/**
 * 카메라 한 세션 동안의 판정 유스케이스.
 *
 * MediaPipe 표본 추출 → 10초 창 수집 → Worker 추론 제출까지의 흐름을 소유한다. React 를
 * 모르기 때문에 훅 없이 단위 테스트할 수 있고, presentation 은 이 세션을 켜고 끄면서
 * 보고만 받는다.
 *
 * 프레임·랜드마크는 이 안에서만 존재하며 밖으로 나가는 값은 상태와 판정 결과뿐이다.
 */

/** `HTMLMediaElement.HAVE_CURRENT_DATA`. 모듈 로드 시점에 DOM 전역을 읽지 않도록 상수로 둔다. */
const HAVE_CURRENT_DATA = 2;

/** 표본 추출 주기. 모델이 10fps 로 학습됐으므로 100ms 를 유지한다. */
export const DEFAULT_SAMPLE_INTERVAL_MS = 100;

export interface AttentionDetectionSessionOptions {
  /** 판정 대상 비디오 요소를 가져온다. 아직 없으면 null. */
  videoSource(): HTMLVideoElement | null;
  onStatus(status: AttentionStatus): void;
  onPrediction(prediction: AttentionPrediction): void;
  /** 표본 추출 주기(ms). 기본 100. */
  readonly sampleIntervalMs?: number;
  readonly scheduler?: FrameScheduler;
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
    videoSource,
    onStatus,
    onPrediction,
    sampleIntervalMs = DEFAULT_SAMPLE_INTERVAL_MS,
    scheduler = ANIMATION_FRAME_SCHEDULER,
    createLandmarker = createBrowserFaceLandmarker,
    createInferenceClient = createAttentionInferenceClient,
  } = options;

  let stopped = false;
  let landmarker: BrowserFaceLandmarker | null = null;
  let frameHandle: number | null = null;
  let lastSampleAtMs = Number.NEGATIVE_INFINITY;
  let judgementDisabled = false;
  let hasPrediction = false;
  const featureWindow = new RollingFeatureWindow();

  function stopLoop(): void {
    if (frameHandle === null) return;
    scheduler.cancel(frameHandle);
    frameHandle = null;
  }

  const inference = createInferenceClient({
    onPrediction(next: AttentionPrediction) {
      if (stopped) return;
      hasPrediction = true;
      onPrediction(next);
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
      judgementDisabled = true;
      stopLoop();
      featureWindow.clear();
      onStatus("unavailable");
    },
  });

  function scheduleNext(): void {
    if (stopped || judgementDisabled) return;
    frameHandle = scheduler.request(onFrame);
  }

  function sample(
    active: BrowserFaceLandmarker,
    video: HTMLVideoElement,
    timestampMs: number,
  ): void {
    let values: Float32Array | null;
    try {
      const detected = active.detect(video, timestampMs);
      values = detected === null ? null : extractFrameFeatures(detected);
    } catch (error) {
      // 한 프레임의 실패로 루프를 끊지 않는다.
      console.warn("[attention] 프레임 특징 추출 실패", error);
      return;
    }

    featureWindow.add(timestampMs, values);
    if (values === null) {
      onStatus("unmeasurable");
      return;
    }
    onStatus(hasPrediction ? "measuring" : "collecting");

    const tokens = featureWindow.tokens(timestampMs);
    if (tokens === null) return;
    // 다음 10초 창을 처음부터 다시 모은다.
    featureWindow.clear();
    inference.submit(tokens);
  }

  function onFrame(timestampMs: number): void {
    frameHandle = null;
    const video = videoSource();
    // 주기 안에 밀려 들어온 프레임은 버리고 가장 최신 프레임만 표본으로 쓴다.
    if (
      landmarker !== null &&
      video !== null &&
      video.readyState >= HAVE_CURRENT_DATA &&
      timestampMs - lastSampleAtMs >= sampleIntervalMs
    ) {
      lastSampleAtMs = timestampMs;
      sample(landmarker, video, timestampMs);
    }
    scheduleNext();
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
      scheduleNext();
    } catch (error) {
      if (stopped) return;
      console.warn("[attention] MediaPipe 준비 실패", error);
      onStatus("unavailable");
    }
  }

  void begin();

  return {
    stop(): void {
      stopped = true;
      stopLoop();
      landmarker?.close();
      landmarker = null;
      inference.terminate();
      featureWindow.clear();
    },
  };
}
