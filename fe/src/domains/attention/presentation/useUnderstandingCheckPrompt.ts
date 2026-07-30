"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { useAuth } from "@/domains/auth";
import {
  sendPromptResponse,
  type PromptAnswer,
  type PromptResponseSender,
} from "../infrastructure/promptResponseApi";
import { isTabHidden } from "./promptVisibility";
import { usePromptTimer } from "./usePromptTimer";

/** 학생이 직접 고를 수 있는 답. 무응답은 훅이 `NON_RESPONSE` 로 대신 보낸다. */
export type UnderstandingCheckResponse = Exclude<PromptAnswer, "NON_RESPONSE">;

/** 표시 시간(초). 실시간 코칭 기준 문서 §3 기준. */
export const UNDERSTANDING_CHECK_SECONDS = 30;
/** 같은 학생에게 다시 띄우기 전 최소 간격(ms). 기준 문서 §3 에 따라 **닫힌 시각**부터 잰다. */
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
  /** 30초 동안 응답이 없어 자동으로 닫혔을 때 호출된다. `NON_RESPONSE` 전송은 훅이 알아서 한다. */
  onTimedOut?: () => void;
  /**
   * 어떤 이유로든 프롬프트가 닫힐 때 호출된다. 판정 파이프라인(75)이 연속 카운터를 0으로
   * 되돌리는 신호다(§5). 무응답으로 닫힌 경우에는 `onTimedOut` 과 함께 호출된다.
   */
  onClosed?: () => void;
}

export interface UseUnderstandingCheckPromptResult {
  readonly prompt: UnderstandingCheckPrompt | null;
  /** 판정 파이프라인(75)이 "이해 확인이 필요하다"고 알릴 때 호출한다. */
  trigger(promptId: string): void;
  /** 전송 성공 여부를 반환한다(reject 하지 않음 — 실패해도 수업 화면을 막지 않는다). */
  respond(value: UnderstandingCheckResponse): Promise<boolean>;
}

/**
 * "이해 확인" 프롬프트(3택). 30초 안에 응답하지 않으면 `NON_RESPONSE` 로 대신 보내고 닫는다 —
 * 무전송을 신호로 쓰면 서버가 학생의 무응답과 브라우저 중단을 구분할 수 없다.
 * 닫힌 시각으로부터 5분 이내에는 재트리거를 무시한다.
 * 전송 실패는 수업 진행을 막지 않도록 조용히 삼킨다.
 */
export function useUnderstandingCheckPrompt(
  options: UseUnderstandingCheckPromptOptions,
): UseUnderstandingCheckPromptResult {
  const { sessionId, sendResponse = sendPromptResponse, onTimedOut, onClosed } = options;
  // 서버는 Bearer 토큰으로 요청자가 이 세션의 학생인지 본다. 쿠키는 refresh 전용이라 헤더가 없으면 401 이다.
  const { accessToken } = useAuth();

  const [promptId, setPromptId] = useState<string | null>(null);
  const answeredRef = useRef(false);
  const shownAtRef = useRef<string | null>(null);
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
    lastClosedAtRef.current = Date.now();
    setPromptId(null);
    onClosedRef.current?.();
  }, []);

  const send = useCallback(
    async (targetPromptId: string, answer: PromptAnswer): Promise<boolean> => {
      const shownAt = shownAtRef.current;
      if (shownAt === null || accessToken === null) return false;
      try {
        await sendResponse(
          sessionId,
          targetPromptId,
          {
            kind: "UNDERSTANDING_CHECK",
            answer,
            shownAt,
            respondedAt: new Date().toISOString(),
          },
          accessToken,
        );
        return true;
      } catch {
        // 전송 실패는 수업 화면을 막지 않는다 — 호출자가 조용히 넘어갈 수 있도록 false 만 반환한다.
        return false;
      }
    },
    [accessToken, sendResponse, sessionId],
  );

  const handleElapsed = useCallback(() => {
    if (answeredRef.current) return;
    answeredRef.current = true;
    const timedOutPromptId = promptIdRef.current;
    close();
    if (timedOutPromptId !== null) {
      void send(timedOutPromptId, "NON_RESPONSE");
    }
    onTimedOut?.();
  }, [close, send, onTimedOut]);

  const { remainingMs } = usePromptTimer(
    promptId !== null,
    UNDERSTANDING_CHECK_DURATION_MS,
    handleElapsed,
  );

  const trigger = useCallback((nextPromptId: string) => {
    if (promptIdRef.current !== null) return; // 이미 하나가 떠 있으면 무시한다.
    if (isTabHidden()) return; // 안 보이는 화면에 띄우면 무응답으로 닫히고 쿨타임만 깎인다(§4.3).
    const lastClosedAt = lastClosedAtRef.current;
    if (lastClosedAt !== null && Date.now() - lastClosedAt < UNDERSTANDING_CHECK_COOLDOWN_MS) {
      return; // 쿨다운 중.
    }
    shownAtRef.current = new Date().toISOString();
    answeredRef.current = false;
    setPromptId(nextPromptId);
  }, []);

  const respond = useCallback(
    async (value: UnderstandingCheckResponse): Promise<boolean> => {
      const currentPromptId = promptIdRef.current;
      if (answeredRef.current || currentPromptId === null) return false; // 중복 응답 방지.
      answeredRef.current = true;
      close();
      return send(currentPromptId, value);
    },
    [close, send],
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
