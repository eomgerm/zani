import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { SelectionAskButton } from "./SelectionAskButton";

const SELECTION = { text: "개방주소법", anchorStartMs: 95_000, x: 200, y: 300 };

describe("SelectionAskButton", () => {
  it("선택이 없으면 아무것도 그리지 않는다", () => {
    render(<SelectionAskButton selection={null} onAsk={vi.fn()} />);

    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("누르면 드래그한 내용과 앵커를 그대로 넘긴다", () => {
    const onAsk = vi.fn();
    render(<SelectionAskButton selection={SELECTION} onAsk={onAsk} />);

    fireEvent.click(screen.getByRole("button", { name: "AI에게 묻기" }));

    // 이 값이 곧 질문이 된다. 무엇을 물을지 따로 입력받지 않는다.
    expect(onAsk).toHaveBeenCalledWith(SELECTION);
  });

  it("누를 때 선택이 풀리지 않도록 mousedown 기본 동작을 막는다", () => {
    render(<SelectionAskButton selection={SELECTION} onAsk={vi.fn()} />);

    const event = new MouseEvent("mousedown", { bubbles: true, cancelable: true });
    screen.getByRole("button").dispatchEvent(event);

    // 막지 않으면 버튼을 누르는 순간 선택이 풀려, onClick 이 도달할 때는 넘길 선택이 없다.
    expect(event.defaultPrevented).toBe(true);
  });
});
