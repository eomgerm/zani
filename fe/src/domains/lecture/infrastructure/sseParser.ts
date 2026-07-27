/**
 * 증분 SSE(text/event-stream) 파서.
 *
 * EventSource 는 Authorization 헤더를 붙일 수 없어(백엔드는 Bearer 필수) fetch 스트림을
 * 직접 파싱한다. 청크가 이벤트 중간 어디에서 갈라져도 라인 버퍼로 재조립하고,
 * 콜론으로 시작하는 주석 라인(서버 ping)은 이벤트와 분리해 전달한다.
 */

export type SseEvent = {
  readonly name: string;
  readonly data: string;
};

export type SseParserHandlers = {
  onEvent: (event: SseEvent) => void;
  onComment?: (comment: string) => void;
};

export type SseParser = {
  /** 스트림에서 읽은 텍스트 조각을 밀어 넣는다. 완성된 이벤트마다 onEvent 가 호출된다. */
  push(text: string): void;
};

/** 필드 값 규칙: 콜론 뒤 공백 한 칸은 값에 포함하지 않는다. */
const fieldValue = (raw: string): string => (raw.startsWith(" ") ? raw.slice(1) : raw);

export function createSseParser(handlers: SseParserHandlers): SseParser {
  let buffer = "";
  let eventName: string | null = null;
  let dataLines: string[] = [];

  const dispatch = () => {
    // data 라인이 하나도 없는 블록(event 이름만 등)은 이벤트가 아니다.
    if (dataLines.length > 0) {
      handlers.onEvent({ name: eventName ?? "message", data: dataLines.join("\n") });
    }
    eventName = null;
    dataLines = [];
  };

  const handleLine = (line: string) => {
    if (line === "") {
      dispatch();
      return;
    }
    if (line.startsWith(":")) {
      handlers.onComment?.(fieldValue(line.slice(1)));
      return;
    }

    const colonIndex = line.indexOf(":");
    const field = colonIndex === -1 ? line : line.slice(0, colonIndex);
    const value = colonIndex === -1 ? "" : fieldValue(line.slice(colonIndex + 1));

    if (field === "event") {
      eventName = value;
    } else if (field === "data") {
      dataLines.push(value);
    }
    // id·retry 등 나머지 필드는 사용하지 않는다.
  };

  return {
    push(text: string): void {
      buffer += text;
      let newlineIndex = buffer.indexOf("\n");
      while (newlineIndex !== -1) {
        let line = buffer.slice(0, newlineIndex);
        buffer = buffer.slice(newlineIndex + 1);
        // CRLF 개행: \r 이 청크 끝에 걸려도 \n 을 만날 때 함께 제거된다.
        if (line.endsWith("\r")) {
          line = line.slice(0, -1);
        }
        handleLine(line);
        newlineIndex = buffer.indexOf("\n");
      }
    },
  };
}
