import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

// jsdom 에는 ResizeObserver 가 없다. 클립 탭이 품은 recharts 계열이 마운트되며 참조하므로 없으면
// 렌더가 통째로 터진다. 크기를 재는 것이 이 테스트의 관심사는 아니다.
vi.stubGlobal(
  "ResizeObserver",
  class {
    observe() {}
    unobserve() {}
    disconnect() {}
  },
);

const sessionOf = (sessionId: string, overrides: Record<string, unknown> = {}) => ({
  sessionId,
  inviteCode: "ABC123",
  title: "React 상태 관리",
  instructorName: "박서준",
  status: "ENDED",
  role: "INSTRUCTOR",
  startedAt: "2026-08-04T14:00:00",
  endedAt: "2026-08-04T16:05:00",
  participantCount: 32,
  reportStatus: "COMPLETED",
  rejoinable: false,
  ...overrides,
});

const role = vi.hoisted(() => ({
  status: "ready" as "loading" | "ready" | "unknown",
  role: "INSTRUCTOR" as "INSTRUCTOR" | "STUDENT" | null,
  session: null as Record<string, unknown> | null,
}));
vi.mock("./useSessionRole", () => ({ useSessionRole: () => role }));

// 리포트 탭의 두 본문은 여기서 검증할 대상이 아니다. 어느 쪽이 렌더됐고 무엇이 전달됐는지만 본다.
vi.mock("./components/report/InstructorReport", () => ({
  InstructorReport: ({ sessionId }: { sessionId: string }) => (
    <div data-testid="instructor-report">{sessionId}</div>
  ),
}));
vi.mock("./components/report/StudentReport", () => ({
  StudentReport: ({ sessionId }: { sessionId: string }) => (
    <div data-testid="student-report">{sessionId}</div>
  ),
}));
vi.mock("@/domains/report", () => ({
  StudentReportClip: ({ sessionId }: { sessionId: string }) => (
    <div data-testid="student-clip">{sessionId}</div>
  ),
}));

import { ReportScreen } from "./ReportScreen";

/** 리포트 탭은 기본이 클립 탭이라 한 번 눌러 들어간다. */
const openReportTab = () => {
  fireEvent.click(screen.getByRole("button", { name: /리포트/ }));
};

/** 강사용 목업 패널에만 있는 박제된 재생 시간. 목업이 그려졌는지 가리는 표식으로 쓴다. */
const MOCK_CLIP_MARKER = "42:30 / 2:05:30";

beforeEach(() => {
  role.status = "ready";
  role.role = "INSTRUCTOR";
  role.session = sessionOf("s1");
});

