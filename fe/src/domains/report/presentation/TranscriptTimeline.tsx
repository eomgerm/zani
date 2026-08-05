"use client";

import { useEffect, useRef } from "react";

import { PictoInbox } from "@/shared/ui";
import type { TranscriptSegment } from "../infrastructure/studentClipApi";
import { formatOffset } from "./offsetTime";
import { activeSegmentIndex } from "./transcriptCursor";

/**
 * 활성 행을 목록 위쪽에서 이만큼 아래에 둔다.
 *
 * <p>0 으로 붙이면 직전 발화가 화면에서 완전히 사라져 문맥이 끊긴다. 가사 화면들도 현재 줄을 맨 위에 딱 붙이지 않고 한 줄 남을 만큼 띄운다.
 */
const ACTIVE_ROW_TOP_PADDING_PX = 8;

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
 *
 * <p>자동 스크롤은 활성 행을 목록 **위쪽에 붙여** 따라간다(음악 앱 가사 화면과 같은 방식).
 * `scrollIntoView({ block: "nearest" })` 처럼 "화면 밖일 때만 최소한으로" 움직이면 활성 행이
 * 목록 아래쪽에 걸린 채로 남아, 다음에 무슨 말이 나오는지 보이지 않는다.
 */
export function TranscriptTimeline({ segments, currentSeconds, onSeek }: TranscriptTimelineProps) {
  const activeIndex = activeSegmentIndex(segments, currentSeconds);

  const listRef = useRef<HTMLDivElement | null>(null);
  const activeRowRef = useRef<HTMLButtonElement | null>(null);
  const hoveringRef = useRef(false);

  useEffect(() => {
    if (hoveringRef.current) return;
    const list = listRef.current;
    const row = activeRowRef.current;
    if (list === null || row === null) return;

    // 컨테이너 기준 상대 위치로 계산한다. offsetTop 은 offsetParent 가 무엇이냐에 따라 값이 달라지는데,
    // 이 패널은 바깥에서 absolute 래퍼에 담겨 쓰인다(StudentReportClip) — 그 차이에 기대면 조용히 어긋난다.
    const delta = row.getBoundingClientRect().top - list.getBoundingClientRect().top;
    const target = list.scrollTop + delta - ACTIVE_ROW_TOP_PADDING_PX;
    // 이미 그 자리면 건드리지 않는다. 매 timeupdate 마다 scrollTo 를 부르면 부드러운 스크롤이 계속 재시작된다.
    if (Math.abs(target - list.scrollTop) < 1) return;

    // 움직임을 줄여 달라고 한 사용자에게는 즉시 이동한다. 따라가는 것 자체가 목적이라 스크롤을 없애지는 않는다.
    const reduceMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches === true;
    // jsdom 에는 scrollTo 가 없다. 스크롤은 브라우저에서만 의미가 있는 동작이다.
    list.scrollTo?.({ top: target, behavior: reduceMotion ? "auto" : "smooth" });
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
