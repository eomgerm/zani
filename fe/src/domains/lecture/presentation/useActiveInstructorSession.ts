"use client";

import { useCallback, useEffect, useState } from "react";

import { useAuth } from "@/domains/auth";
import {
  requestSessionIdentities,
  type SessionIdentity,
  type SessionIdentityRequester,
} from "@/domains/lecture/infrastructure/sessionListApi";

/**
 * 강사가 아직 끝내지 않은 수업의 상태. 준비 중이거나 진행 중이면 "돌아갈 수 있는" 수업이다.
 *
 * <p>서버는 한 강사에게 활성 수업을 하나만 허용하므로, 이 상태가 있으면 새 수업을 만들 수 없다. 그래서 이 값이 곧 "먼저 종료해야 하는 수업"이다.
 */
const OPEN_STATUSES = ["PREPARING", "LIVE"];

export type ActiveInstructorSessionState = {
  /** 아직 끝내지 않은 내 수업. 없거나 아직 확인되지 않았으면 null. */
  session: SessionIdentity | null;
  /** 다시 조회한다. 수업을 종료한 뒤 배너를 사라지게 하는 데 쓴다. */
  refresh: () => void;
};

/**
 * 내가 강사로 열어 둔 활성 수업을 찾는다.
 *
 * <p>조회 실패와 "활성 수업 없음"을 밖으로 구분해 주지 않는다. 이 값을 쓰는 홈 배너의 용도는 "돌아가기·종료"뿐이라, 상태를 알 수 없을 때 보여줄 것도 없다. 실패 원인은 콘솔에만 남긴다.
 *
 * <p><b>식별에 필요한 부분만 받는다.</b> 배너가 하는 일이 "돌아가기·종료" 뿐이라 제목이나 리포트 상태가 필요 없고, 목록 화면이 요구하는 검증까지 떠안으면 서버가 그쪽 필드를 하나 바꿨을 때 강의실로
 * 돌아갈 길까지 막힌다. 제목을 보여주려면 {@code requestSessionList} 로 바꿔 끼우면 된다.
 */
export function useActiveInstructorSession(
  requestList: SessionIdentityRequester = requestSessionIdentities,
): ActiveInstructorSessionState {
  const { accessToken } = useAuth();
  const [session, setSession] = useState<SessionIdentity | null>(null);
  // 종료 후 다시 조회하기 위한 트리거. 값 자체는 쓰지 않는다.
  const [reloadKey, setReloadKey] = useState(0);

  const refresh = useCallback(() => setReloadKey((key) => key + 1), []);

  useEffect(() => {
    // 로그인 전에는 조회할 대상이 없다.
    if (accessToken === null) {
      return;
    }

    const controller = new AbortController();
    let active = true;

    requestList(accessToken, controller.signal)
      .then((sessions) => {
        if (!active) return;
        setSession(
          sessions.find(
            (candidate) =>
              candidate.role === "INSTRUCTOR" && OPEN_STATUSES.includes(candidate.status),
          ) ?? null,
        );
      })
      .catch((caught: unknown) => {
        // 취소는 실패가 아니다. 화면을 떠났거나 토큰이 갱신되어 다시 조회하는 경우다.
        if (!active || controller.signal.aborted) return;
        setSession(null);
        // 배너는 조용히 숨기지만 원인은 남긴다. 이게 없으면 서버 오류가 "진행 중인 수업 없음"과
        // 구분되지 않아 디버깅할 단서가 사라진다.
        console.warn("활성 수업 조회 실패", caught);
      });

    return () => {
      active = false;
      controller.abort();
    };
  }, [accessToken, requestList, reloadKey]);

  // 로그아웃 상태에서는 이전 조회 결과를 들고 있지 않는다.
  return { session: accessToken === null ? null : session, refresh };
}
