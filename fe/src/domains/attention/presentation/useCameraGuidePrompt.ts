"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import {
  sessionStorageCameraGuideSuppression,
  type CameraGuideSuppressionStore,
} from "../infrastructure/cameraGuideSuppression";
import { isTabHidden, useTabVisibleTick } from "./promptVisibility";
import { usePromptTimer } from "./usePromptTimer";
import type { CameraAvailability } from "./useAttentionDetection";

/** 표시 시간(초). 기준 문서 §5 공통 규칙. */
export const CAMERA_GUIDE_SECONDS = 30;
/** 카메라가 이만큼 꺼져 있으면 뜬다. 분모에서 빼는 시점(§7.1)과 같은 값이다. */
export const CAMERA_GUIDE_OFF_DURATION_MS = 60_000;
/** "지금 켤게요"·무응답으로 닫힌 뒤 다시 권하기까지의 간격(§5.2). */
export const CAMERA_GUIDE_REMINDER_MS = 5 * 60_000;

const CAMERA_GUIDE_DURATION_MS = CAMERA_GUIDE_SECONDS * 1000;

/** 안내 문구를 가르는 원인(§5.2). 상태 자체는 셋 다 `CAMERA_OFF` 하나다. */
export type CameraGuideCause = "disabled" | "denied" | "muted";

/** 학생이 고를 수 있는 답. 서버로 보내지 않는다(§6). */
export type CameraGuideAnswer = "WILL_ENABLE" | "CANNOT_ENABLE";

export interface CameraGuidePrompt {
  readonly promptId: string;
  readonly cause: CameraGuideCause;
  readonly remainingMs: number;
  readonly durationMs: number;
}

export interface UseCameraGuidePromptOptions {
  /** "못 켜요" 억제를 수업 단위로 묶는 키. */
  readonly sessionId: string;
  /** 스트림을 소유한 화면이 트랙을 보고 내려준다. `on` 이 아니면 꺼진 것으로 센다. */
  readonly camera: CameraAvailability;
  /**
   * 어떤 이유로든(응답·무응답·카메라 복귀) 프롬프트가 닫힐 때 호출된다.
   * 판정 파이프라인(75)이 연속 카운터를 0으로 되돌리는 신호다(§5).
   */
  onClosed?: () => void;
  /** "못 켜요" 억제를 새로고침 뒤에도 유지하기 위한 저장소. 테스트가 가짜 구현을 넣는다. */
  suppressionStore?: CameraGuideSuppressionStore;
  /**
   * 이 프롬프트를 쓸 화면인지. 학생 프롬프트라 강사 화면에서는 꺼둔다(§5).
   * false 로 바뀌면 떠 있던 프롬프트도 닫는다.
   */
  enabled?: boolean;
}

export interface UseCameraGuidePromptResult {
  readonly prompt: CameraGuidePrompt | null;
  /** "지금 켤게요" / "못 켜요". 어느 쪽도 집계를 바꾸지 않는다(§5.2). */
  answer(value: CameraGuideAnswer): void;
}

const causeOf = (camera: CameraAvailability): CameraGuideCause =>
  camera === "denied" ? "denied" : camera === "muted" ? "muted" : "disabled";


/**
 * "카메라 안내" 프롬프트. 카메라가 1분 이어서 꺼져 있으면 뜬다.
 *
 * 응답은 서버로 보내지 않고 집계도 바꾸지 않는다(§5.2) — 답에 따라 분모가 달라지면 학생에게
 * "못 켜요"가 언제나 유리해져 답이 왜곡된다. 집계에서 빼는 판단은 꺼져 있는 시간이 자동으로 한다.
 *
 * "못 켜요"를 고르면 수업이 끝날 때까지 다시 띄우지 않고, 그 외에는 5분 뒤 다시 권한다.
 * 도중에 카메라가 켜지면 목적이 달성됐으므로 그 자리에서 닫는다.
 */
