"use client";

import { useEffect, useRef, useState } from "react";

import { useAuth } from "@/domains/auth";
import { startSession as startSessionApi, type SessionStarter } from "../infrastructure/startSessionApi";

export type StartOnConnectedOptions = {
  sessionId: string;
  /** 서버가 알려준 세션 상태. 아직 모르면 null. */
  sessionStatus: string | null;
  /** 강의실이 실제로 LiveKit 에 연결됐는지. */
  connected: boolean;
  /** 이 사용자가 이 수업의 강사인지. 역할이 확정되기 전에는 false 를 넘긴다. */
  isInstructor: boolean;
  /** 시작 어댑터. 테스트에서 대체한다. */
  startSession?: SessionStarter;
};

/**
 * 강사가 강의실에 실제로 연결된 뒤 수업을 시작시킨다.
 *
 * <p>왜 강의실에서 시작하는가. 생성 화면에서 시작하면 강사가 아직 LiveKit 에 연결되지 않은 상태에서 초대 코드가 열린다. 연결이 실패하면 학생만 강사 없는 방에 들어온다. 연결이 끝난 뒤에
 * 시작하면 실패한 경우 세션이 PREPARING 에 머물러 초대 코드가 열리지 않고, 방치된 세션은 서버가 정리한다.
 *
 * <p>한 번만 보낸다. 서버도 멱등하지만(두 번째는 {@code started: false}) 재연결마다 요청을 보낼 이유가 없다.
 *
 * <p>실패는 화면에 띄우지 않고 여기서 멈춘다. 강사는 이미 방 안에 있고 수업을 진행할 수 있다 — 다만 학생이 못 들어오므로 원인은 콘솔에 남긴다.
 */
export function useStartOnConnected({
  sessionId,
  sessionStatus,
  connected,
  isInstructor,
  startSession = startSessionApi,
}: StartOnConnectedOptions): { started: boolean } {
  const { accessToken } = useAuth();
  const [started, setStarted] = useState(false);
  // 재렌더마다 다시 보내지 않도록 요청 여부를 남긴다. state 로 두면 첫 렌더에서 중복 발사된다.
  const requested = useRef(false);

  useEffect(() => {
    if (
      requested.current ||
      !connected ||
      !isInstructor ||
      sessionStatus !== "PREPARING" ||
      accessToken === null
    ) {
      return;
    }

    requested.current = true;
    let active = true;
    startSession(sessionId, accessToken)
      .then(() => {
        if (active) setStarted(true);
      })
      .catch((caught: unknown) => {
        // 다시 시도할 수 있게 잠금을 푼다. 연결이 흔들려 재시도되는 경우가 있다.
        requested.current = false;
        console.warn("수업 시작 실패 — 초대 코드가 아직 열리지 않았습니다", caught);
      });

    return () => {
      active = false;
    };
  }, [sessionId, sessionStatus, connected, isInstructor, accessToken, startSession]);

  return { started };
}
