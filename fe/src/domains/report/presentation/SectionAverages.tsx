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
 *
 * <p>구간은 시간 순서로 읽는 것이라 가로로 늘어놓아 위 차트와 같은 방향으로 훑게 한다. 카드마다
 * 시각 범위를 적어 차트의 시간축에서 어디였는지 눈으로 이을 수 있다.
 */
export interface SectionAveragesProps {
  readonly sections: readonly SectionAverage[];
}

/** 단계 평균에 따른 강조색. 낮은 구간이 눈에 걸리게 둔다. */
const toneOf = (level: number): { readonly color: string; readonly background: string } => {
  if (level >= 3) return { color: "#16c582", background: "#eaf7f2" };
  if (level >= 2) return { color: "#d9a514", background: "#fdf6df" };
  if (level >= 1) return { color: "#e07a3f", background: "#fdefe8" };
  return { color: "#e0455f", background: "#fdeeee" };
};

/** 값이 없는 구간은 색으로도 낮은 단계와 구분한다. 1단계로 칠하면 "낮았다"로 읽힌다. */
const BLANK_TONE = { color: "#8a90b4", background: "#f4f5fa" } as const;

export function SectionAverages({ sections }: SectionAveragesProps) {
  // 248 이 내용 타임라인을 채우기 전에는 늘 빈 배열이다. 빈 표를 그리지 않고 영역 자체를 접는다.
  if (sections.length === 0) {
    return null;
  }

  return (
    <div className="mt-4">
      <div className="mb-2 text-xs font-extrabold text-ink-sub">수업 내용 구간별 집중 흐름</div>
      <ul className="flex list-none gap-2.5 overflow-x-auto p-0 pb-1.5">
        {sections.map((section) => {
          const tone = section.focusLevel === null ? BLANK_TONE : toneOf(section.focusLevel);
          return (
            <li
              key={section.startSeconds}
              className="w-[152px] shrink-0 rounded-[14px] border-[1.5px] border-line-mint bg-surface px-3.5 py-[13px]"
            >
              <div className="mb-[9px] flex items-center justify-between gap-1.5">
                <span className="font-mono text-[11px] font-bold text-ink-fainter">
                  {formatOffset(section.startSeconds)}
                </span>
                {/* null 은 1단계가 아니다. 값이 없다는 뜻이다(REPORT-S-007). */}
                {section.focusLevel === null ? (
                  <span className="shrink-0 text-[11px] font-semibold text-ink-fainter">
                    값 없음
                  </span>
                ) : (
                  <span
                    className="flex h-[30px] shrink-0 items-center justify-center rounded-full border-[1.6px] px-2 font-mono text-[12.5px] font-extrabold"
                    style={{
                      color: tone.color,
                      background: tone.background,
                      borderColor: tone.color,
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
                ~{formatOffset(section.endSeconds)}
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
