"use client";

import type { SectionAverage } from "../infrastructure/attentionTimelineApi";
import { sectionColorOf, sectionSoftColorOf } from "./sectionFlow";
import { formatOffset } from "./TimelineStatusBar";

/**
 * 수업 내용 구간 목록.
 *
 * <p>경계는 서버가 준다(248 의 내용 타임라인). 10분 같은 고정 길이로 끊지 않는다
 * (REPORT-I-010 · REPORT-S-011).
 *
 * <p>값은 1~4 단계 평균이다. 퍼센트가 아니다.
 *
 * <p>위 차트가 같은 구간을 `구간 N` 으로 짚고 평균에 따라 색을 달리하므로, 카드도 같은 번호와 같은
 * 색을 쓴다 — 번호나 색이 어긋나면 두 그림이 다른 것을 가리키는 것처럼 보인다.
 */
export interface SectionAveragesProps {
  readonly sections: readonly SectionAverage[];
}

export function SectionAverages({ sections }: SectionAveragesProps) {
  // 248 이 내용 타임라인을 채우기 전에는 늘 빈 배열이다. 빈 표를 그리지 않고 영역 자체를 접는다.
  if (sections.length === 0) {
    return null;
  }

  return (
    <div className="mt-4">
      <div className="mb-2 text-xs font-extrabold text-ink-sub">수업 내용 구간</div>
      <ul className="flex list-none gap-2.5 overflow-x-auto p-0 pb-1.5">
        {sections.map((section, index) => {
          const color = sectionColorOf(section.focusLevel);
          return (
            <li
              key={section.startSeconds}
              className="w-[152px] shrink-0 rounded-[14px] border-[1.5px] border-line-mint bg-surface px-3.5 py-[13px]"
            >
              <div className="mb-[9px] flex items-center justify-between gap-1.5">
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
                    {section.focusLevel.toFixed(2)}
                  </span>
                )}
              </div>
              <div className="text-[13px] font-extrabold leading-[1.4] text-[#3a3f5c]">
                {section.title}
              </div>
              <div className="mt-1 font-mono text-[11px] text-ink-fainter">
                {formatOffset(section.startSeconds)}~{formatOffset(section.endSeconds)}
              </div>
            </li>
          );
        })}
      </ul>
      <p className="mt-1.5 text-[11.5px] font-semibold text-ink-faint">
        1~4 단계 평균입니다. 구간 경계는 수업 내용이 바뀌는 지점이에요.
      </p>
    </div>
  );
}