export function useCameraGuidePrompt(
  options: UseCameraGuidePromptOptions,
): UseCameraGuidePromptResult {
  const {
    sessionId,
    camera,
    onClosed,
    suppressionStore = sessionStorageCameraGuideSuppression,
    enabled = true,
  } = options;
  const cameraOff = enabled && camera !== "on";

  const [prompt, setPrompt] = useState<{ promptId: string; cause: CameraGuideCause } | null>(null);
  /** 카메라가 꺼진 시각. 켜질 때만 지운다 — 프롬프트가 닫혀도 계속 센다. */
  const offSinceRef = useRef<number | null>(null);
  const lastClosedAtRef = useRef<number | null>(null);
  const suppressedRef = useRef(false);
  const promptRef = useRef<typeof prompt>(null);
  useEffect(() => {
    promptRef.current = prompt;
  });

  const visibleTick = useTabVisibleTick();
  const [armTick, setArmTick] = useState(0);
  const rearm = useCallback(() => setArmTick((value) => value + 1), []);

  const onClosedRef = useRef(onClosed);
  useEffect(() => {
    onClosedRef.current = onClosed;
  });

  const suppressionStoreRef = useRef(suppressionStore);
  useEffect(() => {
    suppressionStoreRef.current = suppressionStore;
  });

  // 새로고침·재입장으로 훅이 다시 마운트돼도 "못 켜요"는 유지돼야 한다.
  useEffect(() => {
    suppressedRef.current = suppressionStoreRef.current.isSuppressed(sessionId);
  }, [sessionId]);

  const close = useCallback(() => {
    if (promptRef.current === null) return;
    lastClosedAtRef.current = Date.now();
    setPrompt(null);
    rearm();
    onClosedRef.current?.();
  }, [rearm]);

  const { remainingMs } = usePromptTimer(prompt !== null, CAMERA_GUIDE_DURATION_MS, close);

  // 카메라가 꺼진 시각을 기록하고, 켜지면 즉시 닫는다.
  useEffect(() => {
    if (cameraOff) {
      if (offSinceRef.current === null) {
        offSinceRef.current = Date.now();
        rearm();
      }
      return;
    }

    offSinceRef.current = null;
    lastClosedAtRef.current = null;
    if (promptRef.current !== null) {
      // 재권유 간격을 남기지 않고 닫는다 — 카메라가 다시 꺼지면 1분부터 새로 센다.
      setPrompt(null);
      promptRef.current = null;
      onClosedRef.current?.();
    }
  }, [cameraOff, rearm]);

  // 발동 시점을 계산해 한 번만 예약한다. 숨은 탭이면 다시 보일 때 재검사한다.
  useEffect(() => {
    if (!cameraOff || suppressedRef.current || prompt !== null) return;

    const offSince = offSinceRef.current;
    if (offSince === null) return;

    const lastClosedAt = lastClosedAtRef.current;
    const eligibleAt = Math.max(
      offSince + CAMERA_GUIDE_OFF_DURATION_MS,
      lastClosedAt === null ? 0 : lastClosedAt + CAMERA_GUIDE_REMINDER_MS,
    );

    const timer = setTimeout(
      () => {
        if (suppressedRef.current || isTabHidden()) return; // 숨은 탭이면 다시 보일 때 재검사한다.
        setPrompt({ promptId: `camera-${Date.now()}`, cause: causeOf(camera) });
      },
      Math.max(0, eligibleAt - Date.now()),
    );

    return () => clearTimeout(timer);
  }, [cameraOff, camera, prompt, armTick, visibleTick]);

  const answer = useCallback(
    (value: CameraGuideAnswer) => {
      if (promptRef.current === null) return;
      if (value === "CANNOT_ENABLE") {
        // 수업 내내 다시 띄우지 않는다. 새로고침을 견디도록 저장까지 한다.
        suppressedRef.current = true;
        suppressionStoreRef.current.suppress(sessionId);
      }
      close();
    },
    [close, sessionId],
  );

  return {
    prompt:
      prompt === null
        ? null
        : { ...prompt, remainingMs, durationMs: CAMERA_GUIDE_DURATION_MS },
    answer,
  };
}
