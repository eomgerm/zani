import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { ParticipantGrid } from "./ParticipantGrid";

const participant = (id: number) => ({
  id: `participant-${id}`,
  name: `참가자 ${id}`,
  color: "#2aa584",
  role: id === 0 ? ("instructor" as const) : ("student" as const),
  cameraEnabled: true,
  microphoneEnabled: true,
  handRaised: false,
});

const many = (count: number) => Array.from({ length: count }, (_, index) => participant(index));

afterEach(cleanup);

describe("ParticipantGrid", () => {
  it("exposes the total participant count regardless of how many fit on a page", () => {
    render(<ParticipantGrid participants={many(18)} />);

    expect(screen.getByRole("group", { name: "참가자 18명" })).toBeVisible();
  });

  it("renders exactly 12 tiles per page and pages through the rest", () => {
    render(<ParticipantGrid participants={many(18)} />);

    expect(screen.getAllByRole("group", { name: /카메라 켜짐/ })).toHaveLength(12);
    expect(screen.getByText("1/2")).toBeVisible();
    // 1페이지는 앞에서 12명.
    expect(screen.getByRole("group", { name: /참가자 11,/ })).toBeVisible();
    expect(screen.queryByRole("group", { name: /참가자 12,/ })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "다음 페이지" }));

    expect(screen.getByText("2/2")).toBeVisible();
    expect(screen.getByRole("group", { name: /참가자 12,/ })).toBeVisible();
  });

  it("refills a short last page from the start so the grid stays full", () => {
    render(<ParticipantGrid participants={many(18)} />);

    fireEvent.click(screen.getByRole("button", { name: "다음 페이지" }));

    // 남은 6명(12~17) 뒤는 앞에서부터 다시 채워 12칸을 유지한다.
    expect(screen.getAllByRole("group", { name: /카메라 켜짐/ })).toHaveLength(12);
    expect(screen.getAllByRole("group", { name: /참가자 0,/ })).toHaveLength(1);
  });

  it("hides the pager when everyone fits on one page", () => {
    render(<ParticipantGrid participants={many(12)} />);

    expect(screen.queryByRole("button", { name: "다음 페이지" })).not.toBeInTheDocument();
    expect(screen.getAllByRole("group", { name: /카메라 켜짐/ })).toHaveLength(12);
  });

  it("disables paging at both ends", () => {
    render(<ParticipantGrid participants={many(18)} />);

    expect(screen.getByRole("button", { name: "이전 페이지" })).toBeDisabled();

    fireEvent.click(screen.getByRole("button", { name: "다음 페이지" }));

    expect(screen.getByRole("button", { name: "다음 페이지" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "이전 페이지" })).toBeEnabled();
  });

  it("allows instructors to retain controls only for other students", () => {
    render(
      <ParticipantGrid
        currentParticipantId="instructor-1"
        isInstructor
        participants={[
          { ...participant(0), id: "instructor-1" },
          { ...participant(1), id: "student-1" },
        ]}
      />,
    );

    // 2명이 12칸을 순환 채우므로 같은 학생 타일이 여러 번 나온다.
    expect(screen.getAllByRole("button", { name: "참가자 1 음소거" })).toHaveLength(6);
    expect(screen.getAllByRole("button", { name: "참가자 1 퇴장" })).toHaveLength(6);
    expect(screen.queryByRole("button", { name: "참가자 0 음소거" })).not.toBeInTheDocument();
  });
});
