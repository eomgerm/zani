"use client";

import { useEffect, useRef, useState } from "react";

import { chatContentOf, type SessionEventEnvelope } from "../infrastructure/sessionEvent";
import { useSessionChannel } from "./SessionChannelProvider";

/** 토스트 표시 시간. 지나면 저절로 사라진다. */
export const SESSION_EVENT_TOAST_MS = 3_000;

export interface SessionEventToast {
  /** 서버가 부여한 eventId. 새 토스트가 이전 것을 대체할 때 리마운트 키로 쓴다. */
  readonly key: string;
  readonly message: string;
}

export interface UseSessionEventToastOptions {
  /** 내 LiveKit participant identity. 내 행동의 echo 를 알림으로 세지 않는 기준이다. */
  readonly myIdentity: string | null;
  /**
   * 토스트를 보여줄 표면이 지금 떠 있는지(예: 화면 공유 중 PiP 창 열림). 꺼져 있는 동안 도착한
   * 이벤트는 버린다 — 나중에 표면이 다시 떠도 지난 알림을 되살리지 않는다.
   */
  readonly active: boolean;
  /**
   * 지금 손을 든 참가자(서버 확정 값). 서버는 재시도에도 같은 손들기 알림을 다시 보내므로(멱등),
   * 이미 든 손의 재수신을 새 소식으로 띄우지 않는 기준이다.
   */
  readonly raisedIdentities: readonly string[];
  /** 표시 시간(ms). 테스트에서 줄이려고 열어 둔다. */
  readonly durationMs?: number;
}

/**
 * 손들기·채팅 이벤트를 한 건짜리 토스트 문구로 바꾼다. 토스트감이 아니면 null.
 *
 * <p>여러 건이 몰려도 스택을 쌓지 않고 최신 것이 대체한다 — 주 소비처인 PiP 창이 좁아
 * 쌓으면 참가자 비디오를 가린다.
 */
function toastMessageOf(
  event: SessionEventEnvelope,
  myIdentity: string | null,
  raisedIdentities: readonly string[],
): string | null {
  // 내 행동의 echo 는 알림이 아니다(useChatUnread 와 같은 판정).
  if (myIdentity !== null && event.sender.identity === myIdentity) return null;
  switch (event.type) {
    case "HAND_RAISED":
      return raisedIdentities.includes(event.sender.identity)
        ? null
        : `${event.sender.displayName} 님이 손을 들었어요`;
    case "CHAT_MESSAGE": {
      const content = chatContentOf(event);
      return content === null ? null : `${event.sender.displayName}: ${content}`;
    }
    default:
      return null;
  }
}

/**
 * 실시간 손들기·채팅을 자동 소멸 토스트로 알려준다. 메인 창이 가려진 동안(화면 공유 중 PiP)
 * 쓰라고 만들었지만, 판정은 표면을 모른다 — `active` 로 껐다 켤 뿐이다.
 *
 * <p>실시간 이벤트 스트림만 본다. 재입장·재연결로 복원되는 과거 이력은 스냅샷(`getlivestate`)으로
 * 흐르고 이 훅은 채널 이벤트 리스너만 구독하므로, 복원된 이력이 토스트로 뜨는 일이 구조적으로 없다
 * (useChatUnread 와 같은 원칙).
 */
export function useSessionEventToast(options: UseSessionEventToastOptions): SessionEventToast | null {
  const { myIdentity, active, raisedIdentities, durationMs = SESSION_EVENT_TOAST_MS } = options;
  const { addEventListener } = useSessionChannel();
  const [toast, setToast] = useState<SessionEventToast | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 판정 기준은 ref 로 읽는다. 기준이 바뀔 때마다 리스너를 갈아끼우면 해제와 재등록 사이에
  // 도착한 프레임을 놓친다(useChatUnread 와 같은 이유).
  const activeRef = useRef(active);
  const identityRef = useRef(myIdentity);
  const raisedRef = useRef(raisedIdentities);
  useEffect(() => {
    activeRef.current = active;
    identityRef.current = myIdentity;
    raisedRef.current = raisedIdentities;
  }, [active, myIdentity, raisedIdentities]);

  useEffect(
    () =>
      addEventListener((event) => {
        if (!activeRef.current) return;
        // 손들기 판정이 읽는 raisedRef 는 아직 이 이벤트가 반영되기 전 목록이다 — useRaisedHands 의
        // 상태 갱신은 이 리스너들이 모두 돈 뒤에 커밋되므로, 같은 이벤트가 자기 자신을 지우지 않는다.
        const message = toastMessageOf(event, identityRef.current, raisedRef.current);
        if (message === null) return;
        setToast({ key: event.eventId, message });
        if (timerRef.current !== null) clearTimeout(timerRef.current);
        timerRef.current = setTimeout(() => {
          timerRef.current = null;
          setToast(null);
        }, durationMs);
      }),
    [addEventListener, durationMs],
  );

  // 언마운트 시 남은 소멸 타이머를 정리한다.
  useEffect(
    () => () => {
      if (timerRef.current !== null) clearTimeout(timerRef.current);
    },
    [],
  );

  // 표면이 닫히는 순간 떠 있던 토스트는 끝난 것이다. 이펙트로 지우면 다시 열릴 때 한 프레임
  // 되살아나므로 렌더 중에 바로 보정한다(useChatUnread 와 같은 패턴).
  if (!active && toast !== null) {
    setToast(null);
  }

  return active ? toast : null;
}
