import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/domains/auth", () => ({ useAuth: () => ({ accessToken: "token" }) }));

import { ReportAssistantError, type ReportAnswer } from "../infrastructure/reportAssistantApi";
import { useReportAssistant } from "./useReportAssistant";

const ANSWER: ReportAnswer = {
  answer: "충돌이 나면 빈 자리를 찾아 저장하는 방식입니다.",
  citations: [{ offsetMs: 98_000, quote: "체이닝과 개방주소법이 있습니다." }],
  grounded: true,
};

const ANCHOR = { anchorStartMs: 95_000, selectedText: "개방주소법" };

describe("useReportAssistant", () => {
  it("드래그하면 입력 없이 곧바로 그 부분을 물어본다", async () => {
    const ask = vi.fn().mockResolvedValue(ANSWER);
    const { result } = renderHook(() => useReportAssistant({ sessionId: "s1", ask }));

    act(() => result.current.askAbout(ANCHOR));

    await waitFor(() => expect(result.current.status).toBe("idle"));

    // 선택 텍스트만 덩그러니 보내지 않는다. 명사구 하나로는 정의를 묻는지 이 수업에서의 설명을
    // 묻는지 갈리므로, 화면의 동작("이 부분 설명해줘")을 문장으로 적어 보낸다.
    const [, question] = ask.mock.calls[0];
    expect(question.question).toContain("개방주소법");
    expect(question.selectedText).toBe("개방주소법");
    expect(question.anchorStartMs).toBe(95_000);
    // 첫 질문에는 이력이 없다. 방금 만든 사용자 말풍선을 이력에도 담으면 두 번 보내진다.
    expect(question.history).toEqual([]);
  });

  it("답이 오면 질문과 답변이 순서대로 쌓인다", async () => {
    const ask = vi.fn().mockResolvedValue(ANSWER);
    const { result } = renderHook(() => useReportAssistant({ sessionId: "s1", ask }));

    act(() => result.current.askAbout(ANCHOR));
    await waitFor(() => expect(result.current.messages).toHaveLength(2));

    expect(result.current.messages[0].role).toBe("user");
    expect(result.current.messages[1].role).toBe("assistant");
    expect(result.current.messages[1].citations).toHaveLength(1);
  });

  it("후속 질문은 앞선 대화를 이력으로 함께 보낸다", async () => {
    const ask = vi.fn().mockResolvedValue(ANSWER);
    const { result } = renderHook(() => useReportAssistant({ sessionId: "s1", ask }));

    act(() => result.current.askAbout(ANCHOR));
    await waitFor(() => expect(result.current.messages).toHaveLength(2));

    act(() => result.current.send("그럼 체이닝은?"));
    await waitFor(() => expect(ask).toHaveBeenCalledTimes(2));

    // 서버가 대화를 저장하지 않으므로 이어지려면 화면이 들고 보내야 한다.
    const [, followUp] = ask.mock.calls[1];
    expect(followUp.question).toBe("그럼 체이닝은?");
    expect(followUp.history).toHaveLength(2);
    expect(followUp.anchorStartMs).toBe(95_000);
  });

  it("새로 드래그하면 대화를 새로 시작한다", async () => {
    const ask = vi.fn().mockResolvedValue(ANSWER);
    const { result } = renderHook(() => useReportAssistant({ sessionId: "s1", ask }));

    act(() => result.current.askAbout(ANCHOR));
    await waitFor(() => expect(result.current.messages).toHaveLength(2));

    act(() => result.current.askAbout({ anchorStartMs: 60_000, selectedText: "해시 함수" }));
    await waitFor(() => expect(ask).toHaveBeenCalledTimes(2));

    // 이어 붙이면 앞 구간의 문답이 카드에 쌓여 지금 무엇을 읽고 있는지가 흐려진다.
    const [, second] = ask.mock.calls[1];
    expect(second.history).toEqual([]);
    expect(second.anchorStartMs).toBe(60_000);
    await waitFor(() => expect(result.current.messages).toHaveLength(2));
  });

  it("빈도 제한은 다시 드래그하라고 안내한다", async () => {
    const ask = vi.fn().mockRejectedValue(new ReportAssistantError("429", 429, "REPORT_ASSISTANT_001"));
    const { result } = renderHook(() => useReportAssistant({ sessionId: "s1", ask }));

    act(() => result.current.askAbout(ANCHOR));

    await waitFor(() => expect(result.current.failure).toContain("너무 빨라요"));
    // 보낸 질문은 남긴다. 지우면 무엇이 실패했는지 알 수 없다.
    expect(result.current.messages).toHaveLength(1);
    expect(result.current.status).toBe("idle");
  });

  it("닫으면 대화를 비운다", async () => {
    const ask = vi.fn().mockResolvedValue(ANSWER);
    const { result } = renderHook(() => useReportAssistant({ sessionId: "s1", ask }));

    act(() => result.current.askAbout(ANCHOR));
    await waitFor(() => expect(result.current.messages).toHaveLength(2));

    act(() => result.current.close());

    expect(result.current.anchor).toBeNull();
    expect(result.current.messages).toHaveLength(0);
  });
});
