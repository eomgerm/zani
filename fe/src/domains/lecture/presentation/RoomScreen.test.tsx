import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const roomConnection = vi.hoisted(() => ({
  connectionState: "connected" as const,
  error: null,
  room: null,
  sessionExpiresAt: null as string | null,
  retry: vi.fn(),
}));

vi.mock("./RoomProvider", () => ({
  RoomProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useRoomConnection: () => roomConnection,
}));

// 강사 종료 버튼이 인증 컨텍스트를 쓰므로, 컨텍스트가 없는 단위 테스트에서는 대체한다.
vi.mock("@/domains/auth", () => ({
  useAuth: () => ({ accessToken: "test-access-token" }),
}));

const roomParticipants = vi.hoisted(() => ({
  participants: [] as {
    id: string;
    name: string;
    color: string;
    role: "instructor" | "student";
    cameraEnabled: boolean;
    microphoneEnabled: boolean;
    handRaised: boolean;
  }[],
  localParticipantId: null as string | null,
}));

vi.mock("./useRoomParticipants", () => ({
  useRoomParticipants: () => roomParticipants,
}));

const push = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

import { RoomScreen } from "./RoomScreen";

const asStudent = () => {
  roomParticipants.participants = [
    {
      id: "me",
      name: "김도현",
      color: "#2aa584",
      role: "student",
      cameraEnabled: true,
      microphoneEnabled: true,
      handRaised: false,
    },
  ];
  roomParticipants.localParticipantId = "me";
};

const asInstructor = () => {
  roomParticipants.participants = [
    {
      id: "me",
      name: "박서준",
      color: "#10b981",
      role: "instructor",
      cameraEnabled: true,
      microphoneEnabled: true,
      handRaised: false,
    },
  ];
  roomParticipants.localParticipantId = "me";
};

afterEach(() => {
  cleanup();
  push.mockClear();
  roomConnection.sessionExpiresAt = null;
  vi.useRealTimers();
});

describe("RoomScreen side panel", () => {
  it("keeps the panel closed until a top-bar toggle is pressed", () => {
    asStudent();
    render(<RoomScreen sessionId="123" />);

    expect(screen.queryByPlaceholderText("전체에게 메시지 보내기")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "참여자" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  });

  it("opens the people panel and marks its toggle pressed", () => {
    asStudent();
    render(<RoomScreen sessionId="123" />);

    fireEvent.click(screen.getByRole("button", { name: "참여자" }));

    expect(screen.getByText("✋ 손든 참가자")).toBeVisible();
    expect(screen.getByRole("button", { name: "참여자" })).toHaveAttribute("aria-pressed", "true");
  });

  it("switches between people and chat without closing the panel", () => {
    asStudent();
    render(<RoomScreen sessionId="123" />);

    fireEvent.click(screen.getByRole("button", { name: "참여자" }));
    fireEvent.click(screen.getByRole("button", { name: "채팅" }));

    expect(screen.getByPlaceholderText("전체에게 메시지 보내기")).toBeVisible();
    expect(screen.getByRole("button", { name: "채팅" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "참여자" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  });

  it("closes the panel when the already-open toggle is pressed again", () => {
    asStudent();
    render(<RoomScreen sessionId="123" />);

    const chat = screen.getByRole("button", { name: "채팅" });
    fireEvent.click(chat);
    fireEvent.click(chat);

    expect(screen.queryByPlaceholderText("전체에게 메시지 보내기")).not.toBeInTheDocument();
  });

  it("panel stays available in gallery view — it is not tied to speaker view", () => {
    asStudent();
    render(<RoomScreen sessionId="123" />);

    // 기본은 갤러리 보기(토글 라벨이 "발표자 보기")인 상태에서 패널이 열린다.
    expect(screen.getByRole("button", { name: /발표자 보기/ })).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "채팅" }));

    expect(screen.getByPlaceholderText("전체에게 메시지 보내기")).toBeVisible();
  });
});

describe("RoomScreen view toggle", () => {
  const withParticipants = (count: number) => {
    roomParticipants.participants = Array.from({ length: count }, (_, i) => ({
      id: `p${i}`,
      name: `참가자 ${i}`,
      color: "#2aa584",
      role: i === 0 ? ("instructor" as const) : ("student" as const),
      cameraEnabled: true,
      microphoneEnabled: true,
      handRaised: false,
    }));
    roomParticipants.localParticipantId = "p0";
  };

  it("shows participant tiles in the default gallery view", () => {
    withParticipants(3);
    render(<RoomScreen sessionId="123" />);

    expect(screen.getAllByRole("group", { name: /카메라 켜짐/ }).length).toBeGreaterThan(0);
  });

  it("brings the tiles back when returning from speaker view", () => {
    withParticipants(3);
    render(<RoomScreen sessionId="123" />);

    fireEvent.click(screen.getByRole("button", { name: /발표자 보기/ }));
    expect(screen.queryByRole("group", { name: /카메라 켜짐/ })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /전체 보기/ }));

    expect(screen.getAllByRole("group", { name: /카메라 켜짐/ }).length).toBeGreaterThan(0);
  });

  it("keeps showing tiles after the panel opens and closes", () => {
    withParticipants(20);
    render(<RoomScreen sessionId="123" />);

    fireEvent.click(screen.getByRole("button", { name: "참여자" }));
    fireEvent.click(screen.getByRole("button", { name: "참여자" }));

    expect(screen.getAllByRole("group", { name: /카메라 켜짐/ })).toHaveLength(12);
  });
});

