import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { CoachTipCard } from "./CoachTipCard";
import type { CoachTip } from "../../../infrastructure/coachPollApi";

const tip: CoachTip = {
  tipType: "CONFUSED",
  title: "추가 설명이 필요해요",
  message: "전체 학생의 30%가 현재 내용을 헷갈려 하고 있어요. 클로저를 다른 예시로 다시 설명해 주세요.",
  targetConcept: "클로저",
};

afterEach(() => {
  cleanup();
});

describe("CoachTipCard", () => {
  it("shows the title and the finished message as the server wrote them", () => {
    render(<CoachTipCard tip={tip} onDismiss={vi.fn()} />);

    expect(screen.getByText(tip.title)).toBeVisible();
    expect(screen.getByText(tip.message)).toBeVisible();
  });

  it.each([
    ["확인", "확인"],
    ["닫기", "팁 닫기"],
  ])("closes on %s", (_label, name) => {
    const onDismiss = vi.fn();
    render(<CoachTipCard tip={tip} onDismiss={onDismiss} />);

    fireEvent.click(screen.getByRole("button", { name }));

    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  // 유형은 검증·로깅용이고 문구는 서버가 완성한다. 화면을 유형별로 나누지 않는다(86 결정).
  it("renders the same shape whatever the tip type is", () => {
    const { container } = render(<CoachTipCard tip={tip} onDismiss={vi.fn()} />);
    const confused = container.innerHTML;
    cleanup();

    const { container: other } = render(
      <CoachTipCard tip={{ ...tip, tipType: "NON_RESPONSE" }} onDismiss={vi.fn()} />,
    );

    expect(other.innerHTML).toBe(confused);
  });

  // 무응답·자리비움 팁에는 핵심 개념이 없다. 자리를 두면 유형마다 카드가 달라 보인다.
  it("does not break when the tip carries no target concept", () => {
    render(<CoachTipCard tip={{ ...tip, targetConcept: null }} onDismiss={vi.fn()} />);

    expect(screen.getByText(tip.message)).toBeVisible();
  });

  // 서버가 익명 집계로 만든 문구다. 개별 학생은 어디에도 드러나지 않는다.
  it("shows no student identity or individual judgement", () => {
    render(<CoachTipCard tip={tip} onDismiss={vi.fn()} />);

    const text = screen.getByTestId("coach-tip-card").textContent ?? "";
    expect(text).not.toMatch(/@|이메일|아이디|님이|개별/);
  });
});
