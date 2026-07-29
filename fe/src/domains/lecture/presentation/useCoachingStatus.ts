"use client";

import { useEffect, useRef, useState } from "react";

import { useAuth } from "@/domains/auth";
import {
  coachingAvailabilityOf,
  type CoachingAvailability,
} from "../domain/coachingAvailability";
import {
  CoachPollError,
  pollCoach,
  type CoachPoller,
  type CoachPollResult,
} from "../infrastructure/coachPollApi";

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
 * 10초마다 조회하고, **폴링이 연속으로 실패할 때만** 강사에게 알린다. 서버가 알려준 미표시
 * 사유는 배지에 쓰지 않는다 — 트리거 하나가 실패한 것이지 코칭이 죽은 것이 아니다(85 계약).
 * 근거는 `coachingAvailability.ts` 에 있다.
 *
 * 실패해도 예외를 올리지 않는다. 코칭이 죽는 것이 수업을 막아서는 안 된다(76 요구사항).
 *
 * <p>주기는 `setInterval` 이 아니라 응답을 받은 뒤 다음 조회를 예약하는 방식이다. 이 폴링이
 * 곧 트리거 판정이라(85) 겹쳐 돌면 분모 조회와 쿨타임 소모가 두 번 일어난다.
 *
 * <p><b>지금은 강사 화면에 `POLL_FAILED` 배지가 계속 뜬다.</b> 엔드포인트(85)와 팁 생성(204)이
 * 아직 없어 폴링이 404 를 받기 때문이며, 코칭이 실제로 동작하지 않는 상태를 정직하게 보여주는
 * 것이라 결함이 아니다. 두 티켓이 붙으면 저절로 사라진다.
 */
export function useCoachingStatus(options: UseCoachingStatusOptions): UseCoachingStatusResult {
  const { sessionId, enabled, poll = pollCoach, onResult } = options;
  const { accessToken } = useAuth();

  // 연속 실패 횟수만 상태로 둔다. 꺼져 있을 때의 "ACTIVE" 는 렌더에서 파생한다 —
  // 효과 안에서 setState 로 되돌리면 불필요한 렌더가 한 번 더 돈다.
  const [consecutiveFailures, setConsecutiveFailures] = useState(0);

  // 콜백·폴러 identity 가 바뀌어도 주기를 다시 잡지 않도록 ref 로 미러링한다.
  const pollRef = useRef(poll);
  const onResultRef = useRef(onResult);
  useEffect(() => {
    pollRef.current = poll;
    onResultRef.current = onResult;
  });

  useEffect(() => {
    // 토큰이 없으면 인증할 수 없다. 세션 복원 중이거나 로그아웃 상태다.
    if (!enabled || accessToken === null) return;

    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const controller = new AbortController();

    const scheduleNext = () => {
      if (stopped) return;
      timer = setTimeout(run, COACH_POLL_INTERVAL_MS);
    };

    const run = async () => {
      try {
        const result = await pollRef.current(sessionId, accessToken, controller.signal);
        if (stopped) return;
        setConsecutiveFailures(0);
        onResultRef.current?.(result);
      } catch (error) {
        if (stopped) return;
        // 종료된 수업이거나 받을 수 없는 역할이면 다시 물어도 답이 같다. 배지도 띄우지 않는다.
        if (error instanceof CoachPollError && error.shouldStopPolling) {
          stopped = true;
          setConsecutiveFailures(0);
          return;
        }
        // 한 번의 실패는 아직 알리지 않는다. 연속으로 이어질 때만 배지가 뜬다.
        setConsecutiveFailures((count) => count + 1);
      }
      scheduleNext();
    };

    void run();

    return () => {
      stopped = true;
      controller.abort();
      if (timer !== null) clearTimeout(timer);
    };
  }, [sessionId, enabled, accessToken]);

  return { availability: enabled ? coachingAvailabilityOf(consecutiveFailures) : "ACTIVE" };
}
