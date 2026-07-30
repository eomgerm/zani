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

/** 더 보내봐야 결과가 같은 상태 코드. 멤버십 없음(403)·방 종료(409)는 즉시 중단한다. */
const TERMINAL_STATUSES = [403, 409];

const NOT_A_MEMBER_MESSAGE = "이 수업의 참가자가 아니라 상태를 보고할 수 없습니다.";
const SESSION_CLOSED_MESSAGE = "이미 종료된 수업입니다.";

export type SessionPresenceState = {
  /** 마지막 heartbeat 응답의 재연결·세션 신호. 아직 응답이 없으면 null. */
  reconnectStatus: PresenceReconnectStatus | null;
  /** 강사 미복귀로 세션이 종료되었는지. */
  sessionEnded: boolean;
  /** 보고를 중단한 이유. 일시적 실패는 담지 않는다(다음 주기에 재시도). */
  error: string | null;
};

/** LiveKit 재연결 상태를 서버가 아는 연결 상태로 옮긴다. */
function toConnectionState(status: ReconnectStatus): PresenceConnectionState {
  if (status === "stable") {
    return "CONNECTED";
  }
  return status === "failed" ? "DISCONNECTED" : "RECONNECTING";
}

function terminalMessage(status: number): string {
  return status === 403 ? NOT_A_MEMBER_MESSAGE : SESSION_CLOSED_MESSAGE;
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
        if (!isCurrent) {
          return;
        }
        // 일시적 실패(네트워크·5xx)는 다음 주기에 다시 보낸다.
        if (failure instanceof PresenceReportError && TERMINAL_STATUSES.includes(failure.status)) {
          setError(terminalMessage(failure.status));
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
