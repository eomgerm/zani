import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { useDocumentPictureInPicture } from "./useDocumentPictureInPicture";

/**
 * PiP 창 대역. 실제 API 는 브라우저만 주므로 document 와 최소 창 인터페이스만 흉내 낸다.
 * 훅이 손대는 것은 head(스타일 복제)와 body(정규화)뿐이다.
 */
function fakePipWindow() {
  const doc = document.implementation.createHTMLDocument("pip");
  return {
    document: doc,
    addEventListener: vi.fn(),
    close: vi.fn(),
  } as unknown as Window;
}

function stubApi(pip: Window) {
  Object.defineProperty(window, "documentPictureInPicture", {
    configurable: true,
    value: { requestWindow: vi.fn(async () => pip), window: null },
  });
}

afterEach(() => {
  Reflect.deleteProperty(window, "documentPictureInPicture");
});

describe("useDocumentPictureInPicture", () => {
  /**
   * copyStyles 는 부모 문서의 규칙을 통째로 옮긴다 — body 규칙도 함께 간다.
   *
   * <p>앱 body 에는 지원 하한(--app-min-width, 1280px)이 걸려 있는데, 그것이 260px 짜리 PiP 창에
   * 따라 들어가면 창 안에 가로 스크롤이 생긴다. PiP 는 브라우저가 크기를 정하는 창이라 하한이라는
   * 개념 자체가 없으므로 열 때 풀어 줘야 한다.
   */
  it("PiP body 에서 앱의 최소 폭 하한을 푼다", async () => {
    const pip = fakePipWindow();
    stubApi(pip);

    const { result } = renderHook(() => useDocumentPictureInPicture());
    await act(async () => {
      await result.current.open();
    });

    // CSSOM 이 길이값을 정규화하므로 "0" 이 아니라 "0px" 로 읽힌다.
    expect(pip.document.body.style.minWidth).toBe("0px");
    // 여백 정규화는 원래 있던 것이다. 함께 확인해 둔다 — 둘 다 같은 이유로 필요하다.
    expect(pip.document.body.style.margin).toBe("0px");
  });

  it("지원하지 않는 브라우저에서는 창을 열지 않는다", async () => {
    const { result } = renderHook(() => useDocumentPictureInPicture());

    expect(result.current.supported).toBe(false);
    await act(async () => {
      await expect(result.current.open()).resolves.toBeNull();
    });
    expect(result.current.pipWindow).toBeNull();
  });
});
