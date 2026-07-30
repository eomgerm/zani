import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { JoinSessionRequestError } from "@/domains/lecture/infrastructure/joinSessionApi";
import { HomeScreen } from "./HomeScreen";

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

vi.mock("@/domains/auth", () => ({
  useAuth: () => ({ accessToken: "access-token", member: { displayName: "지훈" } }),
}));

const joined = {
  sessionId: "100",
  inviteCode: "GPH7GQ5Q",
  status: "LIVE",
  role: "STUDENT",
};

/** 기본은 입장이 성공하는 서버. 거절 흐름을 보는 테스트만 따로 넘긴다. */
const renderHome = (joinSession = vi.fn().mockResolvedValue(joined)) => {
  render(
    <HomeScreen requestSessionList={vi.fn().mockResolvedValue([])} joinSession={joinSession} />,
  );
  return joinSession;
};

const type = (value: string) => {
  fireEvent.change(screen.getByLabelText("초대 코드 또는 초대 링크"), { target: { value } });
};

beforeEach(() => {
  vi.clearAllMocks();
});

describe("HomeScreen 강의실 참여하기", () => {
  /** 강사가 공유하는 건 링크다. 링크에서 코드만 골라내는 일을 사용자에게 맡기면 안 된다. */
  it("초대 링크를 붙여 넣어도 입장 전 점검으로 넘어간다", async () => {
    renderHome();

    type("http://localhost:3000/prejoin/GPH7GQ5Q");
    fireEvent.click(screen.getByRole("button", { name: "참여하기" }));

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/prejoin/GPH7GQ5Q"));
  });

  it("코드만 넣어도 되고 소문자·하이픈을 허용한다", async () => {
    const joinSession = renderHome();

    type("gph7-gq5q");
    fireEvent.click(screen.getByRole("button", { name: "참여하기" }));

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/prejoin/GPH7GQ5Q"));
    // 서버에는 정규화한 코드가 간다.
    expect(joinSession).toHaveBeenCalledWith("GPH7GQ5Q", "access-token");
  });

  it("Enter 로도 입장할 수 있다", async () => {
    renderHome();

    const input = screen.getByLabelText("초대 코드 또는 초대 링크");
    fireEvent.change(input, { target: { value: "GPH7GQ5Q" } });
    fireEvent.keyDown(input, { key: "Enter" });

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/prejoin/GPH7GQ5Q"));
  });

  /**
   * 이 검증이 홈에 있어야 하는 이유. 서버 확인을 점검 화면까지 미루면 학생이 카메라·마이크를 다 맞춘 뒤에야
   * 수업이 시작되지 않았다는 걸 알게 된다.
   */
  it("시작 전 수업이면 점검 화면으로 넘기지 않고 여기서 알려준다", async () => {
    renderHome(
      vi.fn().mockRejectedValue(new JoinSessionRequestError("not started", 409, "SESSION_APP_007")),
    );

    type("GPH7GQ5Q");
    fireEvent.click(screen.getByRole("button", { name: "참여하기" }));

    await waitFor(() =>
      expect(screen.getByTestId("home-join-error")).toHaveTextContent(
        "강사가 아직 수업을 시작하지 않았어요",
      ),
    );
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("정원이 찼으면 정원 안내를 보여준다", async () => {
    renderHome(
      vi.fn().mockRejectedValue(new JoinSessionRequestError("full", 409, "SESSION_APP_009")),
    );

    type("GPH7GQ5Q");
    fireEvent.click(screen.getByRole("button", { name: "참여하기" }));

    await waitFor(() =>
      expect(screen.getByTestId("home-join-error")).toHaveTextContent("정원이 가득 찼어요"),
    );
  });

  it("이미 끝난 수업이면 끝났다고 알려준다", async () => {
    renderHome(
      vi.fn().mockRejectedValue(new JoinSessionRequestError("ended", 409, "SESSION_APP_008")),
    );

    type("GPH7GQ5Q");
    fireEvent.click(screen.getByRole("button", { name: "참여하기" }));

    await waitFor(() =>
      expect(screen.getByTestId("home-join-error")).toHaveTextContent("이미 끝난 수업이에요"),
    );
  });

  /** 거절된 뒤에도 다시 시도할 수 있어야 한다. 버튼이 확인 중 상태로 굳으면 코드를 고쳐도 넘어갈 수 없다. */
  it("거절된 뒤에도 버튼이 되살아난다", async () => {
    renderHome(vi.fn().mockRejectedValue(new JoinSessionRequestError("nope", 404)));

    type("GPH7GQ5Q");
    fireEvent.click(screen.getByRole("button", { name: "참여하기" }));

    await waitFor(() => expect(screen.getByTestId("home-join-error")).toBeVisible());
    expect(screen.getByTestId("home-join-button")).toBeEnabled();
  });

  /** 형식 오류는 입력한 화면에서 알려준다. 서버까지 보내면 화면을 옮긴 뒤에 실패해 어디를 고칠지 알기 어렵다. */
  it("형식이 어긋나면 화면을 옮기지 않고 여기서 알려준다", () => {
    const joinSession = renderHome();

    type("ZANI-8KQ");
    fireEvent.click(screen.getByRole("button", { name: "참여하기" }));

    expect(screen.getByRole("alert")).toHaveTextContent("초대 코드 또는 초대 링크를 확인해 주세요");
    expect(pushMock).not.toHaveBeenCalled();
    // 형식이 어긋난 코드는 서버까지 보내지 않는다.
    expect(joinSession).not.toHaveBeenCalled();
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
