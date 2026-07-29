"use client";

import { useEffect, useRef, useState } from "react";

const TICK_MS = 100;

export interface PromptTimerState {
  /** 남은 시간(ms). 0이 되면 더 이상 줄어들지 않는다. */
  readonly remainingMs: number;
}

/**
 * `active` 인 동안 `durationMs` 부터 0까지 100ms 간격으로 셈다운하고, 0에 닿으면 `onElapsed` 를
 * 한 번 호출한다. `active` 가 false 로 바뀌면 즉시 멈춘다(호출자가 응답을 받아 프롬프트를 닫은 경우).
 *
 * 세 확인 프롬프트(이해 확인·상세 안내·카메라 확인)가 이 타이머를 공유한다.
 */
export function usePromptTimer(
  active: boolean,
  durationMs: number,
  onElapsed: () => void,
): PromptTimerState {
  const [remainingMs, setRemainingMs] = useState(active ? durationMs : 0);

  const onElapsedRef = useRef(onElapsed);
  useEffect(() => {
    onElapsedRef.current = onElapsed;
  });

  useEffect(() => {
    // 초기 반영을 지연해 렌더-이펙트 동기 setState 를 피한다(useRoomParticipants 의
    // `setTimeout(update, 0)` 패턴과 동일).
    const initial = setTimeout(() => setRemainingMs(active ? durationMs : 0), 0);

    if (!active) {
      return () => clearTimeout(initial);
    }

    const startedAt = Date.now();
    const timer = setInterval(() => {
      const next = Math.max(0, durationMs - (Date.now() - startedAt));
      setRemainingMs(next);
      if (next === 0) {
        clearInterval(timer);
        onElapsedRef.current();
      }
    }, TICK_MS);

    return () => {
      clearTimeout(initial);
      clearInterval(timer);
    };
  }, [active, durationMs]);

  return { remainingMs };
}
