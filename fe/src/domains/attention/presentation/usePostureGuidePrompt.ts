"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { isTabHidden } from "./promptVisibility";
import { usePromptTimer } from "./usePromptTimer";

/** 표시 시간(초). 기준 문서 §5 공통 규칙. */
export const POSTURE_GUIDE_SECONDS = 30;
/** 이전 판의 "쿨타임 무시"를 5분으로 바꿨다(§5.1) — 상한이 없으면 자리를 비운 학생에게 30초마다 뜬다. */
export const POSTURE_GUIDE_COOLDOWN_MS = 5 * 60_000;

const POSTURE_GUIDE_DURATION_MS = POSTURE_GUIDE_SECONDS * 1000;

export interface PostureGuidePrompt {
  readonly promptId: string;
  readonly remainingMs: number;
  readonly durationMs: number;
}

export interface UsePostureGuidePromptOptions {
  /** 프롬프트가 닫힐 때 호출된다. 판정 파이프라인(75)이 연속 카운터를 0으로 되돌리는 신호다(§5). */
  onClosed?: () => void;
}

export interface UsePostureGuidePromptResult {
  readonly prompt: PostureGuidePrompt | null;
  /** 판정 파이프라인(75)이 `UNMEASURABLE` 3연속을 확인했을 때 호출한다. */
  trigger(promptId: string): void;
  /** "확인" 버튼. 선택지가 하나뿐이라 값이 없다. */
  acknowledge(): void;
}

/**
 * "자세 안내" 프롬프트(확인 1택). `UNMEASURABLE` 3연속에 뜨고 30초 뒤 저절로 닫힌다.
 *
 * 응답은 서버로 보내지 않는다(§6) — 확인 버튼 하나뿐이라 담긴 정보가 없다.
 * 떠 있는 동안 얼굴이 다시 잡혀도 30초를 그대로 채운다(§5.1) — 얼굴 검출은 프레임 단위로
 * 흔들려서 "해결됐다"고 판단하기가 불안정하기 때문이다.
 */
export function usePostureGuidePrompt(
  options: UsePostureGuidePromptOptions = {},
): UsePostureGuidePromptResult {
  const { onClosed } = options;

  const [promptId, setPromptId] = useState<string | null>(null);
  const lastClosedAtRef = useRef<number | null>(null);
  const promptIdRef = useRef<string | null>(null);
  useEffect(() => {
    promptIdRef.current = promptId;
  });

  const onClosedRef = useRef(onClosed);
  useEffect(() => {
    onClosedRef.current = onClosed;
  });

  const close = useCallback(() => {
    if (promptIdRef.current === null) return;
    lastClosedAtRef.current = Date.now();
    setPromptId(null);
    onClosedRef.current?.();
  }, []);

  const { remainingMs } = usePromptTimer(promptId !== null, POSTURE_GUIDE_DURATION_MS, close);

  const trigger = useCallback((nextPromptId: string) => {
    if (promptIdRef.current !== null) return; // 이미 떠 있으면 무시한다.
    if (isTabHidden()) return;
    const lastClosedAt = lastClosedAtRef.current;
    if (lastClosedAt !== null && Date.now() - lastClosedAt < POSTURE_GUIDE_COOLDOWN_MS) {
      return; // 쿨다운 중. 이해 확인과 따로 잰다(§5).
    }
    setPromptId(nextPromptId);
  }, []);

  return {
    prompt:
      promptId === null
        ? null
        : { promptId, remainingMs, durationMs: POSTURE_GUIDE_DURATION_MS },
    trigger,
    acknowledge: close,
  };
}
