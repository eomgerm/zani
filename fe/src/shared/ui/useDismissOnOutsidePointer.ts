"use client";

import { useEffect, type RefObject } from "react";

/**
 * 열려 있는 팝오버·드롭다운을 바깥 포인터 입력으로 닫는다.
 *
 * click 이 아니라 pointerdown 을 듣는다 — 여는 버튼의 click 이 document 까지 올라와
 * 방금 연 팝오버를 그 자리에서 닫는 일이 없고, 터치·펜 입력도 함께 받는다.
 * 여는 버튼은 반드시 rootRef 안에 두어야 토글(다시 눌러 닫기)이 살아 있다.
 */
export function useDismissOnOutsidePointer(
  rootRef: RefObject<HTMLElement | null>,
  open: boolean,
  onDismiss: () => void,
) {
  useEffect(() => {
    if (!open) {
      return;
    }
    function handlePointerDown(event: PointerEvent) {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) {
        onDismiss();
      }
    }
    document.addEventListener("pointerdown", handlePointerDown);
    return () => document.removeEventListener("pointerdown", handlePointerDown);
  }, [rootRef, open, onDismiss]);
}
