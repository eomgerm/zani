"use client";

import { useCallback, useEffect, useState, type RefObject } from "react";

/**
 * 수업 요약 카드에서 드래그한 선택을 좌표로 바꾼다.
 *
 * <p>선택 지점에서 위로 올라가 가장 가까운 `[data-section-start-ms]` 를 찾는다. 구간 목록은 이미 화면에
 * 그려져 있으므로 API 를 더 부르지 않고도 "어느 구간을 짚었는지" 가 나온다.
 *
 * <p>구간 밖(전체 요약 문단)을 드래그하면 `anchorStartMs` 가 null 이다. 오류가 아니라 서버가 전사 대신
 * 전 구간 요약으로 답하는 갈래다.
 */

/** 선택이 너무 짧으면 클릭으로 생긴 빈 선택이다. 플로팅 버튼이 깜빡이지 않도록 여기서 거른다. */
const MIN_SELECTED_LENGTH = 2;

/** 서버 계약과 같은 상한. 넘겨 보내면 400 이므로 보내기 전에 자른다. */
const MAX_SELECTED_LENGTH = 200;

export type ReportSelection = {
  /** 화면에 보여줄 선택 텍스트. 서버에서 근거로 쓰이지 않는다. */
  readonly text: string;
  /** 드래그한 구간의 시작(ms). 구간 밖이면 null. */
  readonly anchorStartMs: number | null;
  /** 플로팅 버튼을 띄울 뷰포트 좌표 */
  readonly x: number;
  readonly y: number;
};

/**
 * 노드에서 위로 올라가 가장 가까운 구간 좌표를 읽는다.
 *
 * <p>텍스트 노드에는 `closest` 가 없어 부모 엘리먼트로 한 번 올린 뒤 찾는다. 선택은 거의 항상 텍스트
 * 노드에서 시작하므로 이 분기가 기본 경로다.
 */
export const anchorStartMsOf = (node: Node | null): number | null => {
  if (node === null) return null;
  const element = node.nodeType === 1 ? (node as Element) : node.parentElement;
  const value = element?.closest("[data-section-start-ms]")?.getAttribute("data-section-start-ms");
  if (value === null || value === undefined) return null;
  const anchorStartMs = Number(value);
  return Number.isFinite(anchorStartMs) && anchorStartMs >= 0 ? anchorStartMs : null;
};

export function useReportSelection(containerRef: RefObject<HTMLElement | null>) {
  const [selection, setSelection] = useState<ReportSelection | null>(null);

  const clear = useCallback(() => setSelection(null), []);

  useEffect(() => {
    // selectionchange 가 아니라 mouseup/keyup 을 쓴다. 그쪽은 드래그 중에도 계속 발화해서
    // 버튼이 마우스를 따라다니며 깜빡인다.
    const onSelectionSettled = () => {
      const current = window.getSelection();
      if (current === null || current.isCollapsed || current.rangeCount === 0) {
        setSelection(null);
        return;
      }

      const range = current.getRangeAt(0);
      const container = containerRef.current;
      // 카드 밖의 선택은 무시한다. 리포트 화면에는 전사 패널처럼 드래그할 곳이 더 있다.
      if (container === null || !container.contains(range.commonAncestorContainer)) {
        setSelection(null);
        return;
      }

      const text = current.toString().trim();
      if (text.length < MIN_SELECTED_LENGTH) {
        setSelection(null);
        return;
      }

      const rect = range.getBoundingClientRect();
      // 스크롤로 선택이 화면 밖으로 나갔다. 버튼만 화면에 남으면 아무것도 가리키지 않는 채 떠 있다.
      if (rect.bottom < 0 || rect.top > window.innerHeight) {
        setSelection(null);
        return;
      }

      setSelection({
        text: text.slice(0, MAX_SELECTED_LENGTH),
        anchorStartMs: anchorStartMsOf(range.startContainer),
        x: rect.left + rect.width / 2,
        y: rect.top,
      });
    };

    document.addEventListener("mouseup", onSelectionSettled);
    document.addEventListener("keyup", onSelectionSettled);
    // 좌표를 다시 잰다. 버튼은 position: fixed 라 뷰포트에 못 박혀 있는데 글은 스크롤로 움직이므로,
    // 다시 재지 않으면 버튼이 방금 드래그한 문장이 아니라 엉뚱한 곳을 가리킨다.
    //
    // capture 로 듣는 이유는 스크롤이 window 가 아니라 안쪽 컨테이너에서 일어날 수 있어서다 —
    // scroll 이벤트는 버블링하지 않으므로 캡처 단계에서만 잡힌다.
    window.addEventListener("scroll", onSelectionSettled, true);
    window.addEventListener("resize", onSelectionSettled);
    return () => {
      document.removeEventListener("mouseup", onSelectionSettled);
      document.removeEventListener("keyup", onSelectionSettled);
      window.removeEventListener("scroll", onSelectionSettled, true);
      window.removeEventListener("resize", onSelectionSettled);
    };
  }, [containerRef]);

  return { selection, clear };
}
