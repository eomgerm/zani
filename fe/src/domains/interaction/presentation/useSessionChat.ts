"use client";

import { useCallback, useEffect, useMemo, useReducer, useRef } from "react";

import {
  chatMessageViews,
  chatReducer,
  initialChatState,
  type ChatMessageView,
} from "./chatMessages";
import { useSessionChannel } from "./SessionChannelProvider";

/**
 * 공개 채팅. 스냅샷·실시간 스트림·낙관적 표시를 한 목록으로 합친다.
 *
 * 전송은 STOMP 라 응답이 없다. 서버가 거절하면 `/user/queue/errors` 로 사유가 오지만, 연결이
 * 끊겨 프레임이 사라진 경우는 아무 신호도 없다. 그래서 확정(echo)이 제한 시간 안에 오지 않으면
 * 실패로 표시한다 — 이 장치가 없으면 보내는 중 상태로 영원히 남는다.
 */

/** 확정을 기다리는 시간. 넘기면 실패로 표시하고 다시 보낼 수 있게 한다. */
export const CHAT_SEND_TIMEOUT_MS = 10_000;

const TIMEOUT_REASON = "SEND_TIMEOUT";

export interface UseSessionChatOptions {
  /** 내 LiveKit participant identity. 연결 전이면 null — 그때는 낙관적 항목만 내 것으로 본다. */
  readonly myIdentity: string | null;
  /** 낙관적으로 그릴 때 쓸 내 표시 이름. */
  readonly myDisplayName: string;
  readonly amInstructor: boolean;
  createClientEventId?: () => string;
  sendTimeoutMs?: number;
}

export interface UseSessionChatResult {
  readonly messages: readonly ChatMessageView[];
  /** 채널이 붙어 있어 보낼 수 있는지. 끊긴 동안 입력을 막는다. */
  readonly canSend: boolean;
  /** 보낼 수 없거나 본문이 비면 아무 일도 하지 않는다. */
  send: (content: string) => void;
  retry: (clientEventId: string) => void;
}

let fallbackSequence = 0;

/** `crypto.randomUUID` 가 없는 환경(구형 브라우저·일부 테스트 런너)을 위한 대비. */
function defaultClientEventId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  fallbackSequence += 1;
  return `c-${Date.now()}-${fallbackSequence}`;
}

export function useSessionChat(options: UseSessionChatOptions): UseSessionChatResult {
  const {
    myIdentity,
    myDisplayName,
    amInstructor,
    createClientEventId = defaultClientEventId,
    sendTimeoutMs = CHAT_SEND_TIMEOUT_MS,
  } = options;

  const { state, snapshot, publishChat, addEventListener, addRejectionListener } =
    useSessionChannel();
  const [chatState, dispatch] = useReducer(chatReducer, initialChatState);

  /** 확정을 기다리는 전송의 타이머. 확정·거절이 오면 지운다. */
  const pendingTimers = useRef(new Map<string, ReturnType<typeof setTimeout>>());
  const clearPending = useCallback((clientEventId: string) => {
    const timer = pendingTimers.current.get(clientEventId);
    if (timer !== undefined) {
      clearTimeout(timer);
      pendingTimers.current.delete(clientEventId);
    }
  }, []);

  useEffect(() => {
    const timers = pendingTimers.current;
    return () => {
      timers.forEach(clearTimeout);
      timers.clear();
    };
  }, []);

  useEffect(() => {
    if (snapshot === null) return;
    dispatch({ type: "snapshot", snapshot });
  }, [snapshot]);

  useEffect(
    () =>
      addEventListener((event) => {
        if (event.type !== "CHAT_MESSAGE") return;
        if (event.clientEventId !== null) clearPending(event.clientEventId);
        dispatch({ type: "received", event });
      }),
    [addEventListener, clearPending],
  );

  useEffect(
    () =>
      addRejectionListener((rejection) => {
        clearPending(rejection.clientEventId);
        dispatch({
          type: "failed",
          clientEventId: rejection.clientEventId,
          reason: rejection.reason,
        });
      }),
    [addRejectionListener, clearPending],
  );

  const canSend = state === "connected";

  const publish = useCallback(
    (clientEventId: string, content: string) => {
      publishChat(clientEventId, content);
      pendingTimers.current.set(
        clientEventId,
        setTimeout(() => {
          pendingTimers.current.delete(clientEventId);
          dispatch({ type: "failed", clientEventId, reason: TIMEOUT_REASON });
        }, sendTimeoutMs),
      );
    },
    [publishChat, sendTimeoutMs],
  );

  const send = useCallback(
    (content: string) => {
      const trimmed = content.trim();
      if (!canSend || trimmed.length === 0) return;

      const clientEventId = createClientEventId();
      dispatch({
        type: "sending",
        clientEventId,
        content: trimmed,
        authorName: myDisplayName,
        isInstructor: amInstructor,
      });
      publish(clientEventId, trimmed);
    },
    [canSend, createClientEventId, myDisplayName, amInstructor, publish],
  );

  const retry = useCallback(
    (clientEventId: string) => {
      if (!canSend) return;
      const failed = chatState.entries.find(
        (entry) => entry.clientEventId === clientEventId && entry.status === "failed",
      );
      if (failed === undefined) return;

      dispatch({ type: "retrying", clientEventId });
      // 같은 clientEventId 로 다시 보낸다. 서버가 멱등 처리하므로 첫 전송이 실제로 도착했더라도 두 번 저장되지 않는다.
      publish(clientEventId, failed.content);
    },
    [canSend, chatState.entries, publish],
  );

  const messages = useMemo(
    () => chatMessageViews(chatState.entries, myIdentity),
    [chatState.entries, myIdentity],
  );

  return { messages, canSend, send, retry };
}
