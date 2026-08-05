import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { SessionSummary } from "../infrastructure/sessionListApi";
import { toMyLecture, type MyLecture } from "./myLectures";

/** 훅이 돌려주는 것은 세션 요약을 카드용으로 접은 값이다. 테스트도 같은 변환을 거친다. */
const lectureOf = (sessionId: string, overrides: Partial<SessionSummary> = {}): MyLecture =>
  toMyLecture(sessionOf(sessionId, overrides));

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

const sessionOf = (sessionId: string, overrides: Partial<SessionSummary> = {}): SessionSummary => ({
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
  lecture: null as MyLecture | null,
}));
vi.mock("./useSessionRole", () => ({ useSessionRole: () => role }));

// 이 파일이 보는 것은 ReportScreen 의 배치(어느 탭에 무엇이 걸리는지)다. 두 리포트 본문과
// report 도메인 카드 안쪽은 각자의 테스트가 본다 — 여기서는 조회하지 않는 껍데기로 바꿔 끼운다.
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
  // 강사는 같은 패널을 강사 엔드포인트로 조회한다(308). 여기서는 어느 쪽이 걸리는지만 본다.
  InstructorReportClip: ({ sessionId }: { sessionId: string }) => (
    <div data-testid="instructor-clip">{sessionId}</div>
  ),
  // 강사·학생 두 경로 모두에서 그려지는 공통 카드다. 역할 인자를 받지 않는다.
  SessionSummaryCard: ({ sessionId }: { sessionId: string }) => (
    <div data-testid="session-summary">{sessionId}</div>
  ),
  useInstructorReport: () => ({ status: "loading", report: null, retry: () => {} }),
  useGroupAttentionTimeline: () => ({ status: "loading", timeline: null, retry: () => {} }),
  focusedIntervalRatio: () => null,
  focusedRatioBand: () => "보통",
  formatOffset: (seconds: number) => String(seconds),
}));

import { ReportScreen } from "./ReportScreen";

/** 리포트 탭은 기본이 클립 탭이라 한 번 눌러 들어간다. */
const openReportTab = () => {
  fireEvent.click(screen.getByRole("button", { name: /리포트/ }));
};

beforeEach(() => {
  role.status = "ready";
  role.role = "INSTRUCTOR";
  role.lecture = lectureOf("s1");
});

