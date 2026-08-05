import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

// jsdom 에는 ResizeObserver 가 없다. 리포트 탭이 품은 EvalDonuts(recharts)가 마운트되며
// 참조하므로 없으면 렌더가 통째로 터진다. 크기를 재는 것이 이 테스트의 관심사는 아니다.
vi.stubGlobal(
  "ResizeObserver",
  class {
    observe() {}
    unobserve() {}
    disconnect() {}
  },
);

const role = vi.hoisted(() => ({
  status: "ready" as "loading" | "ready" | "unknown",
  role: "INSTRUCTOR" as "INSTRUCTOR" | "STUDENT" | null,
}));
vi.mock("./useSessionRole", () => ({ useSessionRole: () => role }));

// report 도메인 카드들은 여기서 검증할 대상이 아니다. 어느 쪽이 렌더됐고 무엇이 전달됐는지만 본다.
// 이 파일이 보는 것은 ReportScreen 의 배치(어느 탭에 무엇이 걸리는지)다. 카드 안쪽은 각자의
// 테스트가 본다 — 여기서는 조회하지 않는 껍데기로 바꿔 끼운다.
vi.mock("@/domains/report", () => ({
  GroupAttentionTimeline: ({ sessionId }: { sessionId: string }) => (
    <div data-testid="group-timeline">{sessionId}</div>
  ),
  StudentAttentionTimeline: ({ sessionId }: { sessionId: string }) => (
    <div data-testid="student-timeline">{sessionId}</div>
  ),
  StudentReportClip: ({ sessionId }: { sessionId: string }) => (
    <div data-testid="student-clip">{sessionId}</div>
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

/** 강사용 목업 패널에만 있는 박제된 재생 시간. 목업이 그려졌는지 가리는 표식으로 쓴다. */
const MOCK_CLIP_MARKER = "42:30 / 2:05:30";

beforeEach(() => {
  role.status = "ready";
  role.role = "INSTRUCTOR";
});

describe("ReportScreen", () => {
  it("renders the group timeline for an instructor", () => {
    render(<ReportScreen lectureId="s1" />);
    openReportTab();

    expect(screen.getByTestId("group-timeline")).toBeInTheDocument();
    expect(screen.queryByTestId("student-timeline")).not.toBeInTheDocument();
  });

  it("follows the server role, not the fixture", () => {
    // fixture 의 s1 은 강사 강의지만 서버가 학생이라고 답했다. 서버가 이긴다.
    role.role = "STUDENT";

    render(<ReportScreen lectureId="s1" />);
    openReportTab();

    expect(screen.getByTestId("student-timeline")).toBeInTheDocument();
    expect(screen.queryByTestId("group-timeline")).not.toBeInTheDocument();
  });

  it("passes the session id straight through to the card", () => {
    render(<ReportScreen lectureId="0123456789" />);
    openReportTab();

    expect(screen.getByTestId("group-timeline")).toHaveTextContent("0123456789");
  });

  it("does not guess a role while it is still loading", () => {
    role.status = "loading";
    role.role = null;

    render(<ReportScreen lectureId="s1" />);
    openReportTab();

    // 잘못 고르면 학생이 집단 경로를 불러 403 을 받는다. 어느 쪽도 그리지 않는다.
    expect(screen.queryByTestId("group-timeline")).not.toBeInTheDocument();
    expect(screen.queryByTestId("student-timeline")).not.toBeInTheDocument();
  });

  it("explains when the role cannot be determined", () => {
    role.status = "unknown";
    role.role = null;

    render(<ReportScreen lectureId="s1" />);
    openReportTab();

    expect(screen.getByText(/리포트를 볼 수 없어요/)).toBeInTheDocument();
  });

  it("renders the real clip panel only for a confirmed student", () => {
    role.role = "STUDENT";

    render(<ReportScreen lectureId="s4" />);

    expect(screen.getByTestId("student-clip")).toHaveTextContent("s4");
  });

  it("keeps the mock clip panel for an instructor", () => {
    render(<ReportScreen lectureId="s1" />);

    // 강사 클립 탭은 강사 리포트 API 가 생길 때까지 목업이다.
    expect(screen.getByText(MOCK_CLIP_MARKER)).toBeInTheDocument();
    expect(screen.queryByTestId("student-clip")).not.toBeInTheDocument();
  });

  it("does not show the mock clip panel while the role is still loading", () => {
    role.status = "loading";
    role.role = null;

    render(<ReportScreen lectureId="s4" />);

    // 목업을 먼저 보여 주면 학생이 남의 강의 전사를 자기 수업으로 읽는다. 어느 쪽도 그리지 않는다.
    expect(screen.queryByText(MOCK_CLIP_MARKER)).not.toBeInTheDocument();
    expect(screen.queryByTestId("student-clip")).not.toBeInTheDocument();
    expect(screen.getByText(/불러오는 중이에요/)).toBeInTheDocument();
  });

  it("does not leave the mock clip panel up when the role cannot be determined", () => {
    role.status = "unknown";
    role.role = null;

    render(<ReportScreen lectureId="s4" />);

    // 로딩과 달리 이 상태는 지나가지 않는다. 가짜가 영구히 남으면 안 된다.
    expect(screen.queryByText(MOCK_CLIP_MARKER)).not.toBeInTheDocument();
    expect(screen.getByText(/리포트를 볼 수 없어요/)).toBeInTheDocument();
  });
});
