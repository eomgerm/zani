"use client";

import { useEffect, useRef, useState } from "react";

import {
  startAttentionDetection,
  type AttentionDetectionSessionOptions,
} from "../application/attentionDetectionSession";
import { createDetectionReportController } from "../application/detectionReportController";
import { ATTENTION_DETECTION_CONFIG } from "../domain/attentionDetectionConfig";
import type { AttentionPrediction, AttentionStatus } from "../domain/attentionPrediction";
import type { DetectorReport } from "../domain/detectionOutcome";
import { createAttentionInferenceClient } from "../infrastructure/attentionInferenceClient";
import { createAttentionFeatureDetector } from "../infrastructure/faceLandmarker";
import { createTrackProcessorFrameSource } from "../infrastructure/trackProcessorFrameSource";

/** 카메라 가용 상태. 스트림을 소유한 상위 화면이 판단해 내려준다. */
export type CameraAvailability = "on" | "off" | "denied";

export interface UseAttentionDetectionOptions
  extends Partial<Pick<
    AttentionDetectionSessionOptions,
    "createFeatureDetector" | "createInferenceClient" | "createFrameSource"
  >> {
  /** `on` 이 아니면 판정을 중단하고 상태만 알린다. */
  readonly camera: CameraAvailability;
  /** Worker TrackProcessor가 읽을 LiveKit 로컬 카메라 트랙. */
  readonly track: MediaStreamTrack | null;
  onPrediction?: (prediction: AttentionPrediction) => void;
  onStatusChange?: (status: AttentionStatus) => void;
  onReport?: (report: DetectorReport) => void;
}
// `createFeatureDetector`·`createInferenceClient`·`createFrameSource` 는 세션을 다시 시작할지 판단하는
// 의존성이다. 넘길 거라면 반드시 안정적인 참조여야 한다(렌더마다 새로 만든 함수를 주면
// 세션이 매 렌더 재시작되며 루프가 돈다). 생략하면 모듈 상수 기본값이 쓰인다.

export interface AttentionDetectionState {
  readonly status: AttentionStatus;
  /** 가장 최근 10초 창의 판정 결과. 아직 없으면 null. */
  readonly prediction: AttentionPrediction | null;
}

/**
 * 판정 세션이 보고한 값. 어떤 카메라 세션의 보고인지 함께 담아, 카메라를 껐다 켜도
 * 이전 세션의 상태·결과가 남아 보이지 않게 한다.
 */
interface ReportedState {
  readonly camera: CameraAvailability;
  readonly status: AttentionStatus;
  readonly prediction: AttentionPrediction | null;
}

const INITIAL_REPORT: ReportedState = { camera: "off", status: "preparing", prediction: null };

function isUsableCameraTrack(track: MediaStreamTrack | null): track is MediaStreamTrack {
  return track !== null && track.readyState === "live" && !track.muted;
}

/**
 * 참여도 판정 세션을 카메라 상태에 맞춰 켜고 끄며, 결과를 React 상태로 노출하는 훅.
 *
 * 판정 흐름 자체는 `application/attentionDetectionSession` 이 소유한다. 이 훅은 세션
 * 수명주기와 렌더 상태만 다룬다.
 */
export function useAttentionDetection(
  options: UseAttentionDetectionOptions,
): AttentionDetectionState {
  const {
    camera,
    track,
    onPrediction,
    onStatusChange,
    onReport,
    createFeatureDetector,
    createInferenceClient,
    createFrameSource,
  } = options;

  const effectiveCamera: CameraAvailability =
    camera === "on" && !isUsableCameraTrack(track) ? "off" : camera;

  const [reported, setReported] = useState<ReportedState>(INITIAL_REPORT);

  // 카메라가 켜져 있지 않을 때의 상태는 입력만으로 정해지므로 렌더에서 파생한다.
  // 세션이 아직 이번 카메라를 보고하지 않았으면 준비 중으로 본다.
  const activeReport = reported.camera === effectiveCamera ? reported : null;
  const status: AttentionStatus =
    effectiveCamera === "denied"
      ? "permissionDenied"
      : effectiveCamera === "off"
        ? "idle"
        : (activeReport?.status ?? "preparing");
  const prediction = effectiveCamera === "on" ? (activeReport?.prediction ?? null) : null;

  // 콜백 identity 가 바뀌어도 판정 세션을 다시 시작하지 않도록 ref 로 미러링한다.
  const notifyRef = useRef({ onPrediction, onStatusChange, onReport });
  useEffect(() => {
    notifyRef.current = { onPrediction, onStatusChange, onReport };
  });

  const previousStatusRef = useRef<AttentionStatus | null>(null);
  useEffect(() => {
    if (previousStatusRef.current === status) return;
    previousStatusRef.current = status;
    notifyRef.current.onStatusChange?.(status);
  }, [status]);

  useEffect(() => {
    const reports = createDetectionReportController({
      reportIntervalMs: ATTENTION_DETECTION_CONFIG.reportIntervalMs,
      isReportingAllowed: () => typeof document === "undefined" || !document.hidden,
      onReport: (report) => notifyRef.current.onReport?.(report),
    });

    // 카메라 OFF·권한 거부는 즉시 CAMERA_OFF를 보고하고 판정 세션은 시작하지 않는다.
    if (effectiveCamera !== "on" || !isUsableCameraTrack(track)) {
      reports.startImmediate({ outcome: "CAMERA_OFF" });
      return () => reports.stop();
    }

    const session = startAttentionDetection({
      createFeatureDetector: createFeatureDetector ?? createAttentionFeatureDetector,
      createInferenceClient: createInferenceClient ?? createAttentionInferenceClient,
      createFrameSource:
        createFrameSource ??
        ((handlers) => createTrackProcessorFrameSource({ track, ...handlers })),
      onStatus(next) {
        // 세션은 표본마다 상태를 보고한다. 값이 그대로면 같은 객체를 돌려주어
        // 초당 10번씩 리렌더가 도는 것을 막는다.
        setReported((current) =>
          current.camera === effectiveCamera && current.status === next
            ? current
            : { ...current, status: next, camera: effectiveCamera },
        );
      },
      onPrediction(next) {
        setReported((current) => ({ ...current, prediction: next, camera: effectiveCamera }));
        notifyRef.current.onPrediction?.(next);
      },
      onDetection(output) {
        if (output.outcome === "DETECTOR_UNAVAILABLE") {
          reports.startImmediate({ outcome: "DETECTOR_UNAVAILABLE" });
          return;
        }
        reports.stop();
        reports.reportWindow(output);
      },
    });

    return () => {
      session.stop();
      reports.stop();
      // 세션이 끝나는 시점에 보고를 버린다. 카메라를 껐다 켜면 camera 값이 "on" 으로
      // 되돌아와 세션을 값으로 구분할 수 없으므로, 여기서 지우지 않으면 이전 세션의
      // 판정이 새 세션의 최신 판정처럼 노출된다.
      setReported(INITIAL_REPORT);
    };
  }, [
    effectiveCamera,
    track,
    createFeatureDetector,
    createInferenceClient,
    createFrameSource,
  ]);

  return { status, prediction };
}