describe("ReportScreen", () => {
  it("강사에게는 강사 리포트를, 학생에게는 학생 리포트를 그린다", () => {
    const instructor = render(<ReportScreen lectureId="s1" />);
    openReportTab();

    expect(screen.getByTestId("instructor-report")).toBeInTheDocument();
    expect(screen.queryByTestId("student-report")).not.toBeInTheDocument();
    instructor.unmount();

    role.role = "STUDENT";
    role.lecture = lectureOf("s1", { role: "STUDENT" });

    render(<ReportScreen lectureId="s1" />);
    openReportTab();

    expect(screen.getByTestId("student-report")).toBeInTheDocument();
    expect(screen.queryByTestId("instructor-report")).not.toBeInTheDocument();
  });

  it("기본은 클립 탭이고, initialTab=report 면 리포트 탭으로 펼친다", () => {
    const clipFirst = render(<ReportScreen lectureId="s1" />);

    // 내 강의실에서 들어오면 먼저 보고 싶은 것은 다시 보기다.
    expect(screen.getByTestId("instructor-clip")).toBeInTheDocument();
    expect(screen.queryByTestId("instructor-report")).not.toBeInTheDocument();
    clipFirst.unmount();

    // 퀴즈에서 돌아올 때 쓰는 경로다. 클립 탭으로 떨어지면 보던 자리를 다시 찾아 들어가야 한다.
    render(<ReportScreen lectureId="s1" initialTab="report" />);

    expect(screen.getByTestId("instructor-report")).toBeInTheDocument();
    expect(screen.queryByTestId("instructor-clip")).not.toBeInTheDocument();
  });

  it("제목과 시각을 세션 응답에서 읽는다 — fixture 로 흘러내리지 않는다", () => {
    role.lecture = lectureOf("0123456789", { title: "예외 처리와 응답 코드" });

    render(<ReportScreen lectureId="0123456789" />);

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("예외 처리와 응답 코드");
    // 길이는 시작·종료 시각에서 계산한다. 날짜·시각도 박제된 "(목) 14:00" 이 아니다.
    expect(screen.getByText(/2시간 5분 \| 2026\.08\.04 .* 14:00/)).toBeInTheDocument();
  });

  it("세션 id 를 카드에 그대로 넘긴다", () => {
    role.lecture = lectureOf("0123456789");

    render(<ReportScreen lectureId="0123456789" />);
    openReportTab();

    expect(screen.getByTestId("instructor-report")).toHaveTextContent("0123456789");
  });

  it("역할을 확인하는 동안에는 어느 쪽도 짐작하지 않는다", () => {
    role.status = "loading";
    role.role = null;
    role.lecture = null;

    render(<ReportScreen lectureId="s1" />);

    // 잘못 고르면 학생이 집단 경로를 불러 403 을 받는다. 탭 자체를 내지 않는다.
    expect(screen.queryByTestId("instructor-report")).not.toBeInTheDocument();
    expect(screen.queryByTestId("student-report")).not.toBeInTheDocument();
    expect(screen.getByText(/불러오는 중이에요/)).toBeInTheDocument();
  });

  it("서버가 답하기 전에는 기다림을 실패로 말하지 않는다", () => {
    role.status = "loading";
    role.role = null;
    role.lecture = null;

    render(<ReportScreen lectureId="s1" />);

    // 아직 모르는 것을 "분석이 끝나지 않았어요" 로 말하면 기다림이 실패로 읽힌다.
    expect(screen.queryByText(/아직 분석이 끝나지 않았어요/)).not.toBeInTheDocument();
    expect(screen.queryByText(/결과를 생성하지 못했어요/)).not.toBeInTheDocument();
    expect(screen.getByText(/불러오는 중이에요/)).toBeInTheDocument();
  });

  it("역할을 알 수 없으면 이유를 알린다", () => {
    role.status = "unknown";
    role.role = null;
    role.lecture = null;

    render(<ReportScreen lectureId="s1" />);

    expect(screen.getByText(/리포트를 볼 수 없어요/)).toBeInTheDocument();
  });

  it("학생으로 확정된 경우에만 학생 클립 패널을 그린다", () => {
    role.role = "STUDENT";
    role.lecture = lectureOf("s4", { role: "STUDENT" });

    render(<ReportScreen lectureId="s4" />);

    expect(screen.getByTestId("student-clip")).toHaveTextContent("s4");
    // 강사 패널을 그리면 학생이 강사 경로를 불러 403 만 받는다.
    expect(screen.queryByTestId("instructor-clip")).not.toBeInTheDocument();
  });

  it("강사로 확정된 경우에만 강사 클립 패널을 그린다", () => {
    render(<ReportScreen lectureId="s1" />);

    // 목업(258)은 308 이 걷어냈다. 강사도 실제 녹화·전사를 강사 엔드포인트로 받는다.
    expect(screen.getByTestId("instructor-clip")).toHaveTextContent("s1");
    expect(screen.queryByTestId("student-clip")).not.toBeInTheDocument();
  });

  it("수업 요약 카드는 학생과 강사가 같은 것을 쓴다", () => {
    role.role = "STUDENT";
    role.lecture = lectureOf("s4", { role: "STUDENT" });
    const asStudent = render(<ReportScreen lectureId="s4" />);
    expect(asStudent.getByTestId("session-summary")).toHaveTextContent("s4");
    asStudent.unmount();

    // 클립 패널은 역할대로 갈리지만 요약 카드는 학생과 같은 것을 쓴다 — 요약은 공통 산출물이다.
    role.role = "INSTRUCTOR";
    role.lecture = lectureOf("s4");
    const asInstructor = render(<ReportScreen lectureId="s4" />);
    expect(asInstructor.getByTestId("session-summary")).toHaveTextContent("s4");
  });

  it("역할을 확인하는 동안 어느 클립 패널도 먼저 보여주지 않는다", () => {
    role.status = "loading";
    role.role = null;
    role.lecture = null;

    render(<ReportScreen lectureId="s4" />);

    // 잘못 고르면 학생이 강사 경로를(또는 그 반대를) 불러 403 을 받는다.
    expect(screen.queryByTestId("instructor-clip")).not.toBeInTheDocument();
    expect(screen.queryByTestId("student-clip")).not.toBeInTheDocument();
    expect(screen.getByText(/불러오는 중이에요/)).toBeInTheDocument();
  });

  it("역할을 알 수 없으면 클립 패널을 남기지 않는다", () => {
    role.status = "unknown";
    role.role = null;
    role.lecture = null;

    render(<ReportScreen lectureId="s4" />);

    // 로딩과 달리 이 상태는 지나가지 않는다. 짐작으로 고른 패널이 영구히 남으면 안 된다.
    expect(screen.queryByTestId("instructor-clip")).not.toBeInTheDocument();
    expect(screen.queryByTestId("student-clip")).not.toBeInTheDocument();
    expect(screen.getByText(/리포트를 볼 수 없어요/)).toBeInTheDocument();
  });

  it("리포트 처리가 끝나지 않은 수업은 탭과 본문을 감춘다", () => {
    role.lecture = lectureOf("s1", { reportStatus: "PROCESSING" });

    render(<ReportScreen lectureId="s1" />);

    expect(screen.getByText("아직 분석이 끝나지 않았어요")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /리포트/ })).not.toBeInTheDocument();
  });

  it("진행 중인 수업도 마찬가지다", () => {
    role.lecture = lectureOf("s1", { status: "LIVE" });

    render(<ReportScreen lectureId="s1" />);

    expect(screen.getByText("아직 분석이 끝나지 않았어요")).toBeInTheDocument();
  });

  it("분석이 실패한 수업은 실패 안내를 낸다", () => {
    role.lecture = lectureOf("s1", { reportStatus: "FAILED" });

    render(<ReportScreen lectureId="s1" />);

    expect(screen.getByText("결과를 생성하지 못했어요")).toBeInTheDocument();
  });
});
