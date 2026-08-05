"use client";

import { useCallback, useEffect, useState } from "react";

import { useAuth } from "@/domains/auth";
import {
  requestStudentClip,
  StudentClipError,
  type StudentClip,
  type StudentClipRequester,
} from "../infrastructure/studentClipApi";

/**
 * 클립 패널이 분기하는 상태.
 *
 * <p>`forbidden`(403)과 `notReady` 를 나누는 이유는 할 말이 다르기 때문이다 — 앞은 권한 문제,
 * 뒤는 시간 문제다. `notReady` 는 404(산출물 미생성)를 기본으로 하되 409(진행 중)도 흡수한다.
 * 서버가 퀴즈 경로처럼 진행 중을 404 로 번역해 주는 계약이지만, 참여도 타임라인처럼 409 를
 * 그대로 내보내는 선례도 있어 어느 쪽이 와도 "아직 준비 전"으로 읽는다.
 */
export type StudentClipStatus = "loading" | "ready" | "forbidden" | "notReady" | "failed";

export type UseStudentClipResult = {
  readonly status: StudentClipStatus;
  readonly clip: StudentClip | null;
  readonly retry: () => void;
  /**
   * 다시 조회해 새 녹화 URL 만 돌려준다. 재생 중 URL 만료(재발급) 전용이라 화면 상태를
   * 건드리지 않는다 — 성공하면 새 URL, 실패하면 null 이다.
   */
  readonly reissueRecordingUrl: () => Promise<string | null>;
};

export type UseStudentClipOptions = {
  readonly sessionId: string;
  readonly request?: StudentClipRequester;
};

const statusOf = (error: unknown): StudentClipStatus => {
  if (error instanceof StudentClipError) {
    if (error.status === 403) return "forbidden";
    if (error.status === 404 || error.status === 409) return "notReady";
  }
  return "failed";
};

/**
 * 복습 클립(녹화·전사)을 한 번 조회한다.
 *
 * <p>폴링하지 않는다. 종료된 세션의 사후 산출물이라 다시 물어도 값이 바뀌지 않는다.
 * 유일한 예외가 `reissueRecordingUrl` — 단기 URL 은 시간이 지나면 죽는 값이라 그것만 다시 받는다.
 *
 * <p>언마운트하면 진행 중인 요청을 취소하고 상태를 바꾸지 않는다(useAttentionTimeline 과 동일).
 */
export function useStudentClip(options: UseStudentClipOptions): UseStudentClipResult {
  const { sessionId, request = requestStudentClip } = options;
  const { accessToken } = useAuth();

  // 값 자체는 쓰지 않는다. 효과를 다시 돌리기 위한 트리거다.
  const [attempt, setAttempt] = useState(0);
  const key = `${sessionId}|${attempt}`;

  // 결과에 그 결과를 만든 시도를 함께 담는다. 재시도 순간 이전 결과가 보이지 않게 한다.
  const [answer, setAnswer] = useState<{
    key: string;
    status: StudentClipStatus;
    clip: StudentClip | null;
  }>({ key: "", status: "loading", clip: null });

  const retry = useCallback(() => setAttempt((count) => count + 1), []);

  const reissueRecordingUrl = useCallback(async (): Promise<string | null> => {
    // 토큰이 사라졌다면(로그아웃) 재발급할 방법이 없다.
    if (accessToken === null) return null;
    try {
      const fresh = await request(sessionId, accessToken);
      return fresh.recordingUrl;
    } catch {
      // 재발급 실패는 재생만 멈춘다. 이미 그려진 전사까지 오류로 뒤집지 않는다.
      return null;
    }
  }, [sessionId, accessToken, request]);

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
        setAnswer({ key, status: "ready", clip: result });
      })
      .catch((error: unknown) => {
        // 취소는 실패가 아니다. 화면을 떠났거나 다시 조회하는 중이다.
        if (!active || controller.signal.aborted) return;
        setAnswer({ key, status: statusOf(error), clip: null });
      });

    return () => {
      active = false;
      controller.abort();
    };
  }, [sessionId, accessToken, request, key]);

  // 아직 이번 시도의 답이 오지 않았으면 로딩이다. 이전 시도의 결과를 물려주지 않는다.
  return answer.key === key
    ? { status: answer.status, clip: answer.clip, retry, reissueRecordingUrl }
    : { status: "loading", clip: null, retry, reissueRecordingUrl };
}
