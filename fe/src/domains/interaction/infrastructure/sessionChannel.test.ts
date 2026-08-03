import { describe, expect, it } from "vitest";

import { sessionChannelUrl } from "./sessionChannel";

const url = (
  apiBaseUrl: string | undefined,
  origin: string,
  isDevelopment = false,
): string => sessionChannelUrl({ apiBaseUrl, origin, isDevelopment });

describe("sessionChannelUrl", () => {
  it("API 주소의 스킴만 ws 로 바꾼다", () => {
    expect(url("http://localhost:8080", "http://localhost:3000")).toBe("ws://localhost:8080/ws");
  });

  it("https 는 wss 가 된다", () => {
    expect(url("https://zani.example.com", "https://zani.example.com")).toBe(
      "wss://zani.example.com/ws",
    );
  });

  it("끝의 슬래시를 중복시키지 않는다", () => {
    expect(url("http://localhost:8080/", "http://localhost:3000")).toBe("ws://localhost:8080/ws");
  });

  /**
   * 개발 서버는 Next rewrite 로 `/api` 를 백엔드에 넘기는 구성이라 API 주소가 비어 있다. 그런데
   * rewrite 는 WebSocket 업그레이드를 프록시하지 않아, 같은 출처로 붙으면 `/ws` 를 받아 줄 대상이 없다.
   */
  it("개발 모드에서 API 주소가 비어 있으면 백엔드로 직접 붙는다", () => {
    expect(url(undefined, "http://localhost:3000", true)).toBe("ws://localhost:8080/ws");
    expect(url("", "http://localhost:3000", true)).toBe("ws://localhost:8080/ws");
  });

  /** 개발 모드에서도 값이 있으면 그 값이 이긴다(다른 포트·원격 백엔드를 가리키는 구성). */
  it("개발 모드라도 API 주소가 있으면 그 값을 쓴다", () => {
    expect(url("http://localhost:9000", "http://localhost:3000", true)).toBe(
      "ws://localhost:9000/ws",
    );
  });

  /** 배포에서 값이 비어 있으면 같은 도메인을 쓴다는 뜻이다(Nginx 가 `/ws` 를 넘겨야 한다). */
  it("배포에서 API 주소가 비어 있으면 같은 출처로 붙는다", () => {
    expect(url(undefined, "https://zani.example.com")).toBe("wss://zani.example.com/ws");
  });
});
