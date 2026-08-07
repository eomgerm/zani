import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { ReportAssistantPanel } from "./ReportAssistantPanel";
import type { AssistantMessage } from "./useReportAssistant";

const ANSWER: AssistantMessage = {
  id: "a0",
  role: "assistant",
  content: "충돌이 나면 빈 자리를 찾아 저장하는 방식입니다.",
  citations: [{ offsetMs: 98_000, quote: "체이닝과 개방주소법이 있습니다." }],
  grounded: true,
};

const panel = (overrides: Partial<Parameters<typeof ReportAssistantPanel>[0]> = {}) =>
  render(
    <ReportAssistantPanel
      messages={[]}
      status="idle"
      failure={null}
      onSend={vi.fn()}
      onClose={vi.fn()}
      {...overrides}
    />,
  );

describe("ReportAssistantPanel", () => {
  it("인용을 누르면 밀리초가 아니라 초로 이동을 요청한다", () => {
    const onSeek = vi.fn();
    panel({ messages: [ANSWER], onSeek });

    fireEvent.click(screen.getByRole("button", { name: /01:38 구간 재생/ }));

    // jumpToClip 의 계약은 초다. ms 를 그대로 넘기면 98,000초(27시간) 지점으로 보낸다.
    expect(onSeek).toHaveBeenCalledWith(98);
  });

  it("이동 배선이 없으면 인용을 버튼으로 내지 않는다", () => {
    panel({ messages: [ANSWER] });

    // 누를 곳 없는 버튼을 그리지 않는다 — 구간 시각·전사 행과 같은 약속이다.
    expect(screen.queryByRole("button", { name: /구간 재생/ })).not.toBeInTheDocument();
    expect(screen.getByText(/체이닝과 개방주소법이 있습니다/)).toBeInTheDocument();
  });

  it("입력칸은 후속 질문 전용이다", () => {
    panel();

    // 첫 질문은 드래그가 이미 보냈다. 안내 문구가 그것을 말해야 한다.
    expect(screen.getByPlaceholderText("추가로 질문하기...")).toBeInTheDocument();
  });

  it("빈 질문은 보내지 않는다", () => {
    const onSend = vi.fn();
    panel({ onSend });

    fireEvent.click(screen.getByRole("button", { name: "전송" }));

    expect(onSend).not.toHaveBeenCalled();
  });

  it("질문을 보내면 입력칸을 비운다", () => {
    const onSend = vi.fn();
    panel({ onSend });

    const input = screen.getByLabelText("추가 질문 입력");
    fireEvent.change(input, { target: { value: "그럼 체이닝은?" } });
    fireEvent.click(screen.getByRole("button", { name: "전송" }));

    expect(onSend).toHaveBeenCalledWith("그럼 체이닝은?");
    expect(input).toHaveValue("");
  });

  it("답을 기다리는 동안에는 다시 보내지 못한다", () => {
    const onSend = vi.fn();
    panel({ status: "asking", onSend });

    const input = screen.getByLabelText("추가 질문 입력");
    fireEvent.change(input, { target: { value: "또 물어보기" } });
    fireEvent.click(screen.getByRole("button", { name: "전송" }));

    // 연타하면 GMS 호출이 그만큼 나간다. 크레딧이 걸린 자리라 화면에서도 막는다.
    expect(onSend).not.toHaveBeenCalled();
    expect(screen.getByText(/답변을 만들고 있어요/)).toBeInTheDocument();
  });

  it("실패 문구를 그대로 보여준다", () => {
    panel({ failure: "질문이 너무 빨라요. 잠시 후 다시 드래그해 주세요." });

    expect(screen.getByText(/질문이 너무 빨라요/)).toBeInTheDocument();
  });
});
