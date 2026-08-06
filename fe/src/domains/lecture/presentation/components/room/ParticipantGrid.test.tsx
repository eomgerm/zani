import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { createGrid } from "@thangdevalone/meeting-grid-layout-core";

import { ParticipantGrid } from "./ParticipantGrid";

const participant = (id: number) => ({
  id: `participant-${id}`,
  name: `참가자 ${id}`,
  color: "#2aa584",
  role: id === 0 ? ("instructor" as const) : ("student" as const),
  cameraEnabled: true,
  microphoneEnabled: true,
  handRaised: false,
  speaking: false,
});

const many = (count: number) => Array.from({ length: count }, (_, index) => participant(index));

afterEach(cleanup);

/**
 * 배치는 인원수만으로 정해지지 않는다 — 같은 인원이라도 컨테이너 비율이 달라지면 열·행이 바뀌어야 한다.
 *
 * <p>jsdom 은 레이아웃을 계산하지 않아 화면에서는 이걸 확인할 수 없다. 그래서 그리드가 쓰는 배치
 * 함수를 같은 인자로 직접 불러 계약만 못박는다. 예전 `columnsFor` 는 폭·높이를 아예 받지 않아
 * 아래 세 경우가 전부 같은 배치로 나왔다.
 */
describe("배치기", () => {
  const layout = (count: number, width: number, height: number) =>
    createGrid({ aspectRatio: "16:9", count, dimensions: { width, height }, gap: 12 });

  it("같은 인원이라도 컨테이너 비율에 따라 열·행이 달라진다", () => {
    // 넓고 낮은 창 — 옆으로 편다.
    expect(layout(6, 2530, 700)).toMatchObject({ cols: 3, rows: 2 });
    // 좁고 높은 창 — 아래로 쌓는다.
    expect(layout(6, 898, 1428)).toMatchObject({ cols: 2, rows: 3 });
  });

  it("사이드 패널이 열려 폭이 줄면 행을 늘린다", () => {
    expect(layout(12, 1252, 628)).toMatchObject({ cols: 4, rows: 3 });
    expect(layout(12, 898, 628)).toMatchObject({ cols: 3, rows: 4 });
  });

  /** 혼자일 때 타일이 좌상단에 작게 남으면 안 된다. */
  it("혼자면 화면을 꽉 채운다", () => {
    expect(layout(1, 1252, 628)).toMatchObject({ cols: 1, rows: 1 });
  });
});

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

  it("shows only the remaining participants on a short last page", () => {
    render(<ParticipantGrid participants={many(18)} />);

    fireEvent.click(screen.getByRole("button", { name: "다음 페이지" }));

    // 한 사람당 타일 하나다. 남는 칸을 앞 참가자로 채우면 인원을 오해하게 된다.
    expect(screen.getAllByRole("group", { name: /카메라 켜짐/ })).toHaveLength(6);
    expect(screen.queryByRole("group", { name: /참가자 0,/ })).not.toBeInTheDocument();
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

    expect(screen.getAllByRole("button", { name: "참가자 1 음소거" })).toHaveLength(1);
    // 퇴장 버튼은 어디에도 없어야 한다(티켓 246).
    expect(screen.queryByRole("button", { name: "참가자 1 퇴장" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "참가자 0 음소거" })).not.toBeInTheDocument();
  });
});
