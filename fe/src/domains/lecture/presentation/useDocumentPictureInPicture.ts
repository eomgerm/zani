"use client";

import { useCallback, useEffect, useRef, useState } from "react";

type DocumentPipRequestOptions = { width?: number; height?: number };

/** 표준화 진행 중인 Document Picture-in-Picture API. TS lib 에 아직 없어 최소 형태만 선언한다. */
type DocumentPictureInPictureApi = {
  requestWindow: (options?: DocumentPipRequestOptions) => Promise<Window>;
  readonly window: Window | null;
};

declare global {
  interface Window {
    documentPictureInPicture?: DocumentPictureInPictureApi;
  }
}

/**
 * 부모 문서의 스타일시트를 PiP 창으로 복제한다.
 *
 * <p>PiP 창은 완전히 새 document 라 부모의 스타일을 물려받지 않는다. Tailwind 클래스와 테마 CSS 변수가 먹으려면 규칙을 옮겨야 한다. 규칙을 읽을 수 없는 교차 출처 시트는
 * 링크로 건다.
 */
function copyStyles(target: Window) {
  const head = target.document.head;
  [...document.styleSheets].forEach((sheet) => {
    try {
      const cssText = [...sheet.cssRules].map((rule) => rule.cssText).join("");
      const style = target.document.createElement("style");
      style.textContent = cssText;
      head.appendChild(style);
    } catch {
      if (sheet.href) {
        const link = target.document.createElement("link");
        link.rel = "stylesheet";
        link.href = sheet.href;
        head.appendChild(link);
      }
    }
  });
}

export type DocumentPictureInPicture = {
  /** 이 브라우저가 Document PiP 를 지원하는지. 미지원이면 팝아웃 버튼을 숨긴다. */
  supported: boolean;
  /** 열려 있는 PiP 창. 없으면 null. 여기에 portal 로 내용을 그린다. */
  pipWindow: Window | null;
  /** PiP 창을 연다(사용자 제스처 필요). 이미 열려 있으면 그 창을 돌려준다. */
  open: (options?: DocumentPipRequestOptions) => Promise<Window | null>;
  /** PiP 창을 닫는다. */
  close: () => void;
};

/**
 * Document Picture-in-Picture 창의 생명주기를 관리한다. 이 창은 브라우저 밖 다른 앱 위에도 항상 떠 있어(구글미트식), 공유 중 다른 창으로 전환해도 강의방을 계속 볼 수 있다.
 */
export function useDocumentPictureInPicture(): DocumentPictureInPicture {
  const [pipWindow, setPipWindow] = useState<Window | null>(null);
  const [supported] = useState(
    () => typeof window !== "undefined" && "documentPictureInPicture" in window,
  );
  // requestWindow 는 비동기라, 열리기 전에 open 이 다시 불리면(effect 중복 호출·StrictMode) 창이 둘 뜬다. 진행 중 재진입을 막는다.
  const openingRef = useRef(false);

  const open = useCallback(async (options?: DocumentPipRequestOptions) => {
    const api = typeof window === "undefined" ? undefined : window.documentPictureInPicture;
    if (!api) {
      return null;
    }
    if (api.window) {
      return api.window;
    }
    if (openingRef.current) {
      return null;
    }
    openingRef.current = true;
    let pip: Window;
    try {
      pip = await api.requestWindow({
        width: options?.width ?? 260,
        height: options?.height ?? 460,
      });
    } finally {
      openingRef.current = false;
    }
    copyStyles(pip);
    pip.document.body.style.margin = "0";
    // 앱의 지원 하한(--app-min-width)은 이 창에 해당하지 않는다. copyStyles 가 body 규칙까지
    // 통째로 옮기는 탓에 260px 짜리 창에 1280px 하한이 걸려 가로 스크롤이 생긴다. PiP 는 브라우저가
    // 크기를 정하는 작은 창이라 하한이라는 개념 자체가 없다.
    pip.document.body.style.minWidth = "0";
    // 사용자가 창을 직접 닫으면(네이티브 X) 상태를 되돌린다.
    pip.addEventListener("pagehide", () => setPipWindow(null), { once: true });
    setPipWindow(pip);
    return pip;
  }, []);

  const close = useCallback(() => {
    setPipWindow((current) => {
      current?.close();
      return null;
    });
  }, []);

  // 언마운트(강의실 퇴장) 시 떠 있는 창을 닫는다. 최신 창을 참조로 잡아 둔다.
  const pipRef = useRef<Window | null>(null);
  useEffect(() => {
    pipRef.current = pipWindow;
  }, [pipWindow]);
  useEffect(() => () => pipRef.current?.close(), []);

  return { supported, pipWindow, open, close };
}
