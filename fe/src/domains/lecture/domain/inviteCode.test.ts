import { describe, expect, it } from "vitest";

import { canonicalInviteCode, inviteCodeFrom } from "./inviteCode";

describe("canonicalInviteCode", () => {
  it("하이픈·공백을 없애고 대문자로 올린다", () => {
    expect(canonicalInviteCode(" gph7-gq5q ")).toBe("GPH7GQ5Q");
  });
});

describe("inviteCodeFrom", () => {
  /** 강사가 공유하는 건 링크다. 링크를 그대로 보내면 서버가 형식 오류로 거절하므로 여기서 코드를 뽑아야 한다. */
  it.each([
    ["초대 링크", "http://localhost:3000/prejoin/GPH7GQ5Q"],
    ["끝에 슬래시가 붙은 링크", "http://localhost:3000/prejoin/GPH7GQ5Q/"],
    ["쿼리가 붙은 링크", "https://zani.example.com/prejoin/GPH7GQ5Q?from=kakao"],
    ["코드만", "GPH7GQ5Q"],
    ["소문자 코드", "gph7gq5q"],
    ["표시형 코드", "GPH7-GQ5Q"],
    ["앞뒤 공백", "  GPH7GQ5Q  "],
  ])("%s 에서 코드를 뽑는다", (_, input) => {
    expect(inviteCodeFrom(input)).toBe("GPH7GQ5Q");
  });

  /** 형식이 어긋나면 서버까지 보내지 않고 입력한 화면에서 바로 알려준다. */
  it.each([
    ["빈 값", ""],
    ["공백만", "   "],
    ["7자", "ZANI8KQ"],
    ["9자", "GPH7GQ5QX"],
    ["허용하지 않는 문자", "GPH7GQ5!"],
    ["코드가 없는 링크", "http://localhost:3000/prejoin/"],
  ])("%s 는 거절한다", (_, input) => {
    expect(inviteCodeFrom(input)).toBeNull();
  });
});
