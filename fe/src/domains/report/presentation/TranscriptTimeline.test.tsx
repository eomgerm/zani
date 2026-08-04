import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { TranscriptSegment } from "../infrastructure/studentClipApi";
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

  it("전사가 없으면 준비 전 안내를 그린다", () => {
    render(<TranscriptTimeline segments={[]} currentSeconds={0} onSeek={() => {}} />);

    expect(screen.getByText("전사가 아직 없어요")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
