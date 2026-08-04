import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { SectionTimeline } from "./SectionTimeline";

const sections = [
  { startSeconds: 0, endSeconds: 372, title: "함수의 정의", focusLevel: 3.21 },
  { startSeconds: 372, endSeconds: 900, title: "합성 함수", focusLevel: 2.5 },
];

describe("SectionTimeline", () => {
  it("구간마다 번호·제목·시각·평균을 보여준다", () => {
    render(<SectionTimeline sections={sections} selectedIndex={0} onSelect={() => {}} />);

    expect(screen.getByText("구간 1")).toBeInTheDocument();
    expect(screen.getByText("구간 2")).toBeInTheDocument();
    expect(screen.getByText("함수의 정의")).toBeInTheDocument();
    expect(screen.getByText("3.21")).toBeInTheDocument();
    expect(screen.getByText("2.50")).toBeInTheDocument();
  });

  /** 경계가 10분 같은 고정 길이가 아니라 서버가 준 실제 시각이어야 한다. */
  it("고정 길이가 아닌 실제 시각을 쓴다", () => {
    render(<SectionTimeline sections={sections} selectedIndex={0} onSelect={() => {}} />);

    // 372초 = 06:12
    expect(screen.getAllByText(/06:12/).length).toBeGreaterThan(0);
  });

  it("값이 없는 구간은 1단계가 아니라 값 없음으로 보인다", () => {
    render(
      <SectionTimeline
        sections={[{ startSeconds: 0, endSeconds: 90, title: "쉬는 시간", focusLevel: null }]}
        selectedIndex={0}
        onSelect={() => {}}
      />,
    );

    expect(screen.getAllByText("값 없음").length).toBeGreaterThan(0);
    expect(screen.queryByText("1.00")).not.toBeInTheDocument();
  });

  it("퍼센트 기호를 쓰지 않는다 — 1~4 척도다", () => {
    const { container } = render(
      <SectionTimeline sections={sections} selectedIndex={0} onSelect={() => {}} />,
    );

    expect(container.textContent).not.toContain("%");
  });

  it("구간이 없으면 그 사실을 알린다 — 248 미완 세션", () => {
    render(<SectionTimeline sections={[]} selectedIndex={0} onSelect={() => {}} />);

    expect(screen.getByText(/수업 내용 구간이 아직 없어요/)).toBeInTheDocument();
  });

  it("구간을 누르면 그 번호를 알린다", () => {
    const onSelect = vi.fn();
    render(<SectionTimeline sections={sections} selectedIndex={0} onSelect={onSelect} />);

    fireEvent.click(screen.getByRole("button", { name: /구간 2/ }));

    expect(onSelect).toHaveBeenCalledWith(1);
  });

  /** 차트 SVG 는 키보드로 짚을 수 없어 구간 이동을 카드가 맡는다(NFR-UX-007). */
  it("화살표·Home·End 로 구간을 옮긴다", () => {
    const onSelect = vi.fn();
    const { container } = render(
      <SectionTimeline sections={sections} selectedIndex={0} onSelect={onSelect} />,
    );
    const list = container.querySelector("ul");

    fireEvent.keyDown(list!, { key: "ArrowRight" });
    expect(onSelect).toHaveBeenLastCalledWith(1);

    fireEvent.keyDown(list!, { key: "End" });
    expect(onSelect).toHaveBeenLastCalledWith(1);
  });

  it("고른 구간만 탭 순서에 남긴다 — roving tabindex", () => {
    render(<SectionTimeline sections={sections} selectedIndex={1} onSelect={() => {}} />);

    expect(screen.getByRole("button", { name: /구간 2/ })).toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("button", { name: /구간 1/ })).toHaveAttribute("tabindex", "-1");
  });

  it("고른 구간을 읽어 준다", () => {
    const { container } = render(
      <SectionTimeline sections={sections} selectedIndex={1} onSelect={() => {}} />,
    );

    const live = container.querySelector("[aria-live='polite']");
    expect(live?.textContent).toContain("합성 함수");
  });

  /** 이 문구가 없으면 학생이 성적표로 읽는다(NFR-UX-006). */
  it("참고용 지표라는 것을 적는다", () => {
    render(<SectionTimeline sections={sections} selectedIndex={0} onSelect={() => {}} />);

    expect(screen.getByText(/1~4 단계 평균/)).toBeInTheDocument();
  });
});
