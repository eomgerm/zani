import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { TranscriptSegment } from "../infrastructure/studentReportApi";
import { TranscriptTimeline } from "./TranscriptTimeline";

const segments: TranscriptSegment[] = [
  { startSeconds: 2, endSeconds: 30, speakerName: "박서준", text: "오늘은 상태 관리를 다룹니다." },
  { startSeconds: 125, endSeconds: 150, speakerName: "정하윤", text: "Context 는 언제 쓰나요?" },
  { startSeconds: 400, endSeconds: 430, speakerName: "박서준", text: "리렌더링 원리를 봅시다." },
];

describe("TranscriptTimeline", () => {
  it("실명 화자와 본문, 시각을 그대로 그린다", () => {
    render(<TranscriptTimeline segments={segments} currentSeconds={0} onSeek={() => {}} />);

    // 같은 화자가 여러 번 발화한다 — 행마다 실명이 반복해서 붙는다.
    expect(screen.getAllByText("박서준")).toHaveLength(2);
    expect(screen.getByText("Context 는 언제 쓰나요?")).toBeInTheDocument();
    // 125초 → 02:05. 시각은 mm:ss 로 그린다.
    expect(screen.getByText("02:05")).toBeInTheDocument();
  });

  it("행을 누르면 그 발화의 시작 시각으로 이동을 요청한다", () => {
    const onSeek = vi.fn();
    render(<TranscriptTimeline segments={segments} currentSeconds={0} onSeek={onSeek} />);

    fireEvent.click(screen.getByRole("button", { name: /Context 는 언제 쓰나요/ }));

    expect(onSeek).toHaveBeenCalledWith(125);
  });

  it("재생 위치가 속한 행이 활성이다", () => {
    render(<TranscriptTimeline segments={segments} currentSeconds={126} onSeek={() => {}} />);

    expect(screen.getByRole("button", { name: /Context 는 언제 쓰나요/ })).toHaveAttribute(
      "aria-current",
      "true",
    );
    expect(screen.getByRole("button", { name: /상태 관리/ })).not.toHaveAttribute("aria-current");
  });

  it("발화 사이 틈에서는 직전 행을 유지한다 — 하이라이트가 깜빡이지 않는다", () => {
    render(<TranscriptTimeline segments={segments} currentSeconds={300} onSeek={() => {}} />);

    expect(screen.getByRole("button", { name: /Context 는 언제 쓰나요/ })).toHaveAttribute(
      "aria-current",
      "true",
    );
  });

  /**
   * jsdom 은 레이아웃을 계산하지 않아 모든 사각형이 0 이다. 활성 행이 목록 아래쪽에 있는 상황을
   * 직접 만들어 주고 `scrollTo` 를 지켜본다.
   *
   * <p>첫 렌더는 `currentSeconds=0`(활성 행 없음)으로 두어 스크롤이 일어나지 않게 한 뒤,
   * 사각형을 심고 재생 위치를 옮긴다.
   */
  const armScroll = (options: { listTop: number; rowTop: number; scrollTop: number }) => {
    const scrollTo = vi.fn();
    const view = render(
      <TranscriptTimeline segments={segments} currentSeconds={0} onSeek={() => {}} />,
    );

    const row = screen.getByRole("button", { name: /Context 는 언제 쓰나요/ });
    const list = row.parentElement as HTMLElement;

    list.scrollTo = scrollTo;
    Object.defineProperty(list, "scrollTop", { value: options.scrollTop, writable: true });
    list.getBoundingClientRect = () => ({ top: options.listTop }) as unknown as DOMRect;
    row.getBoundingClientRect = () => ({ top: options.rowTop }) as unknown as DOMRect;

    return {
      scrollTo,
      list,
      play: (seconds: number) =>
        view.rerender(
          <TranscriptTimeline segments={segments} currentSeconds={seconds} onSeek={() => {}} />,
        ),
    };
  };

  it("활성 행을 목록 위쪽으로 끌어올린다 — 화면 밖일 때만 움직이지 않는다", () => {
    const { scrollTo, play } = armScroll({ listTop: 200, rowTop: 500, scrollTop: 100 });

    play(126);

    // 현재 스크롤 100 + 컨테이너 기준 행 위치 300 - 위쪽 여백 8
    expect(scrollTo).toHaveBeenCalledWith({ top: 392, behavior: "smooth" });
  });

  it("포인터가 목록 위에 있으면 따라가지 않는다", () => {
    const { scrollTo, list, play } = armScroll({ listTop: 200, rowTop: 500, scrollTop: 100 });

    fireEvent.mouseEnter(list);
    play(126);

    // 읽는 중인 목록이 밑에서 끌려가면 원하는 행을 누를 수 없다.
    expect(scrollTo).not.toHaveBeenCalled();
  });

  it("포인터가 목록을 벗어나면 다시 따라간다", () => {
    const { scrollTo, list, play } = armScroll({ listTop: 200, rowTop: 500, scrollTop: 100 });

    fireEvent.mouseEnter(list);
    fireEvent.mouseLeave(list);
    play(126);

    expect(scrollTo).toHaveBeenCalledWith({ top: 392, behavior: "smooth" });
  });

  it("이미 그 자리면 스크롤을 건드리지 않는다 — 부드러운 이동이 재시작되지 않는다", () => {
    // 행이 이미 위쪽 여백만큼 아래에 있다(208 - 200 = 8).
    const { scrollTo, play } = armScroll({ listTop: 200, rowTop: 208, scrollTop: 0 });

    play(126);

    expect(scrollTo).not.toHaveBeenCalled();
  });

  it("움직임 줄이기를 켠 사용자에게는 즉시 이동한다", () => {
    const matchMedia = vi.fn().mockReturnValue({ matches: true });
    vi.stubGlobal("matchMedia", matchMedia);

    const { scrollTo, play } = armScroll({ listTop: 200, rowTop: 500, scrollTop: 100 });
    play(126);

    expect(matchMedia).toHaveBeenCalledWith("(prefers-reduced-motion: reduce)");
    expect(scrollTo).toHaveBeenCalledWith({ top: 392, behavior: "auto" });

    vi.unstubAllGlobals();
  });

  it("전사가 없으면 준비 전 안내를 그린다", () => {
    render(<TranscriptTimeline segments={[]} currentSeconds={0} onSeek={() => {}} />);

    expect(screen.getByText("전사가 아직 없어요")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
