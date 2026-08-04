"use client";

import { useEffect, useRef } from "react";

import { PictoInbox } from "@/shared/ui";
import type { TranscriptSegment } from "../infrastructure/studentReportApi";
import { formatOffset } from "./offsetTime";
import { activeSegmentIndex } from "./transcriptCursor";

export interface TranscriptTimelineProps {
  readonly segments: readonly TranscriptSegment[];
  /** 플레이어의 현재 재생 위치(초). 이 값을 따라 활성 행이 이동한다. */
  readonly currentSeconds: number;
  /** 행을 누르면 그 발화의 시작 시각으로 이동을 요청한다. */
  readonly onSeek: (seconds: number) => void;
}

/**
 * 실명 화자 전사 패널(REPORT-S-001).
 *
 * <p>화자는 서버가 실명으로 풀어 내려준 표시 이름을 **그대로** 그린다. 여기서 별칭·이니셜로
 * 바꾸거나 참가자 목록과 대조하지 않는다 — 익명화 별칭은 파일 경로와 GMS 전송에만 존재한다.
 *
 * <p>행은 전부 버튼이다. 눌러서 그 발화 시각으로 이동하는 것(timestamp seek)이 이 패널의
 * 유일한 상호작용이라 키보드로도 같은 경로가 열려야 한다.
 *
 * <p>재생을 따라 활성 행을 옮길 때, 포인터가 목록 위에 있으면 자동 스크롤을 멈춘다 —
 * 읽는 중인 목록이 밑에서 계속 끌려가면 원하는 행을 누를 수 없다.
 */
export function TranscriptTimeline({ segments, currentSeconds, onSeek }: TranscriptTimelineProps) {
  const activeIndex = activeSegmentIndex(segments, currentSeconds);

  const listRef = useRef<HTMLDivElement | null>(null);
  const activeRowRef = useRef<HTMLButtonElement | null>(null);
  const hoveringRef = useRef(false);

  useEffect(() => {
    if (hoveringRef.current) return;
    // jsdom 에는 scrollIntoView 가 없다. 스크롤은 브라우저에서만 의미가 있는 동작이다.
    activeRowRef.current?.scrollIntoView?.({ block: "nearest" });
  }, [activeIndex]);

  return (
    <div className="z-card flex h-full min-h-0 flex-col overflow-hidden rounded-2xl">
      <div className="z-section-title shrink-0 border-b border-line-light px-[18px] py-[15px]">
        수업 내용
      </div>

      {segments.length === 0 ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-1.5 px-5 py-10 text-center text-ink-fainter">
          <PictoInbox size={36} />
          <div className="text-[13px] font-bold text-ink-muted">전사가 아직 없어요</div>
          <div className="text-xs">분석이 끝나면 수업 내용이 여기에 채워져요.</div>
        </div>
      ) : (
        <div
          ref={listRef}
          onMouseEnter={() => {
            hoveringRef.current = true;
          }}
          onMouseLeave={() => {
            hoveringRef.current = false;
          }}
          className="min-h-0 flex-1 overflow-y-auto px-2 py-1.5"
        >
          {segments.map((segment, index) => {
            const active = index === activeIndex;
            return (
              <button
                key={`${segment.startSeconds}-${index}`}
                type="button"
                ref={active ? activeRowRef : undefined}
                aria-current={active ? "true" : undefined}
                onClick={() => onSeek(segment.startSeconds)}
                className={`flex w-full cursor-pointer gap-3 rounded-[9px] border-0 px-2 py-[9px] text-left hover:bg-faint ${
                  active ? "bg-faint" : "bg-transparent"
                }`}
              >
                <span className="w-[42px] shrink-0 font-mono text-xs font-bold text-primary">
                  {formatOffset(segment.startSeconds)}
                </span>
                <span className="text-[13px] leading-[1.55] text-ink-sub">
                  {segment.speakerName.length > 0 && (
                    <span className="mr-1.5 font-bold text-ink-label">{segment.speakerName}</span>
                  )}
                  {segment.text}
                </span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
