"use client";

import { useEffect, useState } from "react";

/** 종료 예정 시각 기준 남은 시간 표시를 시작하는 임계값(분). 서버의 자동 종료(3시간)와 짝을 이룬다. */
export const WARNING_THRESHOLD_MINUTES = 10;

const WARNING_THRESHOLD_MS = WARNING_THRESHOLD_MINUTES * 60 * 1000;
const TICK_MS = 1000;

export type SessionTimeWarningState = {
  /** 종료까지 남은 시간(ms). 이미 지났으면 0. */
  remainingMs: number;
  /** 남은 시간이 임계값 이하이고 아직 종료되지 않았다. */
  warning: boolean;
  /** 종료 예정 시각이 지났다(서버 스케줄러가 곧 종료한다). */
  expired: boolean;
};

function stateFor(expiresAtMs: number | null, nowMs: number): SessionTimeWarningState {
  if (expiresAtMs === null) {
    return { remainingMs: 0, warning: false, expired: false };
  }
  const remainingMs = Math.max(0, expiresAtMs - nowMs);
  return {
    remainingMs,
    warning: remainingMs > 0 && remainingMs <= WARNING_THRESHOLD_MS,
    expired: remainingMs === 0,
  };
}

function parse(expiresAt: string | undefined): number | null {
  if (!expiresAt) {
    return null;
  }
  const parsed = Date.parse(expiresAt);
  return Number.isNaN(parsed) ? null : parsed;
}

/**
 * 서버가 준 종료 예정 시각(`expiresAt`)만으로 남은 시간을 계산한다. 경고 전달용 서버 푸시 채널이 없어도 동작하도록
 * 클라이언트에서 1초마다 재계산하며, 값이 없거나 파싱할 수 없으면 아무 것도 알리지 않는다(경고 미표시).
 */
export function useSessionTimeWarning(expiresAt: string | undefined): SessionTimeWarningState {
  const expiresAtMs = parse(expiresAt);
  const [state, setState] = useState<SessionTimeWarningState>({
    remainingMs: 0,
    warning: false,
    expired: false,
  });

  useEffect(() => {
    const update = () => setState(stateFor(expiresAtMs, Date.now()));
    // 초기 계산을 effect 본문 밖(지연)으로 빼서 렌더-이펙트 동기 setState를 피한다.
    const initial = setTimeout(update, 0);

    if (expiresAtMs === null) {
      return () => clearTimeout(initial);
    }

    const timer = setInterval(update, TICK_MS);
    return () => {
      clearTimeout(initial);
      clearInterval(timer);
    };
  }, [expiresAtMs]);

  return state;
}
