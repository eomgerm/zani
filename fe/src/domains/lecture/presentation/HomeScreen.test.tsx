import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { HomeScreen } from "./HomeScreen";

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

vi.mock("@/domains/auth", () => ({
  useAuth: () => ({ accessToken: "access-token", member: { displayName: "지훈" } }),
}));

const renderHome = () => render(<HomeScreen requestSessionList={vi.fn().mockResolvedValue([])} />);

const type = (value: string) => {
  fireEvent.change(screen.getByLabelText("초대 코드 또는 초대 링크"), { target: { value } });
};

beforeEach(() => {
  vi.clearAllMocks();
});

describe("HomeScreen 강의실 참여하기", () => {
  /** 강사가 공유하는 건 링크다. 링크에서 코드만 골라내는 일을 사용자에게 맡기면 안 된다. */
  it("초대 링크를 붙여 넣어도 입장 전 점검으로 넘어간다", () => {
    renderHome();

    type("http://localhost:3000/prejoin/GPH7GQ5Q");
    fireEvent.click(screen.getByRole("button", { name: "참여하기" }));

    expect(pushMock).toHaveBeenCalledWith("/prejoin/GPH7GQ5Q");
  });

  it("코드만 넣어도 되고 소문자·하이픈을 허용한다", () => {
    renderHome();

    type("gph7-gq5q");
    fireEvent.click(screen.getByRole("button", { name: "참여하기" }));

    expect(pushMock).toHaveBeenCalledWith("/prejoin/GPH7GQ5Q");
  });

  it("Enter 로도 입장할 수 있다", () => {
    renderHome();

    const input = screen.getByLabelText("초대 코드 또는 초대 링크");
    fireEvent.change(input, { target: { value: "GPH7GQ5Q" } });
    fireEvent.keyDown(input, { key: "Enter" });

    expect(pushMock).toHaveBeenCalledWith("/prejoin/GPH7GQ5Q");
  });

  /** 형식 오류는 입력한 화면에서 알려준다. 서버까지 보내면 화면을 옮긴 뒤에 실패해 어디를 고칠지 알기 어렵다. */
  it("형식이 어긋나면 화면을 옮기지 않고 여기서 알려준다", () => {
    renderHome();

    type("ZANI-8KQ");
    fireEvent.click(screen.getByRole("button", { name: "참여하기" }));

    expect(screen.getByRole("alert")).toHaveTextContent("초대 코드 또는 초대 링크를 확인해 주세요");
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("아무것도 입력하지 않고 누르면 화면을 옮기지 않는다", () => {
    renderHome();

    fireEvent.click(screen.getByRole("button", { name: "참여하기" }));

    expect(screen.getByRole("alert")).toBeVisible();
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("다시 입력하면 이전 오류를 지운다", () => {
    renderHome();

    type("ZANI-8KQ");
    fireEvent.click(screen.getByRole("button", { name: "참여하기" }));
    expect(screen.getByRole("alert")).toBeVisible();

    type("GPH7GQ5Q");

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
