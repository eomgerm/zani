import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const roomConnection = vi.hoisted(() => ({
  connectionState: "connected" as "connecting" | "connected" | "error",
  error: null,
  room: null,
  sessionExpiresAt: null as string | null,
  sessionTitle: null as string | null,
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
  props: [] as Array<{
    active: boolean;
    denied?: boolean;
    onAvailabilityChange?: (availability: string) => void;
    onDetection?: (output: unknown) => void;
  }>,
}));
vi.mock("./components/room/AttentionCameraSource", () => ({
  AttentionCameraSource: (props: (typeof attentionSource.props)[number]) => {
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

// 폴링 자체는 useCoachingStatus.test 가 본다. 여기서는 누구에게 켜지는지와 배지가 뜨는지만 본다.
const coaching = vi.hoisted(() => ({
  enabledCalls: [] as boolean[],
  availability: "ACTIVE" as string,
  /** 팁 카드가 폴링 결과를 받아 가는 통로. 테스트가 여기로 팁을 흘려 넣는다. */
  onResult: null as ((result: unknown) => void) | null,
}));
vi.mock("./useCoachingStatus", () => ({
  useCoachingStatus: (options: { enabled: boolean; onResult?: (result: unknown) => void }) => {
    coaching.enabledCalls.push(options.enabled);
    coaching.onResult = options.onResult ?? null;
    return { availability: coaching.availability };
  },
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
  roomConnection.sessionTitle = null;
  roomConnection.connectionState = "connected";
  media.cameraEnabled = true;
  media.microphoneEnabled = true;
  media.ready = true;
  media.cameraBlocked = false;
  media.cameraPermissionDenied = false;
  attentionSource.props = [];
  coaching.enabledCalls = [];
  coaching.availability = "ACTIVE";
  coaching.onResult = null;
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

    expect(screen.getByRole("button", { name: "참여자" })).toHaveAttribute("aria-pressed", "true");
    // 참여자 탭에는 채팅 입력이 없다. 손든 참가자 목록은 실제로 손을 든 사람이 있을 때만 나오므로
    // (업무 WebSocket 소관) 패널이 열렸는지는 탭 구분으로 확인한다.
    expect(
      screen.queryByRole("textbox", { name: "전체에게 메시지 보내기" }),
    ).not.toBeInTheDocument();
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

describe("RoomScreen coaching wiring", () => {
  /** 폴링이 켜진 적이 있는지. */
  const everEnabled = () => coaching.enabledCalls.some(Boolean);

  it("polls and shows the coaching notice for an instructor", () => {
    asInstructor();
    coaching.availability = "POLL_FAILED";

    render(<RoomScreen sessionId="123" />);

    expect(everEnabled()).toBe(true);
    expect(screen.getByTestId("coaching-status-notice")).toHaveTextContent(
      "수업 팁을 받아오지 못하고 있어요",
    );
  });

  it("keeps the coaching notice away from students", () => {
    asStudent();
    coaching.availability = "POLL_FAILED";

    render(<RoomScreen sessionId="123" />);

    expect(everEnabled()).toBe(false);
    expect(screen.queryByTestId("coaching-status-notice")).not.toBeInTheDocument();
  });

  // 참가자 목록이 오기 전에는 isInstructor 가 true 다. 그것만 보면 학생도 잠깐 강사 전용
  // 엔드포인트를 두드리고 강사용 배지를 보게 된다.
  it("waits for the role to be confirmed before polling", () => {
    roomParticipants.participants = [];
    roomParticipants.localParticipantId = null;
    coaching.availability = "POLL_FAILED";

    render(<RoomScreen sessionId="123" />);

    expect(everEnabled()).toBe(false);
    expect(screen.queryByTestId("coaching-status-notice")).not.toBeInTheDocument();
  });

  /** 폴링이 팁을 물어 왔다고 알린다. */
  const deliverTip = (triggerId: string, title: string) =>
    act(() =>
      coaching.onResult?.({
        triggerId,
        tip: {
          tipType: "CONFUSED",
          title,
          message: "전체 학생의 30%가 현재 내용을 헷갈려 하고 있어요.",
          targetConcept: "클로저",
        },
        unavailableReason: null,
      }),
    );

  it("shows a tip the poll delivered to an instructor", () => {
    asInstructor();

    render(<RoomScreen sessionId="123" />);
    expect(screen.queryByTestId("coach-tip-card")).not.toBeInTheDocument();

    deliverTip("t-1", "추가 설명이 필요해요");

    expect(screen.getByTestId("coach-tip-card")).toHaveTextContent("추가 설명이 필요해요");
  });

  it("closes the tip card on 확인", () => {
    asInstructor();
    render(<RoomScreen sessionId="123" />);
    deliverTip("t-1", "추가 설명이 필요해요");

    fireEvent.click(screen.getByRole("button", { name: "확인" }));

    expect(screen.queryByTestId("coach-tip-card")).not.toBeInTheDocument();
  });

  // 학생은 팁을 받지 않는다(86 요구사항). 폴링이 꺼져 있는 것과 별개로 카드도 막혀야 한다 —
  // 나중에 폴링 조건이 바뀌어도 이 겹이 남는다.
  it("never renders the tip card for a student", () => {
    asStudent();

    render(<RoomScreen sessionId="123" />);
    // 학생 화면에서는 폴링이 돌지 않으므로 결과가 전달될 통로 자체가 없다.
    expect(coaching.enabledCalls.some(Boolean)).toBe(false);

    // 어떤 경로로든 결과가 흘러 들어와도 카드는 뜨지 않는다.
    deliverTip("t-1", "추가 설명이 필요해요");

    expect(screen.queryByTestId("coach-tip-card")).not.toBeInTheDocument();
  });

  // 팁 카드는 폴러를 따로 두지 않는다. 두 번 돌면 분모 조회와 쿨타임 소모가 두 배가 된다.
  it("feeds the tip card from the coaching poll instead of its own poller", () => {
    asInstructor();

    render(<RoomScreen sessionId="123" />);

    expect(coaching.onResult).not.toBeNull();
  });

  it("says nothing while coaching works", () => {
    asInstructor();

    render(<RoomScreen sessionId="123" />);

    expect(screen.queryByTestId("coaching-status-notice")).not.toBeInTheDocument();
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
  /** 강의명은 강사가 입력한 값이다. 픽스처 기본값을 보여주면 다른 수업에 들어온 것처럼 읽힌다. */
  it("서버가 내려준 강의명을 보여준다", () => {
    roomConnection.sessionTitle = "JavaScript 기초 1강";

    render(<RoomScreen sessionId="123" />);

    expect(screen.getByText("JavaScript 기초 1강")).toBeVisible();
  });

  /** 아직 못 받았을 때 픽스처 제목이 새지 않아야 한다. */
  it("강의명을 못 받았으면 픽스처 제목을 보여주지 않는다", () => {
    render(<RoomScreen sessionId="123" />);

    expect(screen.queryByText("React 상태관리 심화")).not.toBeInTheDocument();
  });

  /** 발표자 보기는 강사 화면을 크게 보는 기능이다. 영상이 없으면 아바타만 남아 목데이터처럼 보인다. */
  it("발표자 보기에서 강사 카메라를 붙인다", () => {
    roomParticipants.participants = [
      {
        id: "host-1",
        name: "김강사",
        color: "#2aa584",
        role: "instructor",
        cameraEnabled: true,
        microphoneEnabled: true,
        handRaised: false,
      },
    ];
    roomParticipants.localParticipantId = "host-1";

    render(<RoomScreen sessionId="123" />);
    fireEvent.click(screen.getByRole("button", { name: /발표자 보기/ }));

    expect(screen.getByTestId("speaker-video")).toBeInTheDocument();
  });
  /**
   * 제목은 미디어 토큰 응답으로 오므로 연결이 끝나기 전에는 알 수 없다. 그 동안 최종값처럼 보이는 문구를 그리면
   * 제목이 "수업" 에서 실제 이름으로 바뀌는 것처럼 보인다.
   */
  it("연결 중에는 제목 대신 자리만 잡는다", () => {
    roomConnection.connectionState = "connecting";

    render(<RoomScreen sessionId="123" />);

    expect(screen.getByTestId("room-title-loading")).toBeVisible();
    expect(screen.queryByText("수업")).not.toBeInTheDocument();
  });

  /** 서버가 제목을 안 내려주는 구성에서 자리만 잡고 영원히 기다리면 안 된다. */
  it("연결이 끝났는데 제목이 없으면 기본 문구를 쓴다", () => {
    render(<RoomScreen sessionId="123" />);

    expect(screen.queryByTestId("room-title-loading")).not.toBeInTheDocument();
    expect(screen.getByText("수업")).toBeVisible();
  });

  /** 강사가 아직 안 잡혔을 때 칩을 그리면 "강의:  선생님" 처럼 빈칸이 남는다. */
  it("발표자 보기에서 강사가 아직 없으면 기다린다고 알린다", () => {
    roomParticipants.participants = [];

    render(<RoomScreen sessionId="123" />);
    fireEvent.click(screen.getByRole("button", { name: /발표자 보기/ }));

    expect(screen.getByText("강의자를 기다리고 있어요")).toBeVisible();
  });

  it("shows the understanding check after three low-engagement windows", () => {
    asStudent();
    render(<RoomScreen sessionId="123" />);
    const lowOutput = {
      outcome: "Barely-Engaged" as const,
      probabilities: [0.1, 0.3, 0.5, 0.1],
    };

    act(() => {
      lastProps()?.onDetection?.(lowOutput);
      lastProps()?.onDetection?.(lowOutput);
      lastProps()?.onDetection?.(lowOutput);
    });

    expect(screen.getByText("잠깐 확인할게요 ✋")).toBeVisible();
  });

  it("pauses both counters while a prompt is visible and resets them when it closes", async () => {
    asStudent();
    render(<RoomScreen sessionId="123" />);
    const lowOutput = {
      outcome: "Barely-Engaged" as const,
      probabilities: [0.1, 0.3, 0.5, 0.1],
    };
    const unmeasurable = { outcome: "UNMEASURABLE" as const };
    const capturedDetectionCallback = lastProps()?.onDetection;

    act(() => {
      for (let count = 0; count < 3; count += 1) capturedDetectionCallback?.(lowOutput);
    });
    expect(screen.getByText("잠깐 확인할게요 ✋")).toBeVisible();

    act(() => {
      for (let count = 0; count < 3; count += 1) {
        capturedDetectionCallback?.(unmeasurable);
      }
    });
    expect(screen.queryByText("얼굴이 잘 보이지 않아요 🙂")).not.toBeInTheDocument();

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: /이해했어요/ }));
    });
    act(() => {
      for (let count = 0; count < 2; count += 1) {
        lastProps()?.onDetection?.(unmeasurable);
      }
    });
    expect(screen.queryByText("얼굴이 잘 보이지 않아요 🙂")).not.toBeInTheDocument();

    act(() => lastProps()?.onDetection?.(unmeasurable));
    expect(screen.getByText("얼굴이 잘 보이지 않아요 🙂")).toBeVisible();
  });
});
