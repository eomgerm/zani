"use client";

import { useEffect, useRef, useState, type RefObject } from "react";

import type { AttentionStatus, AttentionPrediction } from "../domain/attentionPrediction";
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

/** `HTMLMediaElement.HAVE_CURRENT_DATA`. 모듈 로드 시점에 DOM 전역을 읽지 않도록 상수로 둔다. */
const HAVE_CURRENT_DATA = 2;

/** 표본 추출 주기. 모델이 10fps 로 학습됐으므로 100ms 를 유지한다. */
const DEFAULT_SAMPLE_INTERVAL_MS = 100;

/** 프레임 루프. 테스트에서 수동 스케줄러로 대체한다. */
export interface FrameScheduler {
  request(callback: (timestampMs: number) => void): number;
  cancel(handle: number): void;
}

const ANIMATION_FRAME_SCHEDULER: FrameScheduler = {
  request: (callback) => requestAnimationFrame(callback),
  cancel: (handle) => cancelAnimationFrame(handle),
};

/** 카메라 가용 상태. 스트림을 소유한 상위 화면이 판단해 내려준다. */
export type CameraAvailability = "on" | "off" | "denied";

export interface UseAttentionDetectionOptions {
  /** 판정 대상인 로컬 카메라 비디오 요소. */
  readonly videoRef: RefObject<HTMLVideoElement | null>;
  /** `on` 이 아니면 판정을 중단하고 상태만 알린다. */
  readonly camera: CameraAvailability;
  onPrediction?: (prediction: AttentionPrediction) => void;
  onStatusChange?: (status: AttentionStatus) => void;
  /** 표본 추출 주기(ms). 기본 100. */
  readonly sampleIntervalMs?: number;
  createLandmarker?: () => Promise<BrowserFaceLandmarker>;
  createInferenceClient?: (handlers: {
    onPrediction(prediction: AttentionPrediction): void;
    onFailure(failure: AttentionInferenceFailure): void;
  }) => AttentionInferenceClient;
  readonly scheduler?: FrameScheduler;
}

export interface AttentionDetectionState {
  readonly status: AttentionStatus;
  /** 가장 최근 10초 창의 판정 결과. 아직 없으면 null. */
  readonly prediction: AttentionPrediction | null;
}

/**
 * 판정 루프가 보고한 값. 어떤 카메라 세션의 보고인지 함께 담아, 카메라를 껐다 켜도
 * 이전 세션의 상태·결과가 남아 보이지 않게 한다.
 */
interface ReportedState {
  readonly camera: CameraAvailability;
  readonly status: AttentionStatus;
  readonly prediction: AttentionPrediction | null;
}

const INITIAL_REPORT: ReportedState = { camera: "off", status: "preparing", prediction: null };

/**
 * 로컬 카메라 프레임으로 참여도를 주기적으로 판정하는 훅.
 *
 * 메인 스레드는 MediaPipe 로 랜드마크를 뽑아 10초 창을 채우기만 하고, ONNX 추론은
 * Worker 에 넘긴다. 표본은 `sampleIntervalMs` 주기로만 뽑아 밀린 프레임은 버리고 항상
 * 최신 프레임만 처리한다. 프레임·랜드마크는 브라우저 밖으로 나가지 않으며 상위에는
 * 상태와 판정 결과만 노출한다.
 */
