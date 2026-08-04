"use client";

import { useCallback, useEffect, useState } from "react";

import { useAuth } from "@/domains/auth";
import {
  QuizSummaryError,
  requestQuizSummary,
  type QuizSummary,
  type QuizSummaryRequester,
} from "../infrastructure/quizSummaryApi";

/**
 * 퀴즈 카드가 분기하는 상태. `notReady`(404)는 아직 만들어지지 않은 것이고 `failed` 는 다시
 * 시도할 만한 것이다.
 */
export type QuizSummaryStatus = "loading" | "ready" | "notReady" | "failed";

export type UseQuizSummaryResult = {
  readonly status: QuizSummaryStatus;
  readonly summary: QuizSummary | null;
  readonly retry: () => void;
};

export type UseQuizSummaryOptions = {
  readonly sessionId: string;
  readonly request?: QuizSummaryRequester;
};

/**
 * 퀴즈 요약을 한 번 조회한다.
 *
 * <p>학습 리포트와 따로 조회하는 이유: 퀴즈는 다른 엔드포인트의 다른 산출물이라 한쪽이 없어도
 * 다른 쪽은 보여줄 수 있다. 하나로 묶으면 퀴즈가 아직 없는 수업에서 참여 요약까지 사라진다.
 */
export function useQuizSummary(options: UseQuizSummaryOptions): UseQuizSummaryResult {
  const { sessionId, request = requestQuizSummary } = options;
  const { accessToken } = useAuth();

  const [attempt, setAttempt] = useState(0);
  const key = `${sessionId}|${attempt}`;
  const [answer, setAnswer] = useState<{
    key: string;
    status: QuizSummaryStatus;
    summary: QuizSummary | null;
  }>({ key: "", status: "loading", summary: null });

  const retry = useCallback(() => setAttempt((count) => count + 1), []);

  useEffect(() => {
    if (accessToken === null) return;

    const controller = new AbortController();
    let active = true;

    request(sessionId, accessToken, controller.signal)
      .then((summary) => {
        if (!active) return;
        setAnswer({ key, status: "ready", summary });
      })
      .catch((error: unknown) => {
        // 취소는 실패가 아니다. 화면을 떠났거나 다시 조회하는 중이다.
        if (!active || controller.signal.aborted) return;
        const status =
          error instanceof QuizSummaryError && error.status === 404 ? "notReady" : "failed";
        setAnswer({ key, status, summary: null });
      });

    return () => {
      active = false;
      controller.abort();
    };
  }, [sessionId, accessToken, request, key]);

  return answer.key === key
    ? { status: answer.status, summary: answer.summary, retry }
    : { status: "loading", summary: null, retry };
}
