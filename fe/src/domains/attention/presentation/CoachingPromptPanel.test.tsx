import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { CoachingPromptPanel } from "./CoachingPromptPanel";

afterEach(() => {
  cleanup();
});

type Choice = "OK" | "CONFUSED" | "MISSED";

const OPTIONS: { value: Choice; label: string; emoji: string; toneClassName: string }[] = [
  { value: "OK", label: "이해했어요", emoji: "👍", toneClassName: "bg-primary-mint" },
  { value: "CONFUSED", label: "헷갈려요", emoji: "🤔", toneClassName: "bg-warn-soft" },
  { value: "MISSED", label: "놓쳤어요", emoji: "😅", toneClassName: "bg-primary-softer" },
];

describe("CoachingPromptPanel", () => {
  it("renders the title, body, all options, and a rounded-second countdown", () => {
    render(
      <CoachingPromptPanel
        title="잠깐 확인할게요 ✋"
        body="방금 설명한 내용, 지금 어떤가요?"
        options={OPTIONS}
        remainingMs={12_400}
        durationMs={30_000}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.getByText("잠깐 확인할게요 ✋")).toBeInTheDocument();
    expect(screen.getByText(/방금 설명한 내용/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /이해했어요/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /헷갈려요/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /놓쳤어요/ })).toBeInTheDocument();
    // 12.4초 남았으면 올림해서 13으로 보여준다.
    expect(screen.getByText("13")).toBeInTheDocument();
  });

  it("calls onSelect with the chosen option's value", () => {
    const onSelect = vi.fn();
    render(
      <CoachingPromptPanel
        title="title"
        body="body"
        options={OPTIONS}
        remainingMs={30_000}
        durationMs={30_000}
        onSelect={onSelect}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /헷갈려요/ }));

    expect(onSelect).toHaveBeenCalledWith("CONFUSED");
    expect(onSelect).toHaveBeenCalledTimes(1);
  });

  it("moves focus to the first option on mount so keyboard/screen-reader users notice it", () => {
    render(
      <CoachingPromptPanel
        title="title"
        body="body"
        options={OPTIONS}
        remainingMs={30_000}
        durationMs={30_000}
        onSelect={vi.fn()}
      />,
    );

    expect(document.activeElement).toBe(screen.getByRole("button", { name: /이해했어요/ }));
  });

  it("supports a single-option panel (detail-guidance / camera-check reuse)", () => {
    const onSelect = vi.fn();
    render(
      <CoachingPromptPanel
        title="자세를 확인해주세요"
        body="카메라에 얼굴이 나오도록 조정해주세요."
        options={[{ value: "ACK", label: "확인", toneClassName: "bg-primary-mint" }]}
        remainingMs={30_000}
        durationMs={30_000}
        onSelect={onSelect}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "확인" }));
    expect(onSelect).toHaveBeenCalledWith("ACK");
  });
});
