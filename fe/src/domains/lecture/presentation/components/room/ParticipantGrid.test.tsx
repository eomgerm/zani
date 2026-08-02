import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { ParticipantGrid, TILE_FIT, columnsFor } from "./ParticipantGrid";

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

/**
 * 열 수는 인원에 따라 달라진다. Meet·Webex 처럼 적은 인원에서 타일이 커야 얼굴이 보인다.
 *
 * <p>혼자일 때 1열이어야 한다는 게 핵심이다. 고정 4열이면 혼자 있을 때 타일이 좌상단에 작게 남는다.
 */
describe("columnsFor", () => {
  it.each([
    [1, 1],
    [2, 2],
    [4, 2],
    [5, 3],
    [9, 3],
    [10, 4],
    [12, 4],
  ])("인원 %i명이면 %i열", (count, expected) => {
    expect(columnsFor(count, false)).toBe(expected);
  });

  /** 사이드 패널이 열리면 폭이 좁아, 4열은 타일이 너무 납작해진다. */
  it("좁은 폭에서는 3열까지만 쓴다", () => {
    expect(columnsFor(12, true)).toBe(3);
    expect(columnsFor(2, true)).toBe(2);
  });
});

describe("ParticipantGrid", () => {
  /**
   * 타일은 16:9 를 유지하되 칸을 넘지 않아야 한다.
   *
   * <p>`aspect-ratio` 만 주면 폭을 꽉 채운 뒤 높이가 넘쳐 잘린다. 그래서 칸 높이에서 폭 상한을 거꾸로 계산한다. jsdom 은 `cqh` 를 계산하지 않으므로 값이 붙었는지까지만 확인한다.
   */
  it("타일에 16:9 비율과 칸 높이 기준 폭 상한을 준다", () => {
    render(<ParticipantGrid participants={many(1)} />);

    // 비율은 DOM 에 남으므로 타일에 실제로 붙었다는 증거가 된다.
    expect(screen.getByRole("group", { name: /참가자 0,/ })).toHaveStyle({
      aspectRatio: "16 / 9",
    });
    // 폭 상한은 jsdom 이 `cqh` 를 못 읽어 style 에서 지워지므로, 내려보내는 값으로 확인한다.
    expect(TILE_FIT.width).toBe("min(100%, calc(100cqh * 16 / 9))");
  });

  /** 칸이 `cqh` 의 기준이 되어야 폭 상한이 계산된다. 이게 빠지면 상한이 무시돼 타일이 잘린다. */
  it("타일을 감싼 칸이 크기 컨테이너다", () => {
    render(<ParticipantGrid participants={many(1)} />);

    const cell = screen.getByRole("group", { name: /참가자 0,/ }).parentElement;
    expect(cell?.className).toContain("[container-type:size]");
  });

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
    expect(screen.getAllByRole("button", { name: "참가자 1 퇴장" })).toHaveLength(1);
    expect(screen.queryByRole("button", { name: "참가자 0 음소거" })).not.toBeInTheDocument();
  });
});
