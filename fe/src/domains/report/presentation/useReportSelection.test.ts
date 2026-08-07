import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { anchorStartMsOf, useReportSelection } from "./useReportSelection";

/**
 * 앵커 추출은 화면으로 검증되지 않는다.
 *
 * <p>한 구간 밀린 앵커를 보내도 서버는 옆 구간의 전사로 유창하게 답한다. 어느 구간을 짚었는지 여기서
 * 못박아 두지 않으면 그 어긋남을 잡을 곳이 없다.
 */
describe("anchorStartMsOf", () => {
  afterEach(() => {
    document.body.innerHTML = "";
  });

  const render = (html: string) => {
    document.body.innerHTML = html;
  };

  it("reads the anchor from the nearest section row", () => {
    render(`
      <ul>
        <li data-section-start-ms="60000"><p id="earlier">해시 테이블</p></li>
        <li data-section-start-ms="95000"><p id="target">해시 충돌 해결</p></li>
      </ul>
    `);

    const text = document.getElementById("target")!.firstChild;

    // 선택은 거의 항상 텍스트 노드에서 시작한다. closest 가 없으므로 부모로 한 번 올려야 한다.
    expect(anchorStartMsOf(text)).toBe(95_000);
  });

  it("reads the anchor when the node is already an element", () => {
    render(`<li data-section-start-ms="95000"><p id="target">해시 충돌</p></li>`);

    expect(anchorStartMsOf(document.getElementById("target"))).toBe(95_000);
  });

  it("keeps the exact millisecond instead of a rounded second", () => {
    render(`<li data-section-start-ms="200400"><p id="target">구간</p></li>`);

    // 초로 반올림하면 200,400ms 가 200,000ms 가 되어 앞 구간에 떨어진다. 앵커는 ms 그대로여야 한다.
    expect(anchorStartMsOf(document.getElementById("target")!.firstChild)).toBe(200_400);
  });

  it("keeps zero as a real anchor", () => {
    render(`<li data-section-start-ms="0"><p id="target">첫 구간</p></li>`);

    // 수업 도입부다. falsy 로 걸러 내면 첫 구간을 드래그한 사람이 앵커 없음으로 떨어진다.
    expect(anchorStartMsOf(document.getElementById("target"))).toBe(0);
  });

  it("reports no anchor for a drag outside every section", () => {
    render(`<p id="paragraph">수업 전체 요약 문단입니다.</p>`);

    // 오류가 아니다. 서버가 전사 대신 전 구간 요약으로 답하는 갈래다.
    expect(anchorStartMsOf(document.getElementById("paragraph"))).toBeNull();
  });

  it("reports no anchor when the attribute is not a number", () => {
    render(`<li data-section-start-ms="아님"><p id="target">구간</p></li>`);

    // NaN 을 그대로 보내면 서버가 400 을 내고 화면은 원인을 알 수 없는 실패만 본다.
    expect(anchorStartMsOf(document.getElementById("target"))).toBeNull();
  });

  it("reports no anchor for a null node", () => {
    expect(anchorStartMsOf(null)).toBeNull();
  });
});

/**
 * 드래그를 어디까지 "요약 카드 안" 으로 볼 것인가, 그리고 스크롤 뒤에도 버튼이 제자리인가.
 *
 * <p>둘 다 실제로 겪은 버그다 — 채팅 패널을 카드 안에 두었더니 답변을 긁어 복사하려는 선택까지
 * 질문으로 잡혔고, 버튼이 position: fixed 라 스크롤하면 글과 따로 놀았다.
 */
describe("useReportSelection", () => {
  const inViewport = { top: 100, bottom: 120, left: 50, right: 130, width: 80, height: 20 } as DOMRect;
  const scrolledAway = { top: -80, bottom: -60, left: 50, right: 130, width: 80, height: 20 } as DOMRect;

  let rect = inViewport;
  const originalRect = Range.prototype.getBoundingClientRect;

  beforeEach(() => {
    rect = inViewport;
    Range.prototype.getBoundingClientRect = () => rect;
  });

  afterEach(() => {
    Range.prototype.getBoundingClientRect = originalRect;
    window.getSelection()?.removeAllRanges();
    document.body.innerHTML = "";
  });

  const selectContentsOf = (element: Element) => {
    const range = document.createRange();
    range.selectNodeContents(element);
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    selection.addRange(range);
  };

  const settle = () => act(() => void document.dispatchEvent(new MouseEvent("mouseup")));

  /** 카드와 그 **밖**의 채팅 패널. 실제 렌더 구조와 같은 모양이다. */
  const layout = () => {
    document.body.innerHTML = `
      <div id="card">
        <li data-section-start-ms="95000"><p id="section">해시 충돌 해결</p></li>
      </div>
      <aside id="panel"><p id="answer">충돌이 나면 빈 자리를 찾습니다.</p></aside>
    `;
    return { current: document.getElementById("card") };
  };

  it("카드 안을 드래그하면 앵커와 좌표를 준다", () => {
    const cardRef = layout();
    const { result } = renderHook(() => useReportSelection(cardRef));

    selectContentsOf(document.getElementById("section")!);
    settle();

    expect(result.current.selection?.anchorStartMs).toBe(95_000);
    expect(result.current.selection?.y).toBe(100);
  });

  it("채팅 패널 안의 드래그는 잡지 않는다", () => {
    const cardRef = layout();
    const { result } = renderHook(() => useReportSelection(cardRef));

    selectContentsOf(document.getElementById("answer")!);
    settle();

    // 답변을 긁어 복사하려던 것이지 새 질문이 아니다. 패널을 카드 안에 두면 이게 깨진다.
    expect(result.current.selection).toBeNull();
  });

  it("스크롤로 선택이 화면 밖으로 나가면 버튼을 거둔다", () => {
    const cardRef = layout();
    const { result } = renderHook(() => useReportSelection(cardRef));

    selectContentsOf(document.getElementById("section")!);
    settle();
    expect(result.current.selection).not.toBeNull();

    rect = scrolledAway;
    act(() => void window.dispatchEvent(new Event("scroll")));

    // 버튼은 fixed 라 화면에 남는다. 가리킬 글이 사라졌으면 버튼도 사라져야 한다.
    expect(result.current.selection).toBeNull();
  });

  it("스크롤해도 선택이 보이면 좌표를 다시 잰다", () => {
    const cardRef = layout();
    const { result } = renderHook(() => useReportSelection(cardRef));

    selectContentsOf(document.getElementById("section")!);
    settle();

    rect = { ...inViewport, top: 40, bottom: 60 } as DOMRect;
    act(() => void window.dispatchEvent(new Event("scroll")));

    // 다시 재지 않으면 버튼이 방금 드래그한 문장이 아니라 엉뚱한 곳을 가리킨다.
    expect(result.current.selection?.y).toBe(40);
  });
});
