"use client";

import { useCallback, useEffect, useState } from "react";

import { useAuth } from "@/domains/auth";
import {
  requestStudentQuiz,
  StudentQuizError,
  submitQuizAnswers as defaultSubmit,
  type QuizAnswer,
  type QuizAnswersSubmitter,
  type QuizGradingSummary,
  type StudentQuiz,
  type StudentQuizRequester,
} from "../infrastructure/studentQuizApi";

/**
 * 퀴즈 조회 상태. `notReady`(404)는 아직 만들어지지 않은 것이고 — 진행 중 수업도 서버가 여기로
 * 접는다 — `forbidden`(403)은 이 세션의 학생이 아닌 것이다.
 */
export type StudentQuizStatus = "loading" | "ready" | "notReady" | "forbidden" | "failed";

/**
 * 제출 상태. `alreadySubmitted`(409)를 실패와 가르는 이유: 답안은 이미 서버에 있으므로 학생이 할
 * 일은 다시 제출하는 것이 아니라 결과를 보는 것이다. 화면은 조회를 다시 걸어 저장된 채점을 띄운다.
 */
export type QuizSubmitStatus = "idle" | "submitting" | "submitted" | "alreadySubmitted" | "failed";

export type UseStudentQuizResult = {
  readonly status: StudentQuizStatus;
  readonly quiz: StudentQuiz | null;
  readonly retry: () => void;
  readonly submitStatus: QuizSubmitStatus;
  readonly grading: QuizGradingSummary | null;
  /** 모든 문항의 답을 한 번에 보낸다. 서버 계약이 일괄 제출이라 부분 제출은 없다. */
  readonly submit: (answers: readonly QuizAnswer[]) => void;
};

export type UseStudentQuizOptions = {
  readonly sessionId: string;
  readonly request?: StudentQuizRequester;
  readonly submitRequest?: QuizAnswersSubmitter;
};

const statusOf = (error: unknown): StudentQuizStatus => {
  if (error instanceof StudentQuizError) {
    if (error.status === 403) return "forbidden";
    if (error.status === 404) return "notReady";
  }
  return "failed";
};

/**
 * 퀴즈를 한 번 조회하고, 답안을 한 번 제출한다.
 *
 * <p>리포트의 퀴즈 카드와 퀴즈 화면이 같은 훅을 쓴다. 카드는 문항 수·예상 시간만 읽고 제출은
 * 건드리지 않는다 — 같은 엔드포인트를 두 어댑터로 나눠 부르지 않기 위해서다.
 */
export function useStudentQuiz(options: UseStudentQuizOptions): UseStudentQuizResult {
  const { sessionId, request = requestStudentQuiz, submitRequest = defaultSubmit } = options;
  const { accessToken } = useAuth();

  const [attempt, setAttempt] = useState(0);
  const key = `${sessionId}|${attempt}`;
  const [answer, setAnswer] = useState<{
    key: string;
    status: StudentQuizStatus;
    quiz: StudentQuiz | null;
  }>({ key: "", status: "loading", quiz: null });

  const [submitStatus, setSubmitStatus] = useState<QuizSubmitStatus>("idle");
  const [grading, setGrading] = useState<QuizGradingSummary | null>(null);

  const retry = useCallback(() => setAttempt((count) => count + 1), []);

  useEffect(() => {
    if (accessToken === null) return;

    const controller = new AbortController();
    let active = true;

    request(sessionId, accessToken, controller.signal)
      .then((quiz) => {
        if (!active) return;
        setAnswer({ key, status: "ready", quiz });
      })
      .catch((error: unknown) => {
        // 취소는 실패가 아니다. 화면을 떠났거나 다시 조회하는 중이다.
        if (!active || controller.signal.aborted) return;
        setAnswer({ key, status: statusOf(error), quiz: null });
      });

    return () => {
      active = false;
      controller.abort();
    };
  }, [sessionId, accessToken, request, key]);

  const submit = useCallback(
    (answers: readonly QuizAnswer[]) => {
      if (accessToken === null || answers.length === 0) return;
      setSubmitStatus("submitting");

      submitRequest(sessionId, accessToken, answers)
        .then((result) => {
          setGrading(result);
          setSubmitStatus("submitted");
        })
        .catch((error: unknown) => {
          // 이미 제출된 퀴즈는 실패가 아니다. 조회를 다시 걸면 저장된 채점이 문항에 실려 온다.
          if (error instanceof StudentQuizError && error.status === 409) {
            setSubmitStatus("alreadySubmitted");
            retry();
            return;
          }
          setSubmitStatus("failed");
        });
    },
    [sessionId, accessToken, submitRequest, retry],
  );

  const settled = answer.key === key;
  return {
    status: settled ? answer.status : "loading",
    quiz: settled ? answer.quiz : null,
    retry,
    submitStatus,
    grading,
    submit,
  };
}
