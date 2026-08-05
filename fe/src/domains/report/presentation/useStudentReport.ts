"use client";

import { useCallback, useEffect, useState } from "react";

import { useAuth } from "@/domains/auth";
import {
  requestStudentReport,
  StudentReportError,
  type StudentReport,
  type StudentReportRequester,
} from "../infrastructure/studentReportApi";

/**
 * 카드가 분기하는 상태.
 *
 * <p>넷을 나누는 이유는 할 말이 다르기 때문이다 — `forbidden`(403)은 권한, `notReady`(404)는
 * 아직 안 만들어진 것, `live`(409)는 수업이 안 끝난 것, `failed` 는 다시 시도할 만한 것이다.
 * 하나로 묶으면 수업이 진행 중인 학생에게 "볼 권한이 없어요" 라고 말하게 된다.
 *
 * <p>`live` 를 `notReady` 에 흡수하지 않는 까닭: 112 컨트롤러가 진행 중 세션에 409 를 명시적으로
 * 내보내고(`ReportNotReadyException`), 화면에는 "수업이 끝나면 볼 수 있어요" 라는 다음 행동이
 * 있는 안내를 띄울 수 있다.
 */
export type StudentReportStatus =
  | "loading"
  | "ready"
  | "notReady"
  | "forbidden"
  | "live"
  | "failed";

export type UseStudentReportResult = {
  readonly status: StudentReportStatus;
  readonly report: StudentReport | null;
  readonly retry: () => void;
};

export type UseStudentReportOptions = {
  readonly sessionId: string;
  readonly request?: StudentReportRequester;
};

const statusOf = (error: unknown): StudentReportStatus => {
  if (error instanceof StudentReportError) {
    if (error.status === 403) return "forbidden";
    if (error.status === 404) return "notReady";
    if (error.status === 409) return "live";
  }
  return "failed";
};

/**
 * 학생 학습 리포트를 한 번 조회한다.
 *
 * <p>폴링하지 않는다. 종료된 수업의 사후 산출물이라 다시 물어도 값이 바뀌지 않는다. 값을 바꾸는
 * 유일한 길은 사람이 누르는 `retry` 다.
 *
 * <p>언마운트하면 진행 중인 요청을 취소하고 상태를 바꾸지 않는다(useAttentionTimeline 과 동일).
 */
export function useStudentReport(options: UseStudentReportOptions): UseStudentReportResult {
  const { sessionId, request = requestStudentReport } = options;
  const { accessToken } = useAuth();

  // 값 자체는 쓰지 않는다. 효과를 다시 돌리기 위한 트리거다.
  const [attempt, setAttempt] = useState(0);
  const key = `${sessionId}|${attempt}`;

  // 결과에 그 결과를 만든 시도를 함께 담는다. 재시도 순간 이전 결과가 보이지 않게 한다.
  const [answer, setAnswer] = useState<{
    key: string;
    status: StudentReportStatus;
    report: StudentReport | null;
  }>({ key: "", status: "loading", report: null });

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
        setAnswer({ key, status: "ready", report: result });
      })
      .catch((error: unknown) => {
        // 취소는 실패가 아니다. 화면을 떠났거나 다시 조회하는 중이다.
        if (!active || controller.signal.aborted) return;
        setAnswer({ key, status: statusOf(error), report: null });
      });

    return () => {
      active = false;
      controller.abort();
    };
  }, [sessionId, accessToken, request, key]);

  // 아직 이번 시도의 답이 오지 않았으면 로딩이다. 이전 시도의 결과를 물려주지 않는다.
  return answer.key === key
    ? { status: answer.status, report: answer.report, retry }
    : { status: "loading", report: null, retry };
}
