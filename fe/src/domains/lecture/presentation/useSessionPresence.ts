"use client";

import { useEffect, useState } from "react";

import { useAuth } from "@/domains/auth";
import {
  PresenceReportError,
  reportPresence,
  type PresenceConnectionState,
  type PresenceReconnectStatus,
  type PresenceReporter,
} from "../infrastructure/presenceApi";
import { useRoomReconnect, type ReconnectStatus } from "./useRoomReconnect";

/**
 * heartbeat 주기(ms). 서버 presence 키 TTL(30초)보다 넉넉히 짧게 잡아, 한 번 실패해도 접속으로 유지된다.
 */
const HEARTBEAT_INTERVAL_MS = 10_000;

/** 멤버십이 없다는 응답. 더 보내봐야 결과가 같아 즉시 중단한다. */
const NOT_A_MEMBER_STATUS = 403;
/** 수업이 이미 끝났다는 응답. 강사의 명시 종료·3시간 자동 종료가 모두 이걸로 돌아온다. */
const SESSION_CLOSED_STATUS = 409;

const NOT_A_MEMBER_MESSAGE = "이 수업의 참가자가 아니라 상태를 보고할 수 없습니다.";

export type SessionPresenceState = {
  /** 마지막 heartbeat 응답의 재연결·세션 신호. 아직 응답이 없으면 null. */
  reconnectStatus: PresenceReconnectStatus | null;
  /**
   * 수업이 끝났는지. 왜 끝났는지는 가리지 않는다 — 강사의 명시 종료, 3시간 자동 종료, 강사 미복귀가 모두 여기에 들어온다.
   *
   * <p>이걸 한 값으로 합치는 이유는 화면이 해야 할 일이 하나이기 때문이다: 강의실을 떠나야 한다. 문구만 이유에 따라 다르다.
   */
  sessionEnded: boolean;
  /** 보고를 중단한 이유. 수업 종료는 여기 담지 않는다(sessionEnded 가 답한다). 일시적 실패도 담지 않는다(다음 주기에 재시도). */
  error: string | null;
};

/** LiveKit 재연결 상태를 서버가 아는 연결 상태로 옮긴다. */
function toConnectionState(status: ReconnectStatus): PresenceConnectionState {
  if (status === "stable") {
    return "CONNECTED";
  }
  return status === "failed" ? "DISCONNECTED" : "RECONNECTING";
}

export type UseSessionPresenceOptions = {
  /** heartbeat 주기(ms). 테스트에서 짧게 조정한다. */
  intervalMs?: number;
  /** 보고 어댑터. 테스트에서 대체한다. */
  report?: PresenceReporter;
};

/**
 * 강의실에 있는 동안 서버에 presence heartbeat를 주기적으로 보고한다(가이드 §12).
 * 연결 상태는 자동 재연결 훅(useRoomReconnect)에서 가져오므로, 재연결·재입장 구간도 그대로 서버에 전달된다.
 * 세션이 종료되거나 보고가 거부되면(멤버십 없음·방 종료) 타이머를 멈추고, 일시적 실패는 다음 주기에 재시도한다.
 */
export function useSessionPresence(
  sessionId: string,
  options: UseSessionPresenceOptions = {},
): SessionPresenceState {
  const { intervalMs = HEARTBEAT_INTERVAL_MS, report = reportPresence } = options;
  const { status } = useRoomReconnect();
  const { accessToken } = useAuth();
  const connectionState = toConnectionState(status);

  // 첫 연결이 끝난 적 있는지를 나타내는 래치. 강의실에 들어온 직후 LiveKit 핸드셰이크가 끝나기 전에는
  // 상태가 "reconnecting" 인데, 이걸 그대로 보고하면 서버가 강사 이탈로 보고 5분 유예를 시작한다.
  // 그래서 수업을 만든 직후 "강사 연결이 끊겼습니다" 가 잠깐 떴다 사라졌다.
  //
  // 한 번이라도 붙은 뒤의 재연결은 진짜 이탈이므로 그때부터는 그대로 보고한다.
  const [everConnected, setEverConnected] = useState(false);
  useEffect(() => {
    if (status === "stable" && !everConnected) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setEverConnected(true);
    }
  }, [status, everConnected]);

  const [reconnectStatus, setReconnectStatus] = useState<PresenceReconnectStatus | null>(null);
  const [sessionEnded, setSessionEnded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const stopped = sessionEnded || error !== null;

  useEffect(() => {
    // 로그인 없이는 heartbeat 를 보고할 수 없다. 401 을 반복하는 대신 보내지 않는다.
    // 첫 연결을 기다리는 동안에도 보내지 않는다(위 래치 설명 참고).
    if (stopped || accessToken === null || !everConnected) {
      return;
    }

    let isCurrent = true;
    const abortController = new AbortController();

    const send = async () => {
      try {
        const snapshot = await report(
          sessionId,
          { heartbeatAt: new Date().toISOString(), connectionState },
          accessToken,
          abortController.signal,
        );
        if (!isCurrent) {
          return;
        }
        setReconnectStatus(snapshot.reconnectStatus);
        if (snapshot.sessionEnded || snapshot.reconnectStatus === "SESSION_ENDED") {
          setSessionEnded(true);
        }
      } catch (failure) {
        if (!isCurrent || !(failure instanceof PresenceReportError)) {
          // 일시적 실패(네트워크·5xx)는 다음 주기에 다시 보낸다.
          return;
        }
        // 수업이 끝난 건 오류가 아니라 상태다. 강사가 종료하면 다음 heartbeat 가 이걸 받는데,
        // 문구만 띄우고 남겨두면 학생이 끝난 수업에 카메라를 켠 채로 머문다.
        if (failure.status === SESSION_CLOSED_STATUS) {
          setSessionEnded(true);
          return;
        }
        if (failure.status === NOT_A_MEMBER_STATUS) {
          setError(NOT_A_MEMBER_MESSAGE);
        }
      }
    };

    // 첫 보고를 effect 본문 밖(지연)으로 빼서 렌더-이펙트 동기 setState를 피한다.
    const initial = setTimeout(() => void send(), 0);
    const timer = setInterval(() => void send(), intervalMs);
    return () => {
      isCurrent = false;
      abortController.abort();
      clearTimeout(initial);
      clearInterval(timer);
    };
  }, [sessionId, connectionState, intervalMs, report, stopped, accessToken, everConnected]);

  return { reconnectStatus, sessionEnded, error };
}
