"use client";

import { useEffect, useRef } from "react";

import { CloseIcon } from "@/shared/ui";
import type { SectionAverage } from "../infrastructure/attentionTimelineApi";
import { formatOffset } from "./offsetTime";
import { sectionColorOf, sectionLevelLabel, sectionLevelNote, sectionSoftColorOf } from "./sectionFlow";

/**
 * 구간 상세. 타임라인 카드를 누르면 열린다.
 *
 * <p>평가 문구는 단계 평균에서 끌어낸다. 서버가 구간별 문구를 주기 전까지 없는 내용을 지어내지
 * 않는다(110·112 가 실제 문구를 채운다).
 *
 * <p>바 길이는 1~4 를 0~100% 로 늘린 것이 아니라 4 를 만점으로 둔 비율이다 — `n / 4` 표기와 같은
 * 척도를 쓴다. 퍼센트로 바꿔 적지 않는다.
 */
export interface SectionDetailModalProps {
  readonly section: SectionAverage;
  /** 화면에 보이는 구간 번호(1부터). */
  readonly number: number;
  /** 강사 화면은 집단 값이라 이름이 다르다. */
  readonly scopeLabel: string;
  readonly onClose: () => void;
  /** 클립 탭으로 옮겨 이 구간 시작 시각을 재생한다. 배선이 없으면 버튼을 내지 않는다. */
  readonly onJumpToClip?: (offsetSeconds: number) => void;
}

export function SectionDetailModal({
  section,
  number,
  scopeLabel,
  onClose,
  onJumpToClip,
}: SectionDetailModalProps) {
  const closeRef = useRef<HTMLButtonElement | null>(null);

  // 열리면 닫기 버튼에 포커스를 둔다. 그러지 않으면 키보드로 여닫을 수 없다.
  useEffect(() => {
    closeRef.current?.focus();
  }, []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  const color = sectionColorOf(section.focusLevel);
  const range = `${formatOffset(section.startSeconds)}~${formatOffset(section.endSeconds)}`;

  return (
    <div className="z-backdrop" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label={`구간 ${number} ${section.title}`}
        onClick={(event) => event.stopPropagation()}
        className="max-h-[88vh] w-full max-w-[560px] overflow-y-auto rounded-[20px] bg-surface shadow-[0_30px_70px_rgba(24,28,52,.4)] animate-[zPop_.16s]"
      >
        <div className="relative px-[26px] pb-1 pt-6">
          <button
            ref={closeRef}
            type="button"
            onClick={onClose}
            aria-label="닫기"
            className="absolute right-[18px] top-[18px] flex size-8 cursor-pointer items-center justify-center rounded-[9px] border-0 bg-primary-softer text-ink-faint"
          >
            <CloseIcon size={14} />
          </button>
          <div className="mb-1.5 text-[13px] font-extrabold text-primary-deep">
            구간 {number} · {range}
          </div>
          <div className="text-[22px] font-extrabold tracking-[-.4px]">{section.title}</div>
        </div>

        <div className="px-[26px] pb-[26px] pt-[18px]">
          <div className="z-report-box mb-4 px-[18px] py-4">
            <div className="mb-3 flex items-center justify-between gap-2.5">
              <span className="text-[13px] font-extrabold text-ink-faint">{scopeLabel} 평가</span>
              {/* null 은 1단계가 아니다. 값이 없다는 뜻이다(REPORT-S-007). */}
              {section.focusLevel === null ? (
                <span className="text-[13px] font-extrabold text-ink-fainter">값 없음</span>
              ) : (
                <span className="inline-flex items-baseline gap-1" style={{ color }}>
                  <b className="font-mono text-[26px] font-extrabold tracking-[-.5px]">
                    {Math.round(section.focusLevel)}
                  </b>
                  <span className="text-[13px] font-extrabold text-ink-fainter">/ 4</span>
                </span>
              )}
            </div>
            <div className="mb-[11px] h-[9px] overflow-hidden rounded-full bg-[#eef0f6]">
              <div
                className="h-full rounded-full"
                style={{
                  width: `${((section.focusLevel ?? 0) / 4) * 100}%`,
                  background: color,
                }}
              />
            </div>
            <div className="flex items-center gap-2.5">
              <span
                className="inline-block shrink-0 rounded-full px-2.5 py-[3px] text-xs font-extrabold"
                style={{ color, background: sectionSoftColorOf(section.focusLevel) }}
              >
                {sectionLevelLabel(section.focusLevel)}
              </span>
              <span className="flex-1 text-[13px] leading-[1.5] text-ink-sub">
                {sectionLevelNote(section.focusLevel)}
              </span>
            </div>
          </div>

          {onJumpToClip !== undefined && (
            <button
              type="button"
              onClick={() => onJumpToClip(section.startSeconds)}
              className="z-btn z-btn-primary w-full rounded-[13px] py-3.5 text-sm"
            >
              복습 클립 바로가기
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
