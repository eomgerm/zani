import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  CoachTipCard,
  COACH_TIP_PIP_PLACEMENT,
  COACH_TIP_SHARE_PLACEMENT,
  COACH_TIP_STAGE_PLACEMENT,
} from "./CoachTipCard";
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

  it("takes the placement the surface asks for", () => {
    render(<CoachTipCard tip={tip} className={COACH_TIP_PIP_PLACEMENT} onDismiss={vi.fn()} />);

    const card = screen.getByTestId("coach-tip-card");
    expect(card.className).toContain(COACH_TIP_PIP_PLACEMENT);
    expect(card.className).not.toContain(COACH_TIP_STAGE_PLACEMENT);
  });

  /** `z-[N]` 에서 N 을 읽는다. 없으면 0(쌓임 지정 없음). */
  const zLayerOf = (placement: string) => Number(/z-\[(\d+)]/.exec(placement)?.[1] ?? 0);

  // 공유 오버레이는 z-[6] 이고, 그 안의 미니 로스터는 z-[8] 이다(RoomScreen). 오버레이가 쌓임
  // 맥락을 만들어 그 안쪽까지 통째로 6층에 얹히지만, 값을 그대로 넘어서게 두어 로스터 z 가 바뀌어도
  // 카드가 다시 가려지지 않게 한다. 이 카드가 오버레이 뒤에 깔린 것이 299 가 고친 결함이다.
  it("stacks the share placement above the screen share overlay", () => {
    expect(zLayerOf(COACH_TIP_SHARE_PLACEMENT)).toBeGreaterThan(8);
    expect(zLayerOf(COACH_TIP_SHARE_PLACEMENT)).toBeGreaterThan(zLayerOf(COACH_TIP_STAGE_PLACEMENT));
  });

  // 미니 창은 기본 260px 라 고정 너비(스테이지 290px)를 그대로 쓰면 카드가 잘린다.
  it("lets the card fill the width inside the mini window", () => {
    expect(COACH_TIP_PIP_PLACEMENT).not.toMatch(/\bw-\[/);
    expect(COACH_TIP_PIP_PLACEMENT).toContain("inset-x-");
  });

  // 서버가 익명 집계로 만든 문구다. 개별 학생은 어디에도 드러나지 않는다.
  it("shows no student identity or individual judgement", () => {
    render(<CoachTipCard tip={tip} onDismiss={vi.fn()} />);

    const text = screen.getByTestId("coach-tip-card").textContent ?? "";
    expect(text).not.toMatch(/@|이메일|아이디|님이|개별/);
  });
});
