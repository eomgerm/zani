"use client";

import { useCallback, useEffect, useRef } from "react";

import { useAuth } from "@/domains/auth";
import type { DetectorReport } from "../domain/detectionOutcome";
import {
  AttentionEventSendError,
  sendAttentionEvent,
  type AttentionEventSender,
} from "../infrastructure/attentionEventApi";

/**
 * 재시도까지 기다리는 시간(ms).
 *
 * 다음 판정 창(10초)보다 훨씬 짧게 둔다. 재시도가 다음 창의 전송과 겹치면 순간적으로 요청이
 * 두 배가 되고, 늦게 도착한 옛 관측이 최신 상태 뒤에 실려 서버가 그것을 버린다.
 */
export const ATTENTION_EVENT_RETRY_DELAY_MS = 1_000;

export interface UseAttentionEventReporterOptions {
  readonly sessionId: string;
  send?: AttentionEventSender;
}

/** 검출기 보고 한 건을 서버로 올린다. 실패해도 예외를 던지지 않는다. */
export type AttentionEventReporter = (report: DetectorReport) => void;

/**
 * 10초 검출기 보고를 서버로 올리는 훅.
 *
 * 전송 자체는 무상태 어댑터가 하고, 이 훅은 보고 주기를 아는 쪽만 판단할 수 있는 것을 쥔다.
 *
 * - **재시도 1회**: 응답이 없거나 5xx 면 같은 리포트를 그대로 한 번 더 보낸다. 리포트가 들고 있는
 *   `clientEventId` 가 서버의 멱등키라 중복 반영되지 않고 `duplicate` 로 성공한다. 두 번 실패하면
 *   버린다 — 10초 뒤 새 관측이 곧 온다.
 * - **409 이후 중단**: 종료된 세션은 다음 창에도 종료돼 있다. 래치를 세워 그 수업에서는 더 보내지
 *   않는다. 다른 수업에 들어가면 래치가 풀린다.
 * - **그 밖의 4xx**: 다시 보내도 같은 답이라 그 관측만 버리고 다음 창은 그대로 시도한다.
 *
 * 전송 실패가 판정 루프나 수업 화면을 막아서는 안 되므로 어떤 경로에서도 예외를 올리지 않는다.
 */
export function useAttentionEventReporter(
  options: UseAttentionEventReporterOptions,
): AttentionEventReporter {
  const { sessionId, send = sendAttentionEvent } = options;
  // 서버는 Bearer 토큰으로 요청자가 이 세션의 학생인지 본다. 쿠키는 refresh 전용이라 헤더가 없으면 401 이다.
  const { accessToken } = useAuth();

  // 콜백 identity 가 바뀌어도 판정 세션이 다시 시작되지 않도록, 훅이 돌려주는 함수는 고정한다.
  const latestRef = useRef({ sessionId, send, accessToken });
  useEffect(() => {
    latestRef.current = { sessionId, send, accessToken };
  });

  // 종료된 세션으로 확인된 수업. 값을 함께 들고 있어야 다른 수업에 들어갔을 때 되살아난다.
  const endedSessionIdRef = useRef<string | null>(null);

  // 강의실을 떠나면 진행 중인 전송을 끊고 남은 재시도를 버린다. 느린 요청 위로 다음 창이
  // 겹칠 수 있으므로 최신 것만 들고 있지 않고 아직 끝나지 않은 것을 모두 모아 둔다.
  const inFlightRef = useRef(new Set<AbortController>());
  const retryTimersRef = useRef(new Set<ReturnType<typeof setTimeout>>());
  const leftRoomRef = useRef(false);
  useEffect(() => {
    const inFlight = inFlightRef.current;
    const retryTimers = retryTimersRef.current;
    leftRoomRef.current = false;
    return () => {
      leftRoomRef.current = true;
      inFlight.forEach((controller) => controller.abort());
      inFlight.clear();
      retryTimers.forEach(clearTimeout);
      retryTimers.clear();
    };
  }, []);

  return useCallback((report: DetectorReport): void => {
    const { sessionId: currentSessionId, send: sendEvent, accessToken: token } = latestRef.current;
    // 토큰이 아직 없으면 보내도 401 이다. 세션 복원이 끝나면 다음 창이 곧 온다.
    // 이 검사만 여기 있다 — `attempt` 는 토큰을 클로저로 잡아 다시 읽지 않는다.
    if (token === null) return;

    /**
     * 보내도 되는지는 **매 시도 직전에** 판단한다. 진입부에서 한 번만 보면, 예약된 재시도가
     * 예약 시점의 판단을 들고 그대로 깨어난다 — 그 1초 사이에 다른 관측이 409 를 받아 세션
     * 종료가 드러나도 종료된 세션으로 한 번 더 보낸다. 창 보고 직후 학생이 카메라를 끄면 두
     * 관측이 1초 안에 겹치므로 실제로 닿는 순서다.
     *
     * 그래서 판단을 이 한 곳에만 둔다. 진입부에 같은 검사를 복제하면 두 벌이 갈라진다.
     */
    const attempt = (retriesLeft: number): void => {
      if (leftRoomRef.current || endedSessionIdRef.current === currentSessionId) return;
      const controller = new AbortController();
      inFlightRef.current.add(controller);

      sendEvent(currentSessionId, report, token, controller.signal)
        .catch((error: unknown) => {
          if (leftRoomRef.current) return;
          const status = error instanceof AttentionEventSendError ? error.status : 0;

          if (status === 409) {
            endedSessionIdRef.current = currentSessionId;
            return;
          }
          // 응답이 없거나(0) 서버가 넘어진 경우(5xx)만 다시 보낼 값이 있다.
          const worthRetrying = status === 0 || status >= 500;
          if (!worthRetrying || retriesLeft === 0) {
            console.warn("[attention] 참여도 관측 전송 실패", status, error);
            return;
          }
          const timer = setTimeout(() => {
            retryTimersRef.current.delete(timer);
            attempt(retriesLeft - 1);
          }, ATTENTION_EVENT_RETRY_DELAY_MS);
          retryTimersRef.current.add(timer);
        })
        .finally(() => {
          inFlightRef.current.delete(controller);
        });
    };

    attempt(1);
  }, []);
}
