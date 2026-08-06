"use client";

import type { ReportSelection } from "./useReportSelection";

/**
 * 드래그한 자리 위에 뜨는 "AI에게 묻기" 버튼.
 *
 * <p>메모·형광펜·AI 모드를 고르는 도구 막대 대신 선택하면 뜨는 버튼을 쓴다(Medium·Notion 방식). 모드 토글은
 * 드래그 전에 무엇을 할지 미리 정하게 만드는데, 실제로는 읽다가 막힌 순간에 묻고 싶어진다.
 *
 * <p>`position: fixed` 로 뷰포트에 띄운다. 카드 안에 넣으면 카드의 `overflow` 와 스크롤에 잘린다.
 *
 * <p>`onMouseDown` 에서 기본 동작을 막는 것이 핵심이다. 막지 않으면 버튼을 누르는 순간 선택이 풀려,
 * `onClick` 이 도달할 때는 이미 넘길 선택이 없다.
 */
export function SelectionAskButton({
  selection,
  onAsk,
}: {
  readonly selection: ReportSelection | null;
  readonly onAsk: (selection: ReportSelection) => void;
}) {
  if (selection === null) {
    return null;
  }

  return (
    <button
      type="button"
      onMouseDown={(event) => event.preventDefault()}
      onClick={() => onAsk(selection)}
      style={{ left: selection.x, top: Math.max(selection.y - 44, 8) }}
      className="fixed z-50 -translate-x-1/2 cursor-pointer rounded-full border-0 bg-primary px-3.5 py-2 text-[13px] font-bold text-white shadow-lg"
    >
      AI에게 묻기
    </button>
  );
}
