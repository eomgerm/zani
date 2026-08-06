import { afterEach, describe, expect, it } from "vitest";

import { anchorStartMsOf } from "./useReportSelection";

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
