"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import {
  sendPromptResponse,
  type PromptResponseSender,
  type PromptResponseValue,
} from "../infrastructure/promptResponseApi";
import { usePromptTimer } from "./usePromptTimer";

export type { PromptResponseValue as UnderstandingCheckResponse } from "../infrastructure/promptResponseApi";

/** 표시 시간(초). 실시간 코칭 기준 문서 §3 기준. */
export const UNDERSTANDING_CHECK_SECONDS = 30;
/** 같은 학생에게 다시 띄우기 전 최소 간격(ms). 마지막으로 표시된 시각부터 잰다. */
export const UNDERSTANDING_CHECK_COOLDOWN_MS = 5 * 60_000;

const UNDERSTANDING_CHECK_DURATION_MS = UNDERSTANDING_CHECK_SECONDS * 1000;

export interface UnderstandingCheckPrompt {
  readonly promptId: string;
  readonly remainingMs: number;
  readonly durationMs: number;
}

export interface UseUnderstandingCheckPromptOptions {
  readonly sessionId: string;
  sendResponse?: PromptResponseSender;
  /** 30초 동안 무응답으로 자동 종료됐을 때 호출된다(서버 전송 없음 — 무전송 자체가 신호). */
  onTimedOut?: () => void;
}

export interface UseUnderstandingCheckPromptResult {
  readonly prompt: UnderstandingCheckPrompt | null;
  /** 판정 파이프라인(75)이 "이해 확인이 필요하다"고 알릴 때 호출한다. */
  trigger(promptId: string): void;
  respond(value: PromptResponseValue): void;
}

/**
 * "이해 확인" 프롬프트(3택). 30초 안에 응답하지 않으면 무응답으로 로컬 처리하고, 응답하면
 * 서버로 전송한다. 마지막으로 표시된 시각으로부터 5분 이내에는 재트리거를 무시한다.
 * 전송 실패는 수업 진행을 막지 않도록 조용히 삼킨다.
 */
export function useUnderstandingCheckPrompt(
  options: UseUnderstandingCheckPromptOptions,
): UseUnderstandingCheckPromptResult {
  const { sessionId, sendResponse = sendPromptResponse, onTimedOut } = options;

  const [promptId, setPromptId] = useState<string | null>(null);
  const answeredRef = useRef(false);
  const lastShownAtRef = useRef<number | null>(null);
  const promptIdRef = useRef<string | null>(null);
  useEffect(() => {
    promptIdRef.current = promptId;
  });

  const close = useCallback(() => setPromptId(null), []);

  const handleElapsed = useCallback(() => {
    if (answeredRef.current) return;
    answeredRef.current = true;
    close();
    onTimedOut?.();
  }, [close, onTimedOut]);

  const { remainingMs } = usePromptTimer(
    promptId !== null,
    UNDERSTANDING_CHECK_DURATION_MS,
    handleElapsed,
  );

  const trigger = useCallback((nextPromptId: string) => {
    if (promptIdRef.current !== null) return; // 이미 하나가 떠 있으면 무시한다.
    const lastShownAt = lastShownAtRef.current;
    if (lastShownAt !== null && Date.now() - lastShownAt < UNDERSTANDING_CHECK_COOLDOWN_MS) {
      return; // 쿨다운 중.
    }
    lastShownAtRef.current = Date.now();
    answeredRef.current = false;
    setPromptId(nextPromptId);
  }, []);

  const respond = useCallback(
    (value: PromptResponseValue) => {
      const currentPromptId = promptIdRef.current;
      if (answeredRef.current || currentPromptId === null) return; // 중복 응답 방지.
      answeredRef.current = true;
      close();
      sendResponse(sessionId, currentPromptId, value).catch(() => {
        // 전송 실패는 수업 화면을 막지 않는다 — 조용히 무시한다.
      });
    },
    [close, sendResponse, sessionId],
  );

  return {
    prompt:
      promptId === null
        ? null
        : { promptId, remainingMs, durationMs: UNDERSTANDING_CHECK_DURATION_MS },
    trigger,
    respond,
  };
}
