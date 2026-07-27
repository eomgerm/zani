import { describe, expect, it, vi } from "vitest";

import { createSseParser, type SseEvent } from "./sseParser";

const collect = () => {
  const events: SseEvent[] = [];
  const comments: string[] = [];
  const parser = createSseParser({
    onEvent: (event) => events.push(event),
    onComment: (comment) => comments.push(comment),
  });
  return { parser, events, comments };
};

describe("createSseParser", () => {
  it("event·data 라인으로 이뤄진 이벤트를 파싱한다", () => {
    const { parser, events } = collect();

    parser.push('event:AUDIO_CLIP_REQUESTED\ndata:{"clipId":"1"}\n\n');

    expect(events).toEqual([{ name: "AUDIO_CLIP_REQUESTED", data: '{"clipId":"1"}' }]);
  });

  it("필드 이름 뒤의 선택적 공백 한 칸을 허용한다", () => {
    const { parser, events } = collect();

    parser.push("event: PING_LIKE\ndata: hello\n\n");

    expect(events).toEqual([{ name: "PING_LIKE", data: "hello" }]);
  });

  it("이벤트가 청크 경계 어디에서 갈라져도 재조립한다", () => {
    const { parser, events } = collect();
    const raw = 'event:AUDIO_CLIP_REQUESTED\ndata:{"clipId":"42"}\n\n';

    // 한 글자씩 밀어 넣어 어떤 경계 분할에도 견디는지 확인한다.
    for (const char of raw) {
      parser.push(char);
    }

    expect(events).toEqual([{ name: "AUDIO_CLIP_REQUESTED", data: '{"clipId":"42"}' }]);
  });

  it("한 청크에 담긴 연속 이벤트를 모두 내보낸다", () => {
    const { parser, events } = collect();

    parser.push("event:A\ndata:1\n\nevent:B\ndata:2\n\n");

    expect(events).toEqual([
      { name: "A", data: "1" },
      { name: "B", data: "2" },
    ]);
  });

  it("여러 data 라인은 개행으로 이어 붙인다", () => {
    const { parser, events } = collect();

    parser.push("data:first\ndata:second\n\n");

    expect(events).toEqual([{ name: "message", data: "first\nsecond" }]);
  });

  it("event 이름이 없으면 기본 이름 message 를 쓴다", () => {
    const { parser, events } = collect();

    parser.push("data:only\n\n");

    expect(events[0]?.name).toBe("message");
  });

  it("콜론으로 시작하는 라인은 주석(ping)으로 전달하고 이벤트를 만들지 않는다", () => {
    const { parser, events, comments } = collect();

    parser.push(":ping\n\n:ping\n\n");

    expect(events).toEqual([]);
    expect(comments).toEqual(["ping", "ping"]);
  });

  it("CRLF 개행도 처리한다", () => {
    const { parser, events } = collect();

    parser.push("event:A\r\ndata:1\r\n\r\n");

    expect(events).toEqual([{ name: "A", data: "1" }]);
  });

  it("빈 줄 없이 끝난 미완성 이벤트는 내보내지 않는다", () => {
    const { parser, events } = collect();

    parser.push("event:A\ndata:1\n"); // 종결 빈 줄 없음

    expect(events).toEqual([]);
  });

  it("onComment 가 없어도 주석에서 죽지 않는다", () => {
    const events: SseEvent[] = [];
    const parser = createSseParser({ onEvent: (event) => events.push(event) });

    expect(() => parser.push(":ping\n\n")).not.toThrow();
    expect(events).toEqual([]);
  });

  it("data 가 전혀 없는 블록은 무시한다", () => {
    const { parser, events } = collect();
    const onEvent = vi.fn();

    parser.push("event:EMPTY\n\n");

    expect(events).toEqual([]);
    expect(onEvent).not.toHaveBeenCalled();
  });
});