describe("ReportScreen", () => {
  it("강사에게는 강사 리포트를, 학생에게는 학생 리포트를 그린다", () => {
    const instructor = render(<ReportScreen lectureId="s1" />);
    openReportTab();

    expect(screen.getByTestId("instructor-report")).toBeInTheDocument();
    expect(screen.queryByTestId("student-report")).not.toBeInTheDocument();
    instructor.unmount();

    role.role = "STUDENT";
    role.session = sessionOf("s1", { role: "STUDENT" });

    render(<ReportScreen lectureId="s1" />);
    openReportTab();

    expect(screen.getByTestId("student-report")).toBeInTheDocument();
    expect(screen.queryByTestId("instructor-report")).not.toBeInTheDocument();
  });

  it("기본은 클립 탭이고, initialTab=report 면 리포트 탭으로 펼친다", () => {
    const clipFirst = render(<ReportScreen lectureId="s1" />);

    // 내 강의실에서 들어오면 먼저 보고 싶은 것은 다시 보기다.
    expect(screen.getByText(MOCK_CLIP_MARKER)).toBeInTheDocument();
    expect(screen.queryByTestId("instructor-report")).not.toBeInTheDocument();
    clipFirst.unmount();

    // 퀴즈에서 돌아올 때 쓰는 경로다. 클립 탭으로 떨어지면 보던 자리를 다시 찾아 들어가야 한다.
    render(<ReportScreen lectureId="s1" initialTab="report" />);

    expect(screen.getByTestId("instructor-report")).toBeInTheDocument();
    expect(screen.queryByText(MOCK_CLIP_MARKER)).not.toBeInTheDocument();
  });

  it("제목과 시각을 세션 응답에서 읽는다 — fixture 로 흘러내리지 않는다", () => {
    role.session = sessionOf("0123456789", { title: "예외 처리와 응답 코드" });

    render(<ReportScreen lectureId="0123456789" />);

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("예외 처리와 응답 코드");
    // 길이는 시작·종료 시각에서 계산한다. 날짜·시각도 박제된 "(목) 14:00" 이 아니다.
    expect(screen.getByText(/2시간 5분 \| 2026\.08\.04 .* 14:00/)).toBeInTheDocument();
  });

  it("세션 id 를 카드에 그대로 넘긴다", () => {
    role.session = sessionOf("0123456789");

    render(<ReportScreen lectureId="0123456789" />);
    openReportTab();

    expect(screen.getByTestId("instructor-report")).toHaveTextContent("0123456789");
  });

  it("역할을 확인하는 동안에는 어느 쪽도 짐작하지 않는다", () => {
    role.status = "loading";
    role.role = null;
    role.session = null;

    render(<ReportScreen lectureId="s1" />);

    // 잘못 고르면 학생이 집단 경로를 불러 403 을 받는다. 탭 자체를 내지 않는다.
    expect(screen.queryByTestId("instructor-report")).not.toBeInTheDocument();
    expect(screen.queryByTestId("student-report")).not.toBeInTheDocument();
    expect(screen.getByText(/불러오는 중이에요/)).toBeInTheDocument();
  });

  it("역할을 알 수 없으면 이유를 알린다", () => {
    role.status = "unknown";
    role.role = null;
    role.session = null;

    render(<ReportScreen lectureId="s1" />);

    expect(screen.getByText(/리포트를 볼 수 없어요/)).toBeInTheDocument();
  });

  it("학생으로 확정된 경우에만 실제 클립 패널을 그린다", () => {
    role.role = "STUDENT";
    role.session = sessionOf("s4", { role: "STUDENT" });

    render(<ReportScreen lectureId="s4" />);

    expect(screen.getByTestId("student-clip")).toHaveTextContent("s4");
  });

  it("강사 클립 탭은 아직 목업이다", () => {
    render(<ReportScreen lectureId="s1" />);

    // 강사 클립 탭은 강사 리포트 API 가 생길 때까지 목업이다.
    expect(screen.getByText(MOCK_CLIP_MARKER)).toBeInTheDocument();
    expect(screen.queryByTestId("student-clip")).not.toBeInTheDocument();
  });

  it("역할을 확인하는 동안 목업 클립을 먼저 보여주지 않는다", () => {
    role.status = "loading";
    role.role = null;
    role.session = null;

    render(<ReportScreen lectureId="s4" />);

    // 목업을 먼저 보여 주면 학생이 남의 강의 전사를 자기 수업으로 읽는다.
    expect(screen.queryByText(MOCK_CLIP_MARKER)).not.toBeInTheDocument();
    expect(screen.queryByTestId("student-clip")).not.toBeInTheDocument();
    expect(screen.getByText(/불러오는 중이에요/)).toBeInTheDocument();
  });

  it("역할을 알 수 없으면 목업 클립을 남기지 않는다", () => {
    role.status = "unknown";
    role.role = null;
    role.session = null;

    render(<ReportScreen lectureId="s4" />);

    // 로딩과 달리 이 상태는 지나가지 않는다. 가짜가 영구히 남으면 안 된다.
    expect(screen.queryByText(MOCK_CLIP_MARKER)).not.toBeInTheDocument();
    expect(screen.getByText(/리포트를 볼 수 없어요/)).toBeInTheDocument();
  });

  it("리포트 처리가 끝나지 않은 수업은 탭과 본문을 감춘다", () => {
    role.session = sessionOf("s1", { reportStatus: "PROCESSING" });

    render(<ReportScreen lectureId="s1" />);

    expect(screen.getByText("아직 분석이 끝나지 않았어요")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /리포트/ })).not.toBeInTheDocument();
  });

  it("분석이 실패한 수업은 실패 안내를 낸다", () => {
    role.session = sessionOf("s1", { reportStatus: "FAILED" });

    render(<ReportScreen lectureId="s1" />);

    expect(screen.getByText("결과를 생성하지 못했어요")).toBeInTheDocument();
  });
});
