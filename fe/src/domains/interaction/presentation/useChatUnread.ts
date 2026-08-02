"use client";

import { useEffect, useRef, useState } from "react";

import { useSessionChannel } from "./SessionChannelProvider";

export interface UseChatUnreadOptions {
  /** 내 LiveKit participant identity. 내가 보낸 메시지의 echo 를 새 메시지로 세지 않는 기준이다. */
  readonly myIdentity: string | null;
  /** 채팅 목록이 지금 화면에 보이는지. 보이는 동안 도착한 메시지는 읽지 않음으로 세지 않는다. */
  readonly chatVisible: boolean;
}

/**
 * 채팅이 보이지 않는 동안 남이 보낸 새 메시지가 있었는지.
 *
 * <p>실시간 이벤트 스트림만 센다. 재입장·재연결로 복원되는 과거 이력은 스냅샷(`getlivestate`)으로
 * 흐르고 이 훅은 채널 이벤트 리스너만 구독하므로, 복원된 메시지가 알림으로 표시되는 일이 구조적으로 없다.
 *
 * <p>여러 건이 와도 값은 boolean 하나다 — 숫자 카운트는 범위 밖(티켓 246).
 */
export function useChatUnread(options: UseChatUnreadOptions): boolean {
  const { myIdentity, chatVisible } = options;
  const { addEventListener } = useSessionChannel();
  const [unread, setUnread] = useState(false);

  // 판정 기준(보임 여부·내 identity)은 ref 로 읽는다. 리스너 자체를 패널 여닫을 때마다
  // 갈아끼우면 해제와 재등록 사이에 도착한 프레임을 놓친다.
  const visibleRef = useRef(chatVisible);
  const identityRef = useRef(myIdentity);
  useEffect(() => {
    visibleRef.current = chatVisible;
    identityRef.current = myIdentity;
  }, [chatVisible, myIdentity]);

  useEffect(
    () =>
      addEventListener((event) => {
        if (event.type !== "CHAT_MESSAGE") return;
        // 패널이 열려 있으면 사용자가 이미 보고 있다 — 점을 켜지 않는다.
        if (visibleRef.current) return;
        // 내 전송의 echo. identity 확정 전(null)에는 채팅 입력 자체가 패널 안이라 위 조건이 먼저 거른다.
        if (identityRef.current !== null && event.sender.identity === identityRef.current) return;
        setUnread(true);
      }),
    [addEventListener],
  );

  useEffect(() => {
    if (chatVisible) {
      setUnread(false);
    }
  }, [chatVisible]);

  return unread;
}
