import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { SectionTimeline } from "./SectionTimeline";

const sections = [
  { startSeconds: 0, endSeconds: 372, title: "함수의 정의", focusLevel: 3.21 },
  { startSeconds: 372, endSeconds: 900, title: "합성 함수", focusLevel: 1.8 },
];

describe("SectionTimeline", () => {
  it("구간마다 번호·제목·시각·평균을 보여준다", () => {
    render(<SectionTimeline sections={sections} selectedIndex={0} onSelect={() => {}} scopeLabel="내 집중도" />);

    expect(screen.getByText("구간 1")).toBeInTheDocument();
    expect(screen.getByText("구간 2")).toBeInTheDocument();
    expect(screen.getByText("함수의 정의")).toBeInTheDocument();
    // 점수는 정수로 반올림한다 — 30초 평균의 정밀도를 넘겨 읽히지 않게.
    expect(screen.getByText("3")).toBeInTheDocument();
    expect(screen.getByText("2")).toBeInTheDocument();
  });

  /** 경계가 10분 같은 고정 길이가 아니라 서버가 준 실제 시각이어야 한다. */
  it("고정 길이가 아닌 실제 시각을 쓴다", () => {
    render(<SectionTimeline sections={sections} selectedIndex={0} onSelect={() => {}} scopeLabel="내 집중도" />);

    // 372초 = 06:12
    expect(screen.getAllByText(/06:12/).length).toBeGreaterThan(0);
  });

  it("값이 없는 구간은 1단계가 아니라 값 없음으로 보인다", () => {
    render(
      <SectionTimeline
        sections={[{ startSeconds: 0, endSeconds: 90, title: "쉬는 시간", focusLevel: null }]}
        selectedIndex={0}
        onSelect={() => {}}
        scopeLabel="내 집중도"
      />,
    );

    expect(screen.getAllByText("값 없음").length).toBeGreaterThan(0);
    expect(screen.queryByText("1")).not.toBeInTheDocument();
  });

  it("퍼센트 기호를 쓰지 않는다 — 1~4 척도다", () => {
    const { container } = render(
      <SectionTimeline sections={sections} selectedIndex={0} onSelect={() => {}} scopeLabel="내 집중도" />,
    );

    expect(container.textContent).not.toContain("%");
  });

  it("구간이 없으면 그 사실을 알린다 — 248 미완 세션", () => {
    render(<SectionTimeline sections={[]} selectedIndex={0} onSelect={() => {}} scopeLabel="내 집중도" />);

    expect(screen.getByText(/수업 내용 구간이 아직 없어요/)).toBeInTheDocument();
  });

  it("구간을 누르면 그 번호를 알린다", () => {
    const onSelect = vi.fn();
    render(<SectionTimeline sections={sections} selectedIndex={0} onSelect={onSelect} scopeLabel="내 집중도" />);

    fireEvent.click(screen.getByRole("button", { name: /구간 2/ }));

    expect(onSelect).toHaveBeenCalledWith(1);
  });

  /** 차트 SVG 는 키보드로 짚을 수 없어 구간 이동을 카드가 맡는다(NFR-UX-007). */
  it("화살표·Home·End 로 구간을 옮긴다", () => {
    const onSelect = vi.fn();
    const { container } = render(
      <SectionTimeline sections={sections} selectedIndex={0} onSelect={onSelect} scopeLabel="내 집중도" />,
    );
    const list = container.querySelector("ul");

    fireEvent.keyDown(list!, { key: "ArrowRight" });
    expect(onSelect).toHaveBeenLastCalledWith(1);

    fireEvent.keyDown(list!, { key: "End" });
    expect(onSelect).toHaveBeenLastCalledWith(1);
  });

  it("고른 구간만 탭 순서에 남긴다 — roving tabindex", () => {
    render(<SectionTimeline sections={sections} selectedIndex={1} onSelect={() => {}} scopeLabel="내 집중도" />);

    expect(screen.getByRole("button", { name: /구간 2/ })).toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("button", { name: /구간 1/ })).toHaveAttribute("tabindex", "-1");
  });

  it("고른 구간을 읽어 준다", () => {
    const { container } = render(
      <SectionTimeline sections={sections} selectedIndex={1} onSelect={() => {}} scopeLabel="내 집중도" />,
    );

    const live = container.querySelector("[aria-live='polite']");
    expect(live?.textContent).toContain("합성 함수");
  });

  /** 카드를 누르면 그 구간의 점수와 한 줄 평을 상세로 보여준다. */
  it("구간을 누르면 상세가 열리고 점수를 4 만점으로 읽는다", () => {
    render(
      <SectionTimeline
        sections={sections}
        selectedIndex={0}
        onSelect={() => {}}
        scopeLabel="내 집중도"
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /구간 1/ }));

    const dialog = screen.getByRole("dialog");
    expect(dialog.textContent).toContain("구간 1 · 00:00~06:12");
    expect(dialog.textContent).toContain("/ 4");
    expect(dialog.textContent).toContain("높음");
  });

  it("상세의 클립 바로가기는 구간 시작 시각을 넘긴다", () => {
    const onJumpToClip = vi.fn();
    render(
      <SectionTimeline
        sections={sections}
        selectedIndex={0}
        onSelect={() => {}}
        scopeLabel="내 집중도"
        onJumpToClip={onJumpToClip}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /구간 2/ }));
    fireEvent.click(screen.getByRole("button", { name: /복습 클립 바로가기/ }));

    expect(onJumpToClip).toHaveBeenCalledWith(372);
  });

  it("배선이 없으면 클립 바로가기를 내지 않는다", () => {
    render(
      <SectionTimeline
        sections={sections}
        selectedIndex={0}
        onSelect={() => {}}
        scopeLabel="내 집중도"
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /구간 1/ }));

    expect(screen.queryByRole("button", { name: /복습 클립 바로가기/ })).not.toBeInTheDocument();
  });
});
