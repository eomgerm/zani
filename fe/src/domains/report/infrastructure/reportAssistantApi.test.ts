import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  ASSISTANT_QUESTION_TOO_LARGE,
  ASSISTANT_RATE_LIMITED,
  askReportQuestion,
  ReportAssistantError,
} from "./reportAssistantApi";

const jsonResponse = (body: unknown, status = 200) =>
  ({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  }) as Response;

const answerEnvelope = {
  isSuccess: true,
  data: {
    answer: "충돌이 나면 빈 자리를 찾아 저장하는 방식입니다.",
    citations: [{ offsetMs: 98000, quote: "체이닝과 개방주소법이 있습니다." }],
    grounded: true,
  },
};

const fetchMock = vi.fn();

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("askReportQuestion", () => {
  it("posts the question with the anchor and the bearer token", async () => {
    fetchMock.mockResolvedValue(jsonResponse(answerEnvelope));

    const result = await askReportQuestion(
      "123",
      { question: "개방주소법이 뭐야?", selectedText: "개방주소법", anchorStartMs: 95000 },
      "test-access-token",
    );

    expect(result.answer).toBe("충돌이 나면 빈 자리를 찾아 저장하는 방식입니다.");
    expect(result.citations).toEqual([
      { offsetMs: 98000, quote: "체이닝과 개방주소법이 있습니다." },
    ]);
    expect(result.grounded).toBe(true);

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toContain("/api/v1/sessions/123/reports/assistant/messages");
    expect(init.method).toBe("POST");
    expect(init.headers.Authorization).toBe("Bearer test-access-token");
    expect(JSON.parse(init.body)).toEqual({
      question: "개방주소법이 뭐야?",
      selectedText: "개방주소법",
      anchorStartMs: 95000,
    });
  });

  it("omits the anchor when the drag was outside the timeline", async () => {
    fetchMock.mockResolvedValue(jsonResponse(answerEnvelope));

    await askReportQuestion("123", { question: "이 수업 요약해줘", anchorStartMs: null }, "token");

    // 서버가 계약 밖 필드에 400 을 내므로 값이 없으면 키 자체를 뺀다.
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ question: "이 수업 요약해줘" });
  });

  it("keeps zero as a real anchor", async () => {
    fetchMock.mockResolvedValue(jsonResponse(answerEnvelope));

    await askReportQuestion("123", { question: "첫 구간 설명해줘", anchorStartMs: 0 }, "token");

    // 0ms 는 첫 구간의 시작이다. falsy 로 걸러 내면 수업 도입부를 드래그한 사람이 앵커를 잃는다.
    expect(JSON.parse(fetchMock.mock.calls[0][1].body).anchorStartMs).toBe(0);
  });

  it("surfaces the rate limit code so the caller can back off", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ isSuccess: false, code: ASSISTANT_RATE_LIMITED }, 429));

    await expect(askReportQuestion("123", { question: "뭐야?" }, "token")).rejects.toMatchObject({
      status: 429,
      code: ASSISTANT_RATE_LIMITED,
    });
  });

  it("distinguishes a too-large question from a transient failure", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ isSuccess: false, code: ASSISTANT_QUESTION_TOO_LARGE }, 400),
    );

    // 다시 눌러도 같은 실패다. 화면이 "다시 시도" 대신 질문을 줄이라고 말해야 한다.
    await expect(askReportQuestion("123", { question: "뭐야?" }, "token")).rejects.toMatchObject({
      status: 400,
      code: ASSISTANT_QUESTION_TOO_LARGE,
    });
  });

  it("reports status 0 when no response arrived", async () => {
    fetchMock.mockRejectedValue(new TypeError("network down"));

    await expect(askReportQuestion("123", { question: "뭐야?" }, "token")).rejects.toMatchObject({
      status: 0,
    });
  });

  it("drops a broken citation but keeps the answer", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        isSuccess: true,
        data: {
          answer: "답변입니다.",
          citations: [{ offsetMs: "이상한 값", quote: "x" }, { offsetMs: 1000, quote: "정상" }, null],
          grounded: true,
        },
      }),
    );

    const result = await askReportQuestion("123", { question: "뭐야?" }, "token");

    // 이동 버튼 하나가 없는 편이 답변을 통째로 잃는 것보다 낫다.
    expect(result.answer).toBe("답변입니다.");
    expect(result.citations).toEqual([{ offsetMs: 1000, quote: "정상" }]);
  });

  it("treats a missing grounded flag as not grounded", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ isSuccess: true, data: { answer: "답변입니다.", citations: [] } }),
    );

    const result = await askReportQuestion("123", { question: "뭐야?" }, "token");

    // 지어낸 답을 근거 있는 답처럼 그리는 쪽이 더 나쁘다.
    expect(result.grounded).toBe(false);
  });

  it("rejects an envelope without answer text", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ isSuccess: true, data: { answer: "" } }));

    await expect(askReportQuestion("123", { question: "뭐야?" }, "token")).rejects.toBeInstanceOf(
      ReportAssistantError,
    );
  });
});