describe("RoomScreen controls", () => {
  it("toggles screen share into an overlay with a stop action", () => {
    asStudent();
    render(<RoomScreen sessionId="123" />);

    fireEvent.click(screen.getByRole("button", { name: "화면 공유" }));

    expect(screen.getByText("내 화면을 공유하고 있어요")).toBeVisible();

    fireEvent.click(screen.getByRole("button", { name: "화면 공유 중지" }));

    expect(screen.queryByText("내 화면을 공유하고 있어요")).not.toBeInTheDocument();
  });

  it("sends a leaving student back to their lecture list", () => {
    asStudent();
    render(<RoomScreen sessionId="123" />);

    fireEvent.click(screen.getByRole("button", { name: "나가기" }));

    expect(push).toHaveBeenCalledWith("/my-lectures");
  });

  it("sends a leaving instructor to the post-class note screen", () => {
    asInstructor();
    render(<RoomScreen sessionId="123" />);

    fireEvent.click(screen.getByRole("button", { name: "나가기" }));

    expect(push).toHaveBeenCalledWith("/my-lectures/123/note");
  });

  it("derives the instructor role from the token-provided participant role", () => {
    roomParticipants.participants = [
      {
        id: "me",
        name: "박서준",
        color: "#10b981",
        role: "instructor",
        cameraEnabled: true,
        microphoneEnabled: true,
        handRaised: false,
      },
    ];
    roomParticipants.localParticipantId = "me";

    render(<RoomScreen sessionId="123" />);
    fireEvent.click(screen.getByRole("button", { name: "카메라 끄기" }));

    // 강사에게는 학생용 카메라 안내가 뜨지 않는다.
    expect(screen.queryByText(/카메라가 10분 이상 꺼져 있어요/)).not.toBeInTheDocument();
  });

  it("warns a student whose camera is off", () => {
    asStudent();
    render(<RoomScreen sessionId="123" />);

    fireEvent.click(screen.getByRole("button", { name: "카메라 끄기" }));

    expect(screen.getByText(/카메라가 10분 이상 꺼져 있어요/)).toBeVisible();
  });
});

describe("RoomScreen maximum duration warning", () => {
  it("warns with the auto-end time the server sent on entry", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-07-26T12:00:00Z"));
    asStudent();
    // 미디어 토큰 응답이 알려준 자동 종료 예정 시각 — 5분 뒤
    roomConnection.sessionExpiresAt = "2026-07-26T12:05:00Z";

    render(<RoomScreen sessionId="123" />);
    await act(async () => vi.advanceTimersByTime(0));

    expect(screen.getByText(/최대 수업 시간까지/)).toBeVisible();
  });

  it("stays quiet when the server has not reported an auto-end time yet", async () => {
    vi.useFakeTimers();
    asStudent();
    roomConnection.sessionExpiresAt = null;

    render(<RoomScreen sessionId="123" />);
    await act(async () => vi.advanceTimersByTime(0));

    expect(screen.queryByText(/최대 수업 시간까지/)).toBeNull();
  });
});

describe("RoomScreen end-session control", () => {
  it("offers the end-class button to the instructor only", () => {
    asInstructor();
    render(<RoomScreen sessionId="123" />);

    expect(screen.getByTestId("end-session-button")).toBeVisible();
  });

  it("hides the end-class button from students", () => {
    asStudent();
    render(<RoomScreen sessionId="123" />);

    expect(screen.queryByTestId("end-session-button")).toBeNull();
  });
});
