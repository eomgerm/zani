import { describe, expect, it } from "vitest";

import {
  chatContentOf,
  parseSessionEvent,
  parseSessionEventRejection,
} from "./sessionEvent";

const validEvent = {
  eventId: "5001",
  clientEventId: "c-1",
  type: "CHAT_MESSAGE",
  sender: { identity: "p-11", displayName: "김민수", role: "STUDENT" },
  occurredOffsetMs: 1_000,
  deliveredAt: "2026-07-30T09:00:01Z",
  payload: { content: "안녕하세요" },
};

const parse = (value: unknown) => parseSessionEvent(JSON.stringify(value));

describe("parseSessionEvent", () => {
  it("계약대로 온 봉투를 읽는다", () => {
    expect(parse(validEvent)).toEqual(validEvent);
  });

  it("clientEventId 가 없으면 null 로 둔다", () => {
    expect(parse({ ...validEvent, clientEventId: null })?.clientEventId).toBeNull();
  });

  /** 알 수 없는 프레임 하나로 수업 화면이 죽으면 안 된다. */
  it("JSON 이 아니면 null 을 준다", () => {
    expect(parseSessionEvent("not json")).toBeNull();
  });

  it.each([
    ["eventId 가 빈 값", { ...validEvent, eventId: "" }],
    ["발신자가 없음", { ...validEvent, sender: null }],
    ["발신자 identity 가 없음", { ...validEvent, sender: { displayName: "김민수" } }],
    ["발생 시각이 숫자가 아님", { ...validEvent, occurredOffsetMs: "1000" }],
  ])("형태가 어긋나면 null 을 준다: %s", (_label, malformed) => {
    expect(parse(malformed)).toBeNull();
  });

  /** 64~66 이 배포되는 사이 이 화면이 먼저 떠 있을 수 있다. 오류로 다루지 않고 조용히 버린다. */
  it("모르는 종류는 null 을 준다", () => {
    expect(parse({ ...validEvent, type: "HAND_RAISED" })).toBeNull();
  });

  it("역할이 INSTRUCTOR 가 아니면 학생으로 본다", () => {
    expect(parse({ ...validEvent, sender: { ...validEvent.sender, role: "???" } })?.sender.role).toBe(
      "STUDENT",
    );
  });
});

describe("chatContentOf", () => {
  it("payload.content 를 준다", () => {
    expect(chatContentOf(parse(validEvent)!)).toBe("안녕하세요");
  });

  it("본문이 비어 있으면 null 을 준다", () => {
    expect(chatContentOf(parse({ ...validEvent, payload: { content: "   " } })!)).toBeNull();
    expect(chatContentOf(parse({ ...validEvent, payload: {} })!)).toBeNull();
  });
});

describe("parseSessionEventRejection", () => {
  it("어느 전송이 실패했는지와 사유를 읽는다", () => {
    expect(
      parseSessionEventRejection(JSON.stringify({ clientEventId: "c-1", reason: "EMPTY_CONTENT" })),
    ).toEqual({ clientEventId: "c-1", reason: "EMPTY_CONTENT" });
  });

  /** clientEventId 가 없으면 어느 항목을 실패로 표시할지 알 수 없어 쓸 수 없다. */
  it("clientEventId 가 없으면 null 을 준다", () => {
    expect(parseSessionEventRejection(JSON.stringify({ reason: "EMPTY_CONTENT" }))).toBeNull();
  });

  it("사유가 없으면 기본값으로 채운다", () => {
    expect(parseSessionEventRejection(JSON.stringify({ clientEventId: "c-1" }))?.reason).toBe(
      "SEND_FAILED",
    );
  });
});
