"use client";

import { useEffect, useRef, useState } from "react";

import type { SectionAverage } from "../infrastructure/attentionTimelineApi";
import { nextSegmentIndex } from "./timelineTrack";
import { SectionDetailModal } from "./SectionDetailModal";
import { sectionColorOf, sectionSoftColorOf } from "./sectionFlow";
import { formatOffset } from "./offsetTime";

/**
 * 타임라인. 수업 내용 구간을 시간 순서대로 늘어놓는다.
 *
 * <p>경계는 서버가 준다(248 의 내용 타임라인). 10분 같은 고정 길이로 끊지 않는다
 * (REPORT-I-010 · REPORT-S-011). 값은 1~4 단계 평균이며 퍼센트가 아니다.
 *
 * <p>구간 선택의 상호작용은 전부 여기가 맡는다(NFR-UX-007). 차트 SVG 안에는 포커스 요소를 만들 수
 * 없어 — recharts 가 그리는 path 는 키보드로 짚을 수 없다 — 카드가 그 역할을 대신한다.
 * roving tabindex 로 ←·→·Home·End 이동을 받고, 고른 구간을 `aria-live` 로 읽어 준다.
 *
 * <p>번호와 색은 위 차트와 같은 값을 쓴다. 어긋나면 두 그림이 다른 것을 가리키는 것처럼 보인다.
 */
export interface SectionTimelineProps {
  readonly sections: readonly SectionAverage[];
  readonly selectedIndex: number;
  readonly onSelect: (index: number) => void;
  /** 강사 화면은 집단 값이라 상세의 이름이 다르다. */
  readonly scopeLabel: string;
  /** 클립 탭으로 옮겨 그 구간을 재생한다. 배선이 없으면 상세에 버튼을 내지 않는다. */
  readonly onJumpToClip?: (offsetSeconds: number) => void;
  /**
   * 열려 있는 상세의 구간. 차트의 구간 이름표도 같은 상세를 열기 때문에 바깥이 들고 있을 수 있다.
   * 주지 않으면 이 컴포넌트가 스스로 들고 있는다.
   */
  readonly detailIndex?: number | null;
  readonly onDetailChange?: (index: number | null) => void;
}

export function SectionTimeline({
  sections,
  selectedIndex,
  onSelect,
  scopeLabel,
  onJumpToClip,
  detailIndex: controlledDetailIndex,
  onDetailChange,
}: SectionTimelineProps) {
  const listRef = useRef<HTMLUListElement | null>(null);
  const [ownDetailIndex, setOwnDetailIndex] = useState<number | null>(null);
  const detailIndex = onDetailChange === undefined ? ownDetailIndex : (controlledDetailIndex ?? null);
  const setDetailIndex = onDetailChange ?? setOwnDetailIndex;

  useEffect(() => {
    const list = listRef.current;
    if (list === null) return;
    // 이미 목록 안에 포커스가 있을 때만 옮긴다. 마운트만으로 포커스를 뺏으면 화면을 연 사람이
    // 읽던 자리를 잃는다.
    if (!list.contains(document.activeElement)) return;
    const target = list.children[selectedIndex]?.firstElementChild;
    if (target instanceof HTMLElement) target.focus();
  }, [selectedIndex]);

  if (sections.length === 0) {
    return (
      <p className="text-xs font-semibold text-ink-faint">
        수업 내용 구간이 아직 없어요. 구간이 만들어지면 여기에 나와요.
      </p>
    );
  }

  const onKeyDown = (event: React.KeyboardEvent<HTMLUListElement>) => {
    const next = nextSegmentIndex(selectedIndex, event.key, sections.length);
    if (next === selectedIndex) return;
    event.preventDefault();
    onSelect(next);
  };

  const selected = sections[Math.min(selectedIndex, sections.length - 1)];
  const labelOf = (section: SectionAverage, index: number) =>
    `구간 ${index + 1} · ${section.title} · ${formatOffset(section.startSeconds)}~${formatOffset(section.endSeconds)} · ` +
    (section.focusLevel === null ? "값 없음" : `${section.focusLevel.toFixed(1)}단계`);

  return (
    <div>
      <ul
        ref={listRef}
        onKeyDown={onKeyDown}
        className="flex list-none gap-4 overflow-x-auto p-0 pb-1.5"
      >
        {sections.map((section, index) => {
          const active = index === selectedIndex;
          const color = sectionColorOf(section.focusLevel);
          return (
            <li key={section.startSeconds} className="min-w-[176px] flex-1 shrink-0">
              <button
                type="button"
                tabIndex={active ? 0 : -1}
                aria-pressed={active}
                aria-label={labelOf(section, index)}
                onClick={() => {
                  onSelect(index);
                  setDetailIndex(index);
                }}
                className={`flex h-full w-full cursor-pointer flex-col rounded-[14px] border-[1.5px] px-3.5 py-[13px] text-left outline-offset-2 ${
                  active ? "border-primary bg-[#edfaf5]" : "border-line-mint bg-surface"
                }`}
              >
                <span className="mb-[9px] flex items-center justify-between gap-1.5">
                  <span className="text-xs font-extrabold text-ink-faint">구간 {index + 1}</span>
                  {/* null 은 1단계가 아니다. 값이 없다는 뜻이다(REPORT-S-007). */}
                  {section.focusLevel === null ? (
                    <span className="shrink-0 text-[11px] font-semibold text-ink-fainter">
                      값 없음
                    </span>
                  ) : (
                    <span
                      className="flex h-[30px] shrink-0 items-center justify-center rounded-full border-[1.6px] px-2 font-mono text-[12.5px] font-extrabold"
                      style={{
                        color,
                        background: sectionSoftColorOf(section.focusLevel),
                        borderColor: color,
                      }}
                    >
                      {section.focusLevel.toFixed(1)}
                    </span>
                  )}
                </span>
                <span className="text-[13px] font-extrabold leading-[1.4] text-[#3a3f5c]">
                  {section.title}
                </span>
                <span className="mt-1 font-mono text-[11px] text-ink-fainter">
                  {formatOffset(section.startSeconds)}~{formatOffset(section.endSeconds)}
                </span>
              </button>
            </li>
          );
        })}
      </ul>

      {/* 고른 구간은 눈으로 보면 카드 강조로 알 수 있다. 눈으로 보지 않는 사람에게도 알리되
          화면에는 같은 말을 두 번 쓰지 않는다(NFR-UX-007). */}
      <p aria-live="polite" className="sr-only">
        {selected === undefined
          ? ""
          : `선택한 구간 · ${labelOf(selected, Math.min(selectedIndex, sections.length - 1))}`}
      </p>

      {detailIndex !== null && sections[detailIndex] !== undefined && (
        <SectionDetailModal
          section={sections[detailIndex]}
          number={detailIndex + 1}
          scopeLabel={scopeLabel}
          onClose={() => setDetailIndex(null)}
          onJumpToClip={
            onJumpToClip === undefined
              ? undefined
              : (offsetSeconds) => {
                  setDetailIndex(null);
                  onJumpToClip(offsetSeconds);
                }
          }
        />
      )}
    </div>
  );
}
