"use client";

import { useCallback, useRef, useState } from "react";

import type { CoachPollResult, CoachTip } from "../infrastructure/coachPollApi";

export interface UseCoachTipCardResult {
  /** 지금 띄울 팁. 없으면 null. */
  readonly tip: CoachTip | null;
  /** 폴링 결과를 넘긴다. `useCoachingStatus` 의 `onResult` 에 그대로 연결한다. */
  accept(result: CoachPollResult): void;
  /** 확인·닫기. 둘 다 같은 동작이라 하나로 둔다 — 서버로 보내는 것이 없다. */
  dismiss(): void;
}

/**
 * 강사 수업 팁 카드의 표시 상태(티켓 86).
 *
 * <h3>같은 트리거를 다시 받아도 한 번만 띄운다</h3>
 *
 * 폴링은 10초마다 도는데 서버는 쿨타임(10분) 동안 같은 트리거를 계속 내려줄 수 있다. 강사가
 * 카드를 닫아도 다음 조회에서 되살아나면 닫을 수가 없다. 그래서 한 번 다룬 `triggerId` 는
 * 기억해 두고 무시한다.
 *
 * 폴링을 여기서 새로 만들지 않는 것도 중요하다. 그 폴링이 곧 트리거 판정이라(85) 카드가 자기
 * 폴러를 두면 분모 조회가 두 배로 돌고 쿨타임을 두 주체가 소모한다. `useCoachingStatus` 의
 * 결과를 `accept` 로 받아 쓴다.
 */
export function useCoachTipCard(): UseCoachTipCardResult {
  const [tip, setTip] = useState<CoachTip | null>(null);
  /** 이미 띄웠거나 닫은 트리거. 같은 값이 다시 와도 카드를 되살리지 않는다. */
  const handledRef = useRef<string | null>(null);

  const accept = useCallback((result: CoachPollResult) => {
    const { triggerId, tip: incoming } = result;

    // 팁이 없는 응답(미표시 사유 포함)은 아무 일도 하지 않는다. 떠 있는 카드도 건드리지 않는다 —
    // 강사가 읽는 중에 다음 폴링이 지우면 안 된다.
    if (triggerId === null || incoming === null) return;
    if (handledRef.current === triggerId) return;

    handledRef.current = triggerId;
    setTip(incoming); // 새 팁은 이전 팁을 대체한다.
  }, []);

  const dismiss = useCallback(() => setTip(null), []);

  return { tip, accept, dismiss };
}
