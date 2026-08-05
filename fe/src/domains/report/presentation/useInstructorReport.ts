"use client";

import { useCallback, useEffect, useState } from "react";

import { useAuth } from "@/domains/auth";
import {
  requestInstructorReport,
  InstructorReportError,
  type InstructorReport,
  type InstructorReportRequester,
} from "../infrastructure/instructorReportApi";

/**
 * 카드가 분기하는 상태.
 *
 * <p>`forbidden`(403)과 `notReady`(404)를 나누는 이유는 할 말이 다르기 때문이다 — 앞은 "내
 * 수업이 아니다", 뒤는 "아직 만들어지지 않았다"다. 둘을 "불러오지 못했어요" 하나로 뭉치면 분석을
 * 기다리는 강사가 자기 수업을 남의 것으로 읽는다.
 *
 * <p>`live`(409)는 두지 않는다. 강사 리포트는 종료된 세션만 대상이고 진행 중이면 권한 판정 단계
 * (`ResolveEndedSessionParticipant`)에서 걸러져 리포트 미생성과 같은 자리로 온다.
 */
export type InstructorReportStatus = "loading" | "ready" | "forbidden" | "notReady" | "failed";

export type UseInstructorReportResult = {
  readonly status: InstructorReportStatus;
  readonly report: InstructorReport | null;
  readonly retry: () => void;
};

export type UseInstructorReportOptions = {
  readonly sessionId: string;
  /** 테스트에서 갈아끼우기 위한 선택 인자. 기본값이 실제 어댑터다. */
  readonly request?: InstructorReportRequester;
};

const statusOf = (error: unknown): InstructorReportStatus => {
  if (error instanceof InstructorReportError) {
    if (error.status === 403) return "forbidden";
    // 409 도 흡수한다. 서버는 진행 중을 404 로 번역해 주는 계약이지만, 참여도 타임라인처럼 409 를
    // 그대로 내보내는 선례가 있어 어느 쪽이 와도 "아직 준비 전"으로 읽는다.
    if (error.status === 404 || error.status === 409) return "notReady";
  }
  return "failed";
};

/**
 * 강사 리포트를 한 번 조회한다.
 *
 * <p>폴링하지 않는다. 종료된 세션의 사후 산출물이라 다시 물어도 값이 바뀌지 않는다. 분석이 끝나기를
 * 기다리는 화면은 내 강의실 목록이 맡는다 — 리포트 화면에 들어왔다는 것은 이미 준비됐다는 뜻이다.
 *
 * <p>언마운트하면 진행 중인 요청을 취소하고 상태를 바꾸지 않는다(useAttentionTimeline 과 동일).
 */
export function useInstructorReport(
  options: UseInstructorReportOptions,
): UseInstructorReportResult {
  const { sessionId, request = requestInstructorReport } = options;
  const { accessToken } = useAuth();

  // 값 자체는 쓰지 않는다. 효과를 다시 돌리기 위한 트리거다.
  const [attempt, setAttempt] = useState(0);
  const key = `${sessionId}|${attempt}`;

  // 결과에 그 결과를 만든 시도를 함께 담는다. 재시도 순간 이전 결과가 보이지 않게 한다.
  const [answer, setAnswer] = useState<{
    key: string;
    status: InstructorReportStatus;
    report: InstructorReport | null;
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
