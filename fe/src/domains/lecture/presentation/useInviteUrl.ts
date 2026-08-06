"use client";

import { useEffect, useState } from "react";

import { useAuth } from "@/domains/auth";
import {
  requestSessionIdentities,
  type SessionIdentityRequester,
} from "../infrastructure/sessionListApi";

/**
 * 이 수업의 학생 입장 링크를 만든다.
 *
 * <p>초대 코드는 미디어 토큰 응답에 없다 — 강의실은 방에 붙는 데 필요한 것만 받는다. 홈의 "진행 중인
 * 수업" 배너가 쓰는 것과 같은 목록 조회에서 이 세션의 코드를 찾는다. 서버 계약을 새로 만들지 않으려는
 * 선택이라, 초대 코드가 토큰 응답에 실리면 이 훅은 사라져도 된다.
 *
 * <p>못 찾으면 `null` 이다. 링크를 지어내지 않는다 — 틀린 링크를 학생에게 뿌리면 아무도 못 들어온다.
 */
export function useInviteUrl(
  sessionId: string,
  request: SessionIdentityRequester = requestSessionIdentities,
): string | null {
  const { accessToken } = useAuth();
  const [inviteCode, setInviteCode] = useState<string | null>(null);

  useEffect(() => {
    if (accessToken === null) return;
    const controller = new AbortController();
    request(accessToken, controller.signal)
      .then((sessions) => {
        const found = sessions.find((session) => session.sessionId === sessionId);
        if (found !== undefined) setInviteCode(found.inviteCode);
      })
      .catch(() => {
        // 링크를 못 구한 것뿐이다. 수업 진행을 막지 않는다 — 안내는 자리만 잡고 기다린다.
      });
    return () => controller.abort();
  }, [sessionId, accessToken, request]);

  if (inviteCode === null || typeof window === "undefined") return null;
  return `${window.location.origin}/prejoin/${inviteCode}`;
}
