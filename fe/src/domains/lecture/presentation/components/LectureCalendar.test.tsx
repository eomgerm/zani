import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { LectureCalendar } from "./LectureCalendar";
import type { MyLecture } from "../myLectures";

const lecture = (over: Partial<MyLecture> = {}): MyLecture => ({
  id: "l1",
  title: "CS 네트워크 기초",
  date: "2026-07-10",
  role: "student",
  status: "COMPLETED",
  dur: "1시간 12분",
  students: 12,
  instructor: "박서준",
  rejoinable: false,
  ...over,
});

/** 달력이 "오늘이 속한 달"에서 시작하므로, 시각을 고정하지 않으면 실행하는 날에 따라 결과가 달라진다. */
beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date(2026, 6, 15, 9, 0, 0));
});

afterEach(() => {
  vi.useRealTimers();
  cleanup();
});

describe("LectureCalendar", () => {
  /** 시연용으로 굳어 있던 달을 걷어냈다. 방금 한 수업이 안 보이는 달에서 시작하면 안 된다. */
  it("starts on the current month", () => {
    render(<LectureCalendar lectures={[lecture()]} />);

    expect(screen.getByText("2026년 7월")).toBeVisible();
  });

  it("follows the clock into the next month", () => {
    vi.setSystemTime(new Date(2026, 7, 3, 9, 0, 0));

    render(<LectureCalendar lectures={[]} />);

    expect(screen.getByText("2026년 8월")).toBeVisible();
  });

  it("moves a month at a time in both directions", () => {
    render(<LectureCalendar lectures={[]} />);

    fireEvent.click(screen.getByRole("button", { name: "다음 달" }));
    expect(screen.getByText("2026년 8월")).toBeVisible();

    fireEvent.click(screen.getByRole("button", { name: "이전 달" }));
    expect(screen.getByText("2026년 7월")).toBeVisible();
  });

  it("rolls over the year at December and January", () => {
    render(<LectureCalendar lectures={[]} />);

    const next = screen.getByRole("button", { name: "다음 달" });
    for (let i = 0; i < 5; i += 1) fireEvent.click(next);
    expect(screen.getByText("2026년 12월")).toBeVisible();

    fireEvent.click(next);
    expect(screen.getByText("2027년 1월")).toBeVisible();

    fireEvent.click(screen.getByRole("button", { name: "이전 달" }));
    expect(screen.getByText("2026년 12월")).toBeVisible();
  });

  it("only shows lectures that fall in the displayed month", () => {
    render(
      <LectureCalendar
        lectures={[lecture(), lecture({ id: "l2", title: "8월 강의", date: "2026-08-03" })]}
      />,
    );

    expect(screen.getByText("CS 네트워크 기초")).toBeVisible();
    expect(screen.queryByText("8월 강의")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "다음 달" }));

    expect(screen.getByText("8월 강의")).toBeVisible();
    expect(screen.queryByText("CS 네트워크 기초")).not.toBeInTheDocument();
  });

  it("links completed lectures to the report and live ones to the room", () => {
    render(
      <LectureCalendar
        lectures={[lecture(), lecture({ id: "l2", title: "라이브 강의", status: "LIVE" })]}
      />,
    );

    expect(screen.getByRole("link", { name: "CS 네트워크 기초" })).toHaveAttribute(
      "href",
      "/my-lectures/l1/report",
    );
    expect(screen.getByRole("link", { name: "라이브 강의" })).toHaveAttribute("href", "/room/l2");
  });

  it("renders a lecture still being analysed as plain text, not a link", () => {
    render(<LectureCalendar lectures={[lecture({ status: "PROCESSING" })]} />);

    expect(screen.getByText("CS 네트워크 기초")).toBeVisible();
    expect(screen.queryByRole("link", { name: "CS 네트워크 기초" })).not.toBeInTheDocument();
  });

  /** 오늘 자리를 색으로만 알리면 눈으로 보지 않는 사람에게는 아무 표시가 없는 것과 같다. */
  it("marks today, and only while the current month is displayed", () => {
    render(<LectureCalendar lectures={[]} />);

    expect(screen.getByText("15")).toHaveAttribute("aria-current", "date");

    fireEvent.click(screen.getByRole("button", { name: "다음 달" }));

    expect(screen.getByText("15")).not.toHaveAttribute("aria-current");
  });

  /** 상태 점만 찍어 두면 색이 무엇을 뜻하는지 알 길이 없다. 실패도 목록처럼 캘린더에 남는다. */
  it("explains every status colour it can show", () => {
    render(<LectureCalendar lectures={[]} />);

    for (const label of ["분석완료", "분석 중", "분석 전", "분석 실패"]) {
      expect(screen.getByText(label)).toBeVisible();
    }
  });

  it("keeps a failed lecture on the calendar", () => {
    render(<LectureCalendar lectures={[lecture({ status: "FAILED" })]} />);

    expect(screen.getByText("CS 네트워크 기초")).toBeVisible();
  });

  it("does not cap how many lectures a day can show", () => {
    render(
      <LectureCalendar
        lectures={[
          lecture({ id: "a", title: "1교시" }),
          lecture({ id: "b", title: "2교시" }),
          lecture({ id: "c", title: "3교시" }),
        ]}
      />,
    );

    expect(screen.getByText("1교시")).toBeVisible();
    expect(screen.getByText("2교시")).toBeVisible();
    expect(screen.getByText("3교시")).toBeVisible();
  });
});
