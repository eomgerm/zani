"use client";

import { useCallback, useEffect, useState } from "react";

import { useAuth } from "@/domains/auth";
import {
  requestSessionSummary,
  SessionSummaryError,
  type SessionSummary,
  type SessionSummaryRequester,
} from "../infrastructure/sessionSummaryApi";

/**
 * 카드가 분기하는 상태.
 *
 * <p>`forbidden`(403)과 `notReady` 를 나누는 이유는 할 말이 다르기 때문이다 — 앞은 권한 문제,
 * 뒤는 시간 문제다. `notReady` 는 404(요약 미생성)를 기본으로 하되 409(진행 중)도 흡수한다.
 */
export type SessionSummaryStatus = "loading" | "ready" | "forbidden" | "notReady" | "failed";

export type UseSessionSummaryResult = {
  readonly status: SessionSummaryStatus;
  readonly summary: string | null;
  readonly retry: () => void;
};

export type UseSessionSummaryOptions = {
  readonly sessionId: string;
  readonly request?: SessionSummaryRequester;
};

const statusOf = (error: unknown): SessionSummaryStatus => {
  if (error instanceof SessionSummaryError) {
    if (error.status === 403) return "forbidden";
    if (error.status === 404 || error.status === 409) return "notReady";
  }
  return "failed";
};

/**
 * 수업 요약을 한 번 조회한다.
 *
 * <p>폴링하지 않는다. 종료된 세션의 사후 산출물이라 다시 물어도 값이 바뀌지 않는다.
 *
 * <p>언마운트하면 진행 중인 요청을 취소하고 상태를 바꾸지 않는다(useStudentReport 와 동일).
 */
export function useSessionSummary(options: UseSessionSummaryOptions): UseSessionSummaryResult {
  const { sessionId, request = requestSessionSummary } = options;
  const { accessToken } = useAuth();

  // 값 자체는 쓰지 않는다. 효과를 다시 돌리기 위한 트리거다.
  const [attempt, setAttempt] = useState(0);
  const key = `${sessionId}|${attempt}`;

  // 결과에 그 결과를 만든 시도를 함께 담는다. 재시도 순간 이전 결과가 보이지 않게 한다.
  const [answer, setAnswer] = useState<{
    key: string;
    status: SessionSummaryStatus;
    summary: SessionSummary | null;
  }>({ key: "", status: "loading", summary: null });

  const retry = useCallback(() => setAttempt((count) => count + 1), []);

  useEffect(() => {
    // 토큰이 없으면 인증할 수 없다. 세션 복원 중이거나 로그아웃 상태다.
    if (accessToken === null) {
      return;
    }

    const controller = new AbortController();
    let active = true;

    request(sessionId, accessToken, controller.signal)
      .then((result) => {
        if (!active) return;
        setAnswer({ key, status: "ready", summary: result });
      })
      .catch((error: unknown) => {
        // 취소는 실패가 아니다. 화면을 떠났거나 다시 조회하는 중이다.
        if (!active || controller.signal.aborted) return;
        setAnswer({ key, status: statusOf(error), summary: null });
      });

    return () => {
      active = false;
      controller.abort();
    };
  }, [sessionId, accessToken, request, key]);

  // 아직 이번 시도의 답이 오지 않았으면 로딩이다. 이전 시도의 결과를 물려주지 않는다.
  return answer.key === key
    ? { status: answer.status, summary: answer.summary?.summary ?? null, retry }
    : { status: "loading", summary: null, retry };
}
