"use client";

import { useEffect, useState } from "react";

import { useAuth } from "@/domains/auth";
import {
  requestSessionList,
  SessionListRequestError,
  type SessionListRequester,
} from "@/domains/lecture/infrastructure/sessionListApi";
import { toMyLecture, type MyLecture } from "./myLectures";

export type MyLecturesState = {
  lectures: readonly MyLecture[];
  loading: boolean;
  /** 조회에 실패한 이유. 성공하면 지워진다. */
  error: string | null;
};

/** 어느 토큰으로 받아 온 결과인지 함께 들고 있어야 로그인이 바뀐 뒤 옛 목록을 그대로 보여주지 않는다. */
type Loaded = {
  token: string;
  lectures: readonly MyLecture[];
  error: string | null;
};

function messageFor(status: number): string {
  if (status === 401) return "다시 로그인해 주세요.";
  return "강의 목록을 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.";
}

/**
 * 내 강의실 목록.
 *
 * <p><b>빈 목록과 실패를 구분해서 내보낸다.</b> 둘을 같은 모양으로 주면 화면이 "아직 수업이 없어요"를 띄우는데, 서버가 죽었을 때도 같은 문구가 뜬다. 사용자는 자기 수업이 사라진 줄 안다.
 *
 * <p><b>로딩은 상태가 아니라 파생값이다.</b> effect 본문에서 곧바로 setState 를 부르면 렌더가 연쇄로 늘어난다(`react-hooks/set-state-in-effect`). 결과에 토큰을
 * 함께 담아 두면 "이 토큰의 결과가 아직 없다"가 곧 로딩이라 따로 둘 필요가 없다.
 */
export function useMyLectures(
  requestList: SessionListRequester = requestSessionList,
): MyLecturesState {
  const { accessToken } = useAuth();
  const [loaded, setLoaded] = useState<Loaded | null>(null);

  useEffect(() => {
    // 로그인 전에는 조회할 대상이 없다. 화면은 로그인 리다이렉트가 처리한다.
    if (accessToken === null) return;

    const abort = new AbortController();
    let alive = true;

    requestList(accessToken, abort.signal)
      .then((summaries) => {
        if (!alive) return;
        setLoaded({ token: accessToken, lectures: summaries.map(toMyLecture), error: null });
      })
      .catch((failed: unknown) => {
        // 화면이 사라지면서 취소된 요청은 실패가 아니다.
        if (!alive || abort.signal.aborted) return;
        const status = failed instanceof SessionListRequestError ? failed.status : 0;
        setLoaded({ token: accessToken, lectures: [], error: messageFor(status) });
      });

    return () => {
      alive = false;
      abort.abort();
    };
  }, [accessToken, requestList]);

  const settled = loaded !== null && loaded.token === accessToken;
  return {
    lectures: settled ? loaded.lectures : [],
    loading: accessToken !== null && !settled,
    error: settled ? loaded.error : null,
  };
}
