import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { TimelineStatusBar } from "./TimelineStatusBar";

// `@testing-library/user-event` 는 설치돼 있지 않고 이 일감은 새 의존성을 추가하지 않는다.
// 키 입력은 컴포넌트가 직접 다는 onKeyDown 을 겨냥하므로 fireEvent 로 충분하다.
const segments = [
  { startSeconds: 0, endSeconds: 10, state: "GOOD" as const },
  { startSeconds: 10, endSeconds: 20, state: "CAMERA_OFF" as const },
  { startSeconds: 20, endSeconds: 30, state: null },
];

describe("TimelineStatusBar", () => {
  it("moves through segments with the arrow keys alone", () => {
    const onSelect = vi.fn();
    render(
      <TimelineStatusBar segments={segments} selectedIndex={0} onSelect={onSelect} label="상태 막대" />,
    );

    const buttons = screen.getAllByRole("button");
    buttons[0].focus();
    fireEvent.keyDown(buttons[0], { key: "ArrowRight" });

    expect(onSelect).toHaveBeenCalledWith(1);
  });

  it("jumps to the last segment with End", () => {
    const onSelect = vi.fn();
    render(
      <TimelineStatusBar segments={segments} selectedIndex={0} onSelect={onSelect} label="상태 막대" />,
    );

    const buttons = screen.getAllByRole("button");
    buttons[0].focus();
    fireEvent.keyDown(buttons[0], { key: "End" });

    expect(onSelect).toHaveBeenCalledWith(2);
  });

  it("keeps only the selected segment in the tab order", () => {
    render(
      <TimelineStatusBar segments={segments} selectedIndex={1} onSelect={vi.fn()} label="상태 막대" />,
    );

    const buttons = screen.getAllByRole("button");
    expect(buttons[0]).toHaveAttribute("tabindex", "-1");
    expect(buttons[1]).toHaveAttribute("tabindex", "0");
  });

  it("names every state in text, not colour alone", () => {
    render(
      <TimelineStatusBar segments={segments} selectedIndex={0} onSelect={vi.fn()} label="상태 막대" />,
    );

    // 색을 못 보는 사람도 상태를 읽을 수 있어야 한다(NFR-UX-005).
    expect(screen.getByRole("button", { name: /집중/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /카메라 꺼짐/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /기록 없음/ })).toBeInTheDocument();
  });

  it("announces the selected segment", () => {
    const { container } = render(
      <TimelineStatusBar segments={segments} selectedIndex={1} onSelect={vi.fn()} label="상태 막대" />,
    );

    const live = container.querySelector("[aria-live='polite']");
    expect(live?.textContent).toContain("카메라 꺼짐");
  });

  it("lets the card write its own segment label", () => {
    render(
      <TimelineStatusBar
        segments={[{ startSeconds: 0, endSeconds: 10, state: null }]}
        selectedIndex={0}
        onSelect={vi.fn()}
        label="흐트러짐 구간"
        renderLabel={(segment) => `집중 흐트러짐 ${segment.startSeconds}초부터`}
      />,
    );

    // 강사 카드는 학생 상태가 아니라 흐트러짐 구간을 담기 때문에 문구를 갈아끼울 수 있어야 한다.
    expect(screen.getByRole("button", { name: /집중 흐트러짐/ })).toBeInTheDocument();
  });
});
