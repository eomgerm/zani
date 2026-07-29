"use client";

import { useEffect, useRef, useState } from "react";

import {
  coachingAvailabilityOf,
  type CoachingAvailability,
} from "../domain/coachingAvailability";
import { pollCoach, type CoachPoller, type CoachPollResult } from "../infrastructure/coachPollApi";

/** 폴링 주기(ms). 85 결정. 팁은 단일 글로벌 쿨타임 10분이라 이보다 촘촘할 이유가 없다. */
export const COACH_POLL_INTERVAL_MS = 10_000;

export interface UseCoachingStatusOptions {
  readonly sessionId: string;
  /** 강사 화면에서만 돈다. 학생은 팁을 받지 않는다(86 요구사항). */
  readonly enabled: boolean;
  poll?: CoachPoller;
  /** 폴링이 성공할 때마다 결과를 넘긴다. 팁 카드(86)가 여기서 팁을 받아 간다. */
  onResult?: (result: CoachPollResult) => void;
}

export interface UseCoachingStatusResult {
  readonly availability: CoachingAvailability;
}

/**
 * 강사 코칭 가용 상태(티켓 76).
 *
 * 10초마다 조회해 서버가 알려준 미표시 사유를 강사에게 보여줄 상태로 접는다. 조회 자체가
 * 실패하면 `POLL_FAILED` 다 — 서버가 팁을 만들었더라도 받지 못하면 강사에겐 같은 결과다.
 *
 * 실패해도 예외를 올리지 않는다. 코칭이 죽는 것이 수업을 막아서는 안 된다(76 요구사항).
 *
 * <p><b>지금은 강사 화면에 `POLL_FAILED` 배지가 계속 뜬다.</b> 엔드포인트(85)와 팁 생성(204)이
 * 아직 없어 폴링이 404 를 받기 때문이며, 코칭이 실제로 동작하지 않는 상태를 정직하게 보여주는
 * 것이라 결함이 아니다. 두 티켓이 붙으면 저절로 사라진다.
 */
export function useCoachingStatus(options: UseCoachingStatusOptions): UseCoachingStatusResult {
  const { sessionId, enabled, poll = pollCoach, onResult } = options;

  // 폴링으로 알게 된 값만 상태로 둔다. 꺼져 있을 때의 "ACTIVE" 는 렌더에서 파생한다 —
  // 효과 안에서 setState 로 되돌리면 불필요한 렌더가 한 번 더 돈다.
  const [polled, setPolled] = useState<CoachingAvailability>("ACTIVE");

  // 콜백·폴러 identity 가 바뀌어도 주기를 다시 잡지 않도록 ref 로 미러링한다.
  const pollRef = useRef(poll);
  const onResultRef = useRef(onResult);
  useEffect(() => {
    pollRef.current = poll;
    onResultRef.current = onResult;
  });

  useEffect(() => {
    if (!enabled) return;

    let cancelled = false;
    const controller = new AbortController();

    const run = async () => {
      try {
        const result = await pollRef.current(sessionId, controller.signal);
        if (cancelled) return;
        setPolled(coachingAvailabilityOf(result.unavailableReason));
        onResultRef.current?.(result);
      } catch {
        // 조회 실패는 강사에게 알리되 수업은 그대로 둔다.
        if (!cancelled) setPolled("POLL_FAILED");
      }
    };

    void run();
    const timer = setInterval(run, COACH_POLL_INTERVAL_MS);

    return () => {
      cancelled = true;
      controller.abort();
      clearInterval(timer);
    };
  }, [sessionId, enabled]);

  return { availability: enabled ? polled : "ACTIVE" };
}
