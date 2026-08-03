"use client";

import type { SectionAverage } from "../infrastructure/attentionTimelineApi";
import { formatOffset } from "./TimelineStatusBar";

/**
 * 수업 내용 구간별 집중 흐름 평균.
 *
 * <p>경계는 서버가 준다(248 의 내용 타임라인). 10분 같은 고정 길이로 끊지 않는다
 * (REPORT-I-010 · REPORT-S-011).
 *
 * <p>값은 1~4 단계 평균이다. 퍼센트가 아니다.
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
      <div className="mb-1.5 text-xs font-extrabold text-ink-sub">수업 내용 구간별 집중 흐름</div>
      <ul className="flex list-none flex-col gap-1.5 p-0">
        {sections.map((section) => (
          <li
            key={section.startSeconds}
            className="flex items-baseline justify-between gap-3 rounded-lg bg-canvas px-3 py-2"
          >
            <div className="min-w-0">
              <div className="truncate text-[13px] font-bold text-ink-sub">{section.title}</div>
              <div className="text-[11.5px] text-ink-fainter">
                {formatOffset(section.startSeconds)}~{formatOffset(section.endSeconds)}
              </div>
            </div>
            {/* null 은 1단계가 아니다. 값이 없다는 뜻이다(REPORT-S-007). */}
            <div className="shrink-0 text-base font-extrabold tracking-[-.3px]">
              {section.focusLevel === null ? (
                <span className="text-[12px] font-semibold text-ink-fainter">값 없음</span>
              ) : (
                section.focusLevel.toFixed(2)
              )}
            </div>
          </li>
        ))}
      </ul>
      <p className="mt-1.5 text-[11.5px] font-semibold text-ink-faint">
        1~4 단계 평균입니다. 구간 경계는 수업 내용이 바뀌는 지점이에요.
      </p>
    </div>
  );
}
