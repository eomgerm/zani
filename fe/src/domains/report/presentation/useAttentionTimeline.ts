"use client";

import { useCallback, useEffect, useState } from "react";

import { useAuth } from "@/domains/auth";
import { AttentionTimelineError } from "../infrastructure/attentionTimelineApi";

/**
 * 카드가 분기하는 상태.
 *
 * <p>`forbidden`(403)·`live`(409)·`failed` 를 나누는 이유는 사용자에게 할 말이 전부 다르기
 * 때문이다. 셋을 "불러오지 못했어요" 하나로 뭉치면 아직 진행 중인 수업을 오류로 읽는다(§5.6).
 */
export type TimelineStatus = "loading" | "ready" | "forbidden" | "live" | "failed";

export type UseAttentionTimelineResult<T> = {
  readonly status: TimelineStatus;
  readonly timeline: T | null;
  readonly retry: () => void;
};

export type UseAttentionTimelineOptions<T> = {
  readonly sessionId: string;
  /**
   * 역할을 확정하기 전에는 `false` 다. 어느 엔드포인트를 부를지 모르는 채로 호출하면
   * 학생이 집단 경로를 불러 403 을 받는다.
   */
  readonly enabled: boolean;
  readonly request: (sessionId: string, accessToken: string, signal?: AbortSignal) => Promise<T>;
};

const statusOf = (error: unknown): TimelineStatus => {
  if (error instanceof AttentionTimelineError) {
    if (error.status === 403) return "forbidden";
    if (error.status === 409) return "live";
  }
  return "failed";
};

/**
 * 리포트 타임라인을 한 번 조회한다.
 *
 * <p>폴링하지 않는다. 종료된 세션의 사후 계산이라 다시 물어도 값이 바뀌지 않는다.
 *
 * <p>언마운트하면 진행 중인 요청을 취소하고 상태를 바꾸지 않는다. 사라진 카드에 실패를 남기면
 * React 가 경고를 내고, 재시도 버튼도 없는 화면에 오류만 쌓인다.
 */
export function useAttentionTimeline<T>(
  options: UseAttentionTimelineOptions<T>,
): UseAttentionTimelineResult<T> {
  const { sessionId, enabled, request } = options;
  const { accessToken } = useAuth();

  const [status, setStatus] = useState<TimelineStatus>("loading");
  const [timeline, setTimeline] = useState<T | null>(null);
  // 값 자체는 쓰지 않는다. 효과를 다시 돌리기 위한 트리거다.
  const [attempt, setAttempt] = useState(0);

  const retry = useCallback(() => setAttempt((count) => count + 1), []);

  useEffect(() => {
    // 토큰이 없으면 인증할 수 없다. 세션 복원 중이거나 로그아웃 상태다.
    if (!enabled || accessToken === null) {
      return;
    }

    const controller = new AbortController();
    let active = true;

    setStatus("loading");

    request(sessionId, accessToken, controller.signal)
      .then((result) => {
        if (!active) return;
        setTimeline(result);
        setStatus("ready");
      })
      .catch((error: unknown) => {
        // 취소는 실패가 아니다. 화면을 떠났거나 다시 조회하는 중이다.
        if (!active || controller.signal.aborted) return;
        setTimeline(null);
        setStatus(statusOf(error));
      });

    return () => {
      active = false;
      controller.abort();
    };
  }, [sessionId, enabled, accessToken, request, attempt]);

  return { status, timeline, retry };
}
