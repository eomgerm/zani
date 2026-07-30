import { describe, expect, it } from "vitest";

import { sessionChannelUrl } from "./sessionChannel";

describe("sessionChannelUrl", () => {
  it("API 주소의 스킴만 ws 로 바꾼다", () => {
    expect(sessionChannelUrl("http://localhost:8080", "http://localhost:3000")).toBe(
      "ws://localhost:8080/ws",
    );
  });

  it("https 는 wss 가 된다", () => {
    expect(sessionChannelUrl("https://zani.example.com", "https://zani.example.com")).toBe(
      "wss://zani.example.com/ws",
    );
  });

  it("끝의 슬래시를 중복시키지 않는다", () => {
    expect(sessionChannelUrl("http://localhost:8080/", "http://localhost:3000")).toBe(
      "ws://localhost:8080/ws",
    );
  });

  /** 로컬에서 프록시를 쓰는 구성. 이 값은 빌드 시점에 박히므로 런타임에 바꿀 수 없다. */
  it("API 주소가 비어 있으면 같은 출처로 붙는다", () => {
    expect(sessionChannelUrl(undefined, "http://localhost:3000")).toBe("ws://localhost:3000/ws");
    expect(sessionChannelUrl("", "https://zani.example.com")).toBe("wss://zani.example.com/ws");
  });
});
