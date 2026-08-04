"use client";

import { useEffect, useState } from "react";

import { useAuth } from "@/domains/auth";
import {
  requestSessionList,
  type SessionSummary,
  type SessionListRequester,
} from "../infrastructure/sessionListApi";

/** 이 수업에서 내 역할. 서버 응답의 `role` 을 그대로 쓴다. */
export type SessionRole = "INSTRUCTOR" | "STUDENT";

export type SessionRoleStatus = "loading" | "ready" | "unknown";

export type UseSessionRoleResult = {
  readonly status: SessionRoleStatus;
  readonly role: SessionRole | null;
  readonly session: SessionSummary | null;
};

export type UseSessionRoleOptions = {
  request?: SessionListRequester;
};

/**
 * 세션 하나에 대한 내 역할을 서버에서 확인한다.
 *
 * <p>fixture 로 역할을 고르면 실제 세션 id 가 목록에 없어 늘 첫 강의(강사)로 떨어진다. 그러면
 * 학생이 자기 리포트를 열어도 강사용 엔드포인트를 불러 403 을 받는다(설계 문서 §2.7).
 *
 * <p>목록에 그 세션이 없거나 조회가 실패하면 `unknown` 이다. **임의로 강사라고 가정하지 않는다.**
 * 틀린 역할로 부르는 것보다 못 부르는 편이 낫다.
 *
 * <p>`lecture` 도메인의 HTTP 어댑터를 밖으로 노출하지 않기 위해 훅으로 감싼다. 다른 도메인은
 * 이 훅만 공개 API 로 가져간다.
 */
export function useSessionRole(
  sessionId: string,
  options: UseSessionRoleOptions = {},
): UseSessionRoleResult {
  const { request = requestSessionList } = options;
  const { accessToken } = useAuth();

  // 답에 어느 세션의 답인지 함께 담는다. 세션이 바뀐 순간 이전 역할을 물려주면 그 짧은 사이에
  // 잘못된 엔드포인트를 부른다.
  const [answer, setAnswer] = useState<UseSessionRoleResult & { sessionId: string }>({
    sessionId: "",
    status: "loading",
    role: null,
    session: null,
  });

  useEffect(() => {
    // 로그인 전에는 확인할 방법이 없다. 로딩에 머문다.
    if (accessToken === null) {
      return;
    }

    const controller = new AbortController();
    let active = true;

    request(accessToken, controller.signal)
      .then((sessions) => {
        if (!active) return;
        const found = sessions.find((session) => session.sessionId === sessionId);
        if (found === undefined || (found.role !== "INSTRUCTOR" && found.role !== "STUDENT")) {
          setAnswer({ sessionId, status: "unknown", role: null, session: null });
          return;
        }
        setAnswer({ sessionId, status: "ready", role: found.role, session: found });
      })
      .catch((caught: unknown) => {
        // 취소는 실패가 아니다. 화면을 떠났거나 토큰이 갱신되어 다시 조회하는 경우다.
        if (!active || controller.signal.aborted) return;
        setAnswer({ sessionId, status: "unknown", role: null, session: null });
        console.warn("세션 역할 조회 실패", caught);
      });

    return () => {
      active = false;
      controller.abort();
    };
  }, [sessionId, accessToken, request]);

  return answer.sessionId === sessionId
    ? { status: answer.status, role: answer.role, session: answer.session }
    : { status: "loading", role: null, session: null };
}
