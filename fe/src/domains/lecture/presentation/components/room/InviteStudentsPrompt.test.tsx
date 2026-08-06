import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { InviteStudentsPrompt } from "./InviteStudentsPrompt";

const LINK = "http://localhost:3000/prejoin/ABCD-1234";

const stubClipboard = (writeText = vi.fn(async () => {})) => {
  Object.defineProperty(navigator, "clipboard", {
    configurable: true,
    value: { writeText },
  });
  return writeText;
};

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  Reflect.deleteProperty(navigator, "clipboard");
});

describe("InviteStudentsPrompt", () => {
  it("링크를 클립보드에 넣는다", async () => {
    const writeText = stubClipboard();
    render(<InviteStudentsPrompt inviteUrl={LINK} onClose={vi.fn()} />);

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "복사하기" }));
    });

    expect(writeText).toHaveBeenCalledWith(LINK);
  });

  /**
   * 링크를 여러 곳에 붙여 넣는 중이라면 다시 눌러야 한다. "복사됨" 인 채로 남아 있으면 이미 끝난
   * 버튼처럼 보여, 누를 수 있다는 것을 알 방법이 없다.
   */
  it("복사됨을 잠깐 보여준 뒤 다시 누를 수 있는 문구로 돌아온다", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const writeText = stubClipboard();
    render(<InviteStudentsPrompt inviteUrl={LINK} onClose={vi.fn()} />);

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "복사하기" }));
    });
    expect(screen.getByRole("button", { name: "복사됨" })).toBeVisible();

    await act(async () => {
      vi.advanceTimersByTime(2_000);
    });

    const again = screen.getByRole("button", { name: "복사하기" });
    await act(async () => {
      fireEvent.click(again);
    });

    expect(writeText).toHaveBeenCalledTimes(2);
  });

  /** 링크를 아직 못 구한 상태다. 빈 값을 복사시키면 아무도 못 들어오는 링크를 뿌리게 된다. */
  it("링크가 없으면 복사할 수 없다", () => {
    render(<InviteStudentsPrompt inviteUrl={null} onClose={vi.fn()} />);

    expect(screen.getByRole("button", { name: "복사하기" })).toBeDisabled();
  });

  it("닫기는 x 를 눌러야만 일어난다", () => {
    const onClose = vi.fn();
    render(<InviteStudentsPrompt inviteUrl={LINK} onClose={onClose} />);

    fireEvent.click(screen.getByRole("dialog"));
    expect(onClose).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "초대 안내 닫기" }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
