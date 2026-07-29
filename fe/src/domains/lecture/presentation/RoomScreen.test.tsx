import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const roomConnection = vi.hoisted(() => ({
  connectionState: "connected" as "connecting" | "connected" | "error",
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

// 실제 LiveKit publish 상태 대신 테스트가 제어하는 값을 쓴다(미디어 훅 자체는 useRoomMediaControls.test 가 검증).
const media = vi.hoisted(() => ({
  microphoneEnabled: true,
  cameraEnabled: true,
  ready: true,
  microphoneBlocked: false,
  cameraBlocked: false,
  mediaError: null as string | null,
  cameraPermissionDenied: false,
  microphones: [] as { value: string; label: string }[],
  cameras: [] as { value: string; label: string }[],
  activeMicrophoneId: null as string | null,
  activeCameraId: null as string | null,
  toggleMicrophone: vi.fn(),
  toggleCamera: vi.fn(),
  selectMicrophone: vi.fn(),
  selectCamera: vi.fn(),
}));

vi.mock("./useRoomMediaControls", () => ({
  useRoomMediaControls: () => media,
}));

// 판정 배선의 세부 판단은 AttentionCameraSource.test 가 본다. 여기서는 누구에게 붙는지와 넘기는 props 만 본다.
const attentionSource = vi.hoisted(() => ({
  props: [] as {
    active: boolean;
    denied?: boolean;
    onAvailabilityChange?: (availability: string) => void;
  }[],
}));
vi.mock("./components/room/AttentionCameraSource", () => ({
  AttentionCameraSource: (props: {
    active: boolean;
    denied?: boolean;
    onAvailabilityChange?: (availability: string) => void;
  }) => {
    attentionSource.props.push(props);
    return <div data-testid="attention-camera-source" />;
  },
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
  media.cameraEnabled = true;
  media.microphoneEnabled = true;
  media.ready = true;
  media.cameraBlocked = false;
  media.cameraPermissionDenied = false;
  attentionSource.props = [];
  media.toggleCamera.mockClear();
  media.toggleMicrophone.mockClear();
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

    media.cameraEnabled = false;

    render(<RoomScreen sessionId="123" />);

    // 강사는 판정 대상이 아니라 학생용 분석 안내가 뜨지 않는다.
    expect(screen.queryByTestId("analysis-status-notice")).not.toBeInTheDocument();
  });

  /*
    카메라가 꺼졌을 때의 안내는 카메라 안내 프롬프트(81)가 1분 지속 뒤 원인별로 맡는다.
    여기 있던 "10분 이상·이후 5분마다" 배너는 확정 규칙과 어긋나 76 에서 제거했다.
  */
  it("leaves the camera-off guidance to the camera prompt instead of a standing banner", () => {
    asStudent();
    media.cameraEnabled = false;

    render(<RoomScreen sessionId="123" />);

    expect(screen.queryByText(/카메라가 10분 이상 꺼져 있어요/)).not.toBeInTheDocument();
  });

  it("delegates the camera toggle to the media hook", () => {
    asStudent();

    render(<RoomScreen sessionId="123" />);
    fireEvent.click(screen.getByRole("button", { name: "카메라 끄기" }));

    expect(media.toggleCamera).toHaveBeenCalledOnce();
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

  it("hides the end-class button until the role is confirmed", () => {
    // 참가자 목록이 도착하기 전에는 역할을 알 수 없다. 이때 종료 버튼이 보이면 학생에게도 잠시 노출된다.
    roomParticipants.participants = [];
    roomParticipants.localParticipantId = null;

    render(<RoomScreen sessionId="123" />);

    expect(screen.queryByTestId("end-session-button")).toBeNull();
  });
});

describe("RoomScreen attention wiring", () => {
  /** 판정 소스가 마지막으로 받은 props. */
  const lastProps = () => attentionSource.props.at(-1);

  it("mounts the attention camera source for a student", () => {
    asStudent();

    render(<RoomScreen sessionId="123" />);

    expect(screen.getByTestId("attention-camera-source")).toBeInTheDocument();
  });

  it("runs detection while the student camera is publishing on a usable room", () => {
    asStudent();

    render(<RoomScreen sessionId="123" />);

    expect(lastProps()).toMatchObject({ active: true, denied: false });
  });

  // 상단 바에 흐름대로 놓아야 한다. 띄워 얹으면 보기 전환·패널 토글 위를 가린다.
  it("shows the analysis notice once the source reports it stopped", () => {
    asStudent();

    render(<RoomScreen sessionId="123" />);
    expect(screen.queryByTestId("analysis-status-notice")).not.toBeInTheDocument();

    act(() => lastProps()?.onAvailabilityChange?.("UNAVAILABLE"));

    expect(screen.getByTestId("analysis-status-notice")).toHaveTextContent(
      "학습 분석을 사용할 수 없어요",
    );
  });

  // 어떤 비활성 상태도 수업을 막지 않는다(76 요구사항).
  it("keeps the class controls usable while the analysis is unavailable", () => {
    asStudent();

    render(<RoomScreen sessionId="123" />);
    act(() => lastProps()?.onAvailabilityChange?.("UNAVAILABLE"));

    expect(screen.getByTestId("analysis-status-notice")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "카메라 끄기" })).toBeVisible();
    expect(screen.getByRole("button", { name: "참여자" })).toBeVisible();
    expect(screen.getByRole("button", { name: /발표자 보기/ })).toBeVisible();
  });

  it("stops detection when the student turns the camera off", () => {
    asStudent();
    media.cameraEnabled = false;

    render(<RoomScreen sessionId="123" />);

    expect(lastProps()?.active).toBe(false);
  });

  it("stops detection while the room is reconnecting", () => {
    asStudent();
    media.ready = false;

    render(<RoomScreen sessionId="123" />);

    expect(lastProps()?.active).toBe(false);
  });

  it("reports a camera the browser denied apart from one the student turned off", () => {
    asStudent();
    media.cameraPermissionDenied = true;

    render(<RoomScreen sessionId="123" />);

    expect(lastProps()?.denied).toBe(true);
  });

  it("never analyses the instructor own camera", () => {
    asInstructor();

    render(<RoomScreen sessionId="123" />);

    expect(screen.queryByTestId("attention-camera-source")).not.toBeInTheDocument();
    expect(attentionSource.props).toHaveLength(0);
  });

  it("holds detection back until the role is known", () => {
    // 역할은 참가자 목록에서 파생한다. 아직 아무도 잡히지 않았으면 판정을 시작하지 않는다.
    roomParticipants.participants = [];

    render(<RoomScreen sessionId="123" />);

    expect(screen.queryByTestId("attention-camera-source")).not.toBeInTheDocument();
  });
});
