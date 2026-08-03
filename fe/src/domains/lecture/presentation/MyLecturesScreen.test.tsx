import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const authState = vi.hoisted(() => ({ accessToken: "access-token" as string | null }));
vi.mock("@/domains/auth", () => ({ useAuth: () => authState }));

import {
  SessionListRequestError,
  type SessionSummary,
} from "@/domains/lecture/infrastructure/sessionListApi";
import { MyLecturesScreen } from "./MyLecturesScreen";

const summary = (over: Partial<SessionSummary> = {}): SessionSummary => ({
  sessionId: "100",
  inviteCode: "AB12CD34",
  title: "자료구조 3주차",
  instructorName: "박서준",
  status: "ENDED",
  role: "STUDENT",
  startedAt: "2026-08-03T09:00:00Z",
  endedAt: "2026-08-03T10:00:00Z",
  participantCount: 12,
  reportStatus: "COMPLETED",
  rejoinable: false,
  ...over,
});

const listing = (summaries: SessionSummary[]) => vi.fn(async () => summaries);

afterEach(() => {
  cleanup();
  authState.accessToken = "access-token";
});

describe("MyLecturesScreen", () => {
  it("참여강의 탭에는 학생으로 들은 수업만 보인다", async () => {
    render(
      <MyLecturesScreen
        requestList={listing([
          summary({ sessionId: "1", title: "학생으로 들은 수업" }),
          summary({ sessionId: "2", title: "내가 연 수업", role: "INSTRUCTOR" }),
        ])}
      />,
    );

    expect(await screen.findByText("학생으로 들은 수업")).toBeVisible();
    expect(screen.queryByText("내가 연 수업")).not.toBeInTheDocument();
  });

  /**
   * 나갔다가 돌아오는 것이 이 화면의 주된 쓰임이다. 프리조인을 다시 거치지 않고 강의실로 바로 간다.
   */
  it("진행 중이고 재입장 가능하면 강의실로 바로 가는 입장 버튼이 있다", async () => {
    render(
      <MyLecturesScreen
        requestList={listing([
          summary({ sessionId: "77", title: "진행 중 수업", status: "LIVE", endedAt: null, rejoinable: true }),
        ])}
      />,
    );

    const enter = await screen.findByRole("link", { name: "진행 중 수업 강의실 입장" });
    expect(enter).toHaveAttribute("href", "/room/77");
  });

  it("재입장할 수 없으면 입장 버튼이 없다", async () => {
    render(
      <MyLecturesScreen
        requestList={listing([
          summary({ title: "진행 중 수업", status: "LIVE", endedAt: null, rejoinable: false }),
        ])}
      />,
    );

    await screen.findByText("진행 중 수업");
    expect(screen.queryByRole("link", { name: /강의실 입장/ })).not.toBeInTheDocument();
  });

  /** 내가 들은 수업 카드는 "누구 수업인지" 를 보여준다. 251 에서 instructorName 을 서버에 추가한 이유가 이것이다. */
  it("참여강의 카드에는 강사 이름이 보인다", async () => {
    render(
      <MyLecturesScreen requestList={listing([summary({ instructorName: "최민서" })])} />,
    );

    expect(await screen.findByText("최민서")).toBeVisible();
  });

  /** 내가 연 수업은 강사가 나이므로 이름 대신 몇 명이 들었는지가 궁금하다. */
  it("진행강의 카드에는 수강생 수가 보인다", async () => {
    render(
      <MyLecturesScreen
        requestList={listing([summary({ role: "INSTRUCTOR", participantCount: 24 })])}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "진행강의" }));

    expect(await screen.findByText("수강생 24명")).toBeVisible();
  });

  /** 리포트가 실패했다고 수업이 없었던 것은 아니다. 목록에서 지우면 강사는 자기 수업이 사라진 것으로 본다. */
  it("분석에 실패한 수업도 목록에 남는다", async () => {
    render(
      <MyLecturesScreen
        requestList={listing([summary({ title: "실패한 수업", reportStatus: "FAILED" })])}
      />,
    );

    expect(await screen.findByText("실패한 수업")).toBeVisible();
  });

  /** 실패를 빈 목록과 같은 문구로 보여주면 사용자는 자기 수업이 사라진 줄 안다. */
  it("조회에 실패하면 빈 목록이 아니라 오류를 알린다", async () => {
    const failing = vi.fn(async () => {
      throw new SessionListRequestError("boom", 500);
    });
    render(<MyLecturesScreen requestList={failing} />);

    await waitFor(() => expect(screen.getByRole("alert")).toBeVisible());
    expect(screen.queryByText("아직 참여한 수업이 없습니다.")).not.toBeInTheDocument();
  });

  it("수업이 없으면 빈 목록 안내를 보여준다", async () => {
    render(<MyLecturesScreen requestList={listing([])} />);

    expect(await screen.findByText("아직 참여한 수업이 없습니다.")).toBeVisible();
  });
});
