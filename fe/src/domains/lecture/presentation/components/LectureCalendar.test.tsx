import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { LectureCalendar } from "./LectureCalendar";
import type { Lecture } from "../fixtures";

const lecture = (over: Partial<Lecture> = {}): Lecture => ({
  id: "l1",
  title: "CS 네트워크 기초",
  date: "2026-07-10",
  role: "student",
  status: "COMPLETED",
  dur: "1시간 12분",
  ...over,
});

afterEach(cleanup);

describe("LectureCalendar", () => {
  it("starts on the seeded month", () => {
    render(<LectureCalendar lectures={[lecture()]} />);

    expect(screen.getByText("2026년 7월")).toBeVisible();
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
