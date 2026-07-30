import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { CreateSessionRequestError } from "@/domains/lecture/infrastructure/createSessionApi";
import { CreateSetupScreen } from "./CreateSetupScreen";

const { pushMock, authState } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  authState: { accessToken: "access-token" as string | null },
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

vi.mock("@/domains/auth/presentation/AuthProvider", () => ({
  useAuth: () => authState,
}));

const created = (sessionId = "777", inviteCode = "A7KM2PQR") => ({
  sessionId,
  inviteCode,
  status: "PREPARING",
  role: "INSTRUCTOR",
  expiresAt: null,
});

const typeTitle = (value: string) => {
  fireEvent.change(screen.getByLabelText("강의명"), { target: { value } });
};

beforeEach(() => {
  vi.clearAllMocks();
  authState.accessToken = "access-token";
});

describe("CreateSetupScreen", () => {
  /** 초대 코드는 서버가 만든다. 만들기 전에 코드를 보여주면 실제와 다른 값을 공유하게 된다. */
  it("방을 만들기 전에는 초대 링크를 보여주지 않는다", async () => {
    render(<CreateSetupScreen createSession={vi.fn()} />);

    expect(screen.getByText("방을 만들면 초대 링크가 발급돼요.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "복사" })).not.toBeInTheDocument();
  });

  it("강의명이 비어 있으면 만들기 버튼을 누를 수 없다", () => {
    render(<CreateSetupScreen createSession={vi.fn()} />);

    expect(screen.getByRole("button", { name: "방 만들고 시작하기" })).toBeDisabled();
  });

  it("입력한 강의명으로 방을 만들고 서버가 발급한 초대 링크를 보여준다", async () => {
    const createSession = vi.fn().mockResolvedValue(created());
    render(<CreateSetupScreen createSession={createSession} />);

    typeTitle("  JavaScript 기초 1강  ");
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "방 만들고 시작하기" }));
    });

    // 앞뒤 공백은 다듬어 보낸다.
    expect(createSession).toHaveBeenCalledWith("JavaScript 기초 1강", "access-token");
    expect(await screen.findByText(/\/prejoin\/A7KM2PQR$/)).toBeInTheDocument();
    // 코드는 눈으로 옮겨 적기 쉽게 끊어 보여준다.
    expect(screen.getByText("A7KM-2PQR")).toBeInTheDocument();
  });

  /**
   * 수업을 시작시키는 건 이 화면이 아니라 강의실이다(useStartOnConnected). 여기서 시작하면 강사가 아직 LiveKit 에
   * 연결되지 않은 상태로 초대 코드가 열려, 연결이 실패했을 때 학생만 강사 없는 방에 들어온다.
   */
  it("준비 상태여도 여기서는 시작시키지 않고 강의실로 보낸다", async () => {
    render(
      <CreateSetupScreen
        createSession={vi.fn().mockResolvedValue(created("777"))}
      />,
    );

    typeTitle("테스트 수업");
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "방 만들고 시작하기" }));
    });

    await act(async () => {
      fireEvent.click(await screen.findByRole("button", { name: "수업 시작하고 입장" }));
    });

    expect(pushMock).toHaveBeenCalledWith("/room/777");
  });

  /** 생성 즉시 LIVE 인 서버 구성에서는 버튼 문구가 달라진다. 시작은 어느 구성에서도 이 화면이 하지 않는다. */
  it("이미 진행 중으로 만들어졌으면 강의실 입장으로 보인다", async () => {
    render(
      <CreateSetupScreen
        createSession={vi.fn().mockResolvedValue({ ...created("777"), status: "LIVE" })}
      />,
    );

    typeTitle("테스트 수업");
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "방 만들고 시작하기" }));
    });

    fireEvent.click(await screen.findByRole("button", { name: "강의실 입장" }));

    expect(pushMock).toHaveBeenCalledWith("/room/777");
  });

  /** 이미 수업이 열려 있는 강사는 새로 만들 수 없다. 왜 막혔는지 알려줘야 스스로 해결할 수 있다. */
  it("이미 진행 중인 수업이 있으면 원인을 알려주고 이동하지 않는다", async () => {
    const createSession = vi
      .fn()
      .mockRejectedValue(new CreateSessionRequestError("active", 409, "SESSION_APP_001"));
    render(<CreateSetupScreen createSession={createSession} />);

    typeTitle("테스트 수업");
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "방 만들고 시작하기" }));
    });

    expect(await screen.findByRole("alert")).toHaveTextContent("이미 진행 중인 수업이 있어요");
    expect(pushMock).not.toHaveBeenCalled();
  });

  it("실패해도 다시 시도할 수 있게 버튼을 되살린다", async () => {
    const createSession = vi
      .fn()
      .mockRejectedValueOnce(new CreateSessionRequestError("boom", 500))
      .mockResolvedValueOnce(created("999"));
    render(<CreateSetupScreen createSession={createSession} />);

    typeTitle("테스트 수업");
    const createButton = screen.getByRole("button", { name: "방 만들고 시작하기" });
    await act(async () => {
      fireEvent.click(createButton);
    });

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    await waitFor(() => expect(createButton).toBeEnabled());

    await act(async () => {
      fireEvent.click(createButton);
    });

    expect(await screen.findByRole("button", { name: "수업 시작하고 입장" })).toBeInTheDocument();
  });

  it("로그인 토큰이 없으면 서버를 호출하지 않고 로그인을 안내한다", async () => {
    authState.accessToken = null;
    const createSession = vi.fn();
    render(<CreateSetupScreen createSession={createSession} />);

    typeTitle("테스트 수업");
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "방 만들고 시작하기" }));
    });

    expect(await screen.findByRole("alert")).toHaveTextContent("로그인이 필요해요");
    expect(createSession).not.toHaveBeenCalled();
  });
});