export function useAttentionDetection(
  options: UseAttentionDetectionOptions,
): AttentionDetectionState {
  const {
    videoRef,
    camera,
    onPrediction,
    onStatusChange,
    sampleIntervalMs = DEFAULT_SAMPLE_INTERVAL_MS,
    createLandmarker = createBrowserFaceLandmarker,
    createInferenceClient = createAttentionInferenceClient,
    scheduler = ANIMATION_FRAME_SCHEDULER,
  } = options;

  const [reported, setReported] = useState<ReportedState>(INITIAL_REPORT);

  // 카메라가 켜져 있지 않을 때의 상태는 입력만으로 정해지므로 렌더에서 파생한다.
  // 판정 루프가 아직 이번 세션을 보고하지 않았으면 준비 중으로 본다.
  const activeReport = reported.camera === camera ? reported : null;
  const status: AttentionStatus =
    camera === "denied"
      ? "permissionDenied"
      : camera === "off"
        ? "idle"
        : (activeReport?.status ?? "preparing");
  const prediction = camera === "on" ? (activeReport?.prediction ?? null) : null;

  // 콜백 identity 가 바뀌어도 판정 루프를 다시 시작하지 않도록 ref 로 미러링한다.
  const notifyRef = useRef({ onPrediction, onStatusChange });
  useEffect(() => {
    notifyRef.current = { onPrediction, onStatusChange };
  });

  const previousStatusRef = useRef<AttentionStatus | null>(null);
  useEffect(() => {
    if (previousStatusRef.current === status) return;
    previousStatusRef.current = status;
    notifyRef.current.onStatusChange?.(status);
  }, [status]);

  useEffect(() => {
    // 카메라 OFF·권한 거부는 상위 상태다. 판정을 시작하지 않는다(상태는 렌더에서 파생된다).
    if (camera !== "on") return;

    let cancelled = false;
    let landmarker: BrowserFaceLandmarker | null = null;
    let frameHandle: number | null = null;
    let lastSampleAtMs = Number.NEGATIVE_INFINITY;
    let judgementDisabled = false;
    let hasPrediction = false;
    const featureWindow = new RollingFeatureWindow();

    /** 이번 카메라 세션의 보고로 상태를 갱신한다. */
    function report(patch: Partial<Omit<ReportedState, "camera">>): void {
      setReported((current) => ({ ...current, ...patch, camera }));
    }

    function stopLoop(): void {
      if (frameHandle === null) return;
      scheduler.cancel(frameHandle);
      frameHandle = null;
    }

    const inference = createInferenceClient({
      onPrediction(next: AttentionPrediction) {
        if (cancelled) return;
        hasPrediction = true;
        report({ status: "measuring", prediction: next });
        notifyRef.current.onPrediction?.(next);
      },
      onFailure(failure: AttentionInferenceFailure) {
        if (cancelled) return;
        if (failure.kind !== "modelUnavailable") {
          // 세션은 살아 있으므로 다음 창에서 다시 시도한다.
          console.warn("[attention] 참여도 추론 실패", failure.message);
          return;
        }
        // 모델을 못 불러왔으면 판정만 비활성화하고 수업은 계속한다.
        judgementDisabled = true;
        stopLoop();
        featureWindow.clear();
        report({ status: "unavailable" });
      },
    });

    function scheduleNext(): void {
      if (cancelled || judgementDisabled) return;
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
        report({ status: "unmeasurable" });
        return;
      }
      report({ status: hasPrediction ? "measuring" : "collecting" });

      const tokens = featureWindow.tokens(timestampMs);
      if (tokens === null) return;
      // 다음 10초 창을 처음부터 다시 모은다.
      featureWindow.clear();
      inference.submit(tokens);
    }

    function onFrame(timestampMs: number): void {
      frameHandle = null;
      const video = videoRef.current;
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

    async function start(): Promise<void> {
      try {
        const created = await createLandmarker();
        if (cancelled) {
          created.close();
          return;
        }
        landmarker = created;
        report({ status: "collecting" });
        scheduleNext();
      } catch (error) {
        if (cancelled) return;
        console.warn("[attention] MediaPipe 준비 실패", error);
        report({ status: "unavailable" });
      }
    }

    void start();

    return () => {
      cancelled = true;
      stopLoop();
      landmarker?.close();
      landmarker = null;
      inference.terminate();
      featureWindow.clear();
      // 세션이 끝나는 시점에 보고를 버린다. 카메라를 껐다 켜면 camera 값이 "on" 으로
      // 되돌아와 세션을 값으로 구분할 수 없으므로, 여기서 지우지 않으면 이전 세션의
      // 판정이 새 세션의 최신 판정처럼 노출된다.
      setReported(INITIAL_REPORT);
    };
  }, [camera, videoRef, sampleIntervalMs, createLandmarker, createInferenceClient, scheduler]);

  return { status, prediction };
}
