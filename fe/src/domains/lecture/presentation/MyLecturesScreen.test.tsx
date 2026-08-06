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
  it("진행강의 카드에는 들어온 인원 수가 보인다", async () => {
    render(
      <MyLecturesScreen
        requestList={listing([summary({ role: "INSTRUCTOR", participantCount: 24 })])}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "진행강의" }));

    expect(await screen.findByText("인원 24명")).toBeVisible();
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

  /**
   * 하루에 수업을 둘 이상 한 사람에게는 날짜만으로 순서가 정해지지 않는다. 시:분까지 봐야 카드가 매번 같은 자리에 온다.
   *
   * <p>날짜를 "오늘" 로 만드는 이유: 캘린더가 오늘이 속한 달에서 시작하므로, 고정 날짜를 쓰면 실행하는 달에 따라 칩이 보이지 않는다.
   */
  describe("같은 날 수업 정렬", () => {
    const at = (hour: number) => new Date(new Date().setHours(hour, 0, 0, 0)).toISOString();

    const twoOnOneDay = () =>
      listing([
        summary({ sessionId: "1", title: "1교시", startedAt: at(9), endedAt: at(10) }),
        summary({ sessionId: "2", title: "3교시", startedAt: at(15), endedAt: at(16) }),
      ]);

    const titles = () => screen.getAllByText(/교시/).map((el) => el.textContent);

    it("최신순에서는 늦게 시작한 수업이 위에 온다", async () => {
      render(<MyLecturesScreen requestList={twoOnOneDay()} />);
      await screen.findByText("1교시");

      expect(titles()).toEqual(["3교시", "1교시"]);
    });

    it("오래된순으로 바꾸면 같은 날 안에서도 순서가 뒤집힌다", async () => {
      render(<MyLecturesScreen requestList={twoOnOneDay()} />);
      await screen.findByText("1교시");

      fireEvent.click(screen.getByRole("button", { name: "최신순" }));

      expect(titles()).toEqual(["1교시", "3교시"]);
    });

    it("검색으로 걸러낸 뒤에도 정렬이 유지된다", async () => {
      render(
        <MyLecturesScreen
          requestList={listing([
            summary({ sessionId: "1", title: "1교시", startedAt: at(9), endedAt: at(10) }),
            summary({ sessionId: "2", title: "특강", startedAt: at(12), endedAt: at(13) }),
            summary({ sessionId: "3", title: "3교시", startedAt: at(15), endedAt: at(16) }),
          ])}
        />,
      );
      await screen.findByText("1교시");

      fireEvent.change(screen.getByPlaceholderText("강의 제목 검색"), {
        target: { value: "교시" },
      });

      expect(titles()).toEqual(["3교시", "1교시"]);
    });

    /** 캘린더는 정렬 토글을 따르지 않는다. 칸 안에서는 늘 늦게 시작한 수업이 위다. */
    it("캘린더의 같은 날 칸은 정렬 토글과 무관하게 최신순으로 쌓인다", async () => {
      render(<MyLecturesScreen requestList={twoOnOneDay()} />);
      await screen.findByText("1교시");

      fireEvent.click(screen.getByRole("button", { name: "최신순" }));
      fireEvent.click(screen.getByRole("button", { name: "캘린더 보기" }));

      expect(titles()).toEqual(["3교시", "1교시"]);
    });

    /** 시각을 읽을 수 없다고 수업이 없었던 것은 아니다. 분석 실패 강의를 남기는 원칙과 같다. */
    it("시작 시각을 읽을 수 없는 수업도 목록에 남는다", async () => {
      render(
        <MyLecturesScreen
          requestList={listing([
            summary({ sessionId: "1", title: "정상 수업", startedAt: at(9), endedAt: at(10) }),
            summary({ sessionId: "2", title: "시각이 깨진 수업", startedAt: "", endedAt: null }),
          ])}
        />,
      );

      expect(await screen.findByText("시각이 깨진 수업")).toBeVisible();
    });
  });
});
