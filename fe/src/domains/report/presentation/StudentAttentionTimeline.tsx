"use client";

import { useState, type ReactNode } from "react";
import {
  Area,
  CartesianGrid,
  ComposedChart,
  ReferenceArea,
  ReferenceLine,
  ResponsiveContainer,
  XAxis,
  YAxis,
} from "recharts";

import {
  Card,
  PictoCamera,
  PictoClockMuted,
  PictoInbox,
  PictoLock,
  PictoWarn,
} from "@/shared/ui";
import {
  requestStudentAttentionTimeline,
  type FocusPoint,
  type StudentTimelineRequester,
} from "../infrastructure/attentionTimelineApi";
import { SectionAverages } from "./SectionAverages";
import {
  sectionBounds,
  sectionCallout,
  sectionColorOf,
  sectionIndexAt,
  sectionKeyOf,
  sectionMidpoint,
  toSectionRows,
} from "./sectionFlow";
import { formatOffset, TimelineStatusBar } from "./TimelineStatusBar";
import type { TrackSegment } from "./timelineTrack";
import { useAttentionTimeline } from "./useAttentionTimeline";

/**
 * 학생 개인 집중 흐름 카드.
 *
 * <p>이 값은 점수가 아니라 겹치지 않는 30초 구간마다 낸 4단계 판정의 단계 평균(1.00~4.00)이며
 * 참고용 파생 지표다(NFR-UX-006, 설계 문서 §2.8). 구간은 서로 겹치지 않으며 창을 밀며 다시
 * 재지 않는다. 전체 점수도, 다른 학생과 견주는 수치도 만들지 않는다 — 만드는 순간 학생이
 * 성적표로 읽는다.
 *
 * <p>모델 확률과 세부 수치는 내려오지도, 보여주지도 않는다(REPORT-S-010). 단계 값 자체는 리포트
 * 에서 보여준다 — 단계 비노출 제한은 실시간 화면에만 적용된다.
 *
 * <p>값이 없는 구간은 1단계가 아니라 공백이다. `connectNulls={false}` 로 선을 끊고 회색 밴드로
 * 사유를 표시한다(REPORT-S-007). `shared/ui/FocusFlowChart` 는 `connectNulls` 가 켜져 있어
 * 이 요구사항에 쓸 수 없다.
 */

/** 값이 없는 구간을 찾는다. 값을 다시 계산하는 것이 아니라 빈 자리를 짚기만 한다. */
const blankRunsOf = (
  points: readonly FocusPoint[],
  intervalSeconds: number,
): { start: number; end: number }[] => {
  const runs: { start: number; end: number }[] = [];
  for (const point of points) {
    if (point.focusLevel !== null) continue;
    const last = runs[runs.length - 1];
    if (last !== undefined && last.end === point.offsetSeconds) {
      last.end = point.offsetSeconds + intervalSeconds;
      continue;
    }
    runs.push({ start: point.offsetSeconds, end: point.offsetSeconds + intervalSeconds });
  }
  return runs;
};

const Notice = ({ icon, title, detail }: { icon: ReactNode; title: string; detail: string }) => (
  <div className="px-5 py-12 text-center text-ink-fainter">
    <div className="mb-3 flex justify-center">{icon}</div>
    <div className="mb-1 font-bold text-ink-muted">{title}</div>
    <div className="text-[13px]">{detail}</div>
  </div>
);

export interface StudentAttentionTimelineProps {
  readonly sessionId: string;
  /** 테스트에서 갈아끼우기 위한 선택 인자. 기본값이 실제 어댑터다. */
  readonly request?: StudentTimelineRequester;
}

export function StudentAttentionTimeline({
  sessionId,
  request = requestStudentAttentionTimeline,
}: StudentAttentionTimelineProps) {
  const { status, timeline, retry } = useAttentionTimeline({ sessionId, enabled: true, request });
  const [selectedIndex, setSelectedIndex] = useState(0);

  const body = () => {
    if (status === "loading") {
      return (
        <Notice
          icon={<PictoClockMuted size={36} />}
          title="집중 흐름을 불러오는 중이에요"
          detail="잠시만 기다려 주세요."
        />
      );
    }

    if (status === "live") {
      return (
        <Notice
          icon={<PictoCamera size={36} />}
          title="아직 진행 중인 수업이에요"
          detail="수업이 끝나면 집중 흐름을 볼 수 있어요."
        />
      );
    }

    if (status === "forbidden") {
      return (
        <Notice
          icon={<PictoLock size={36} />}
          title="이 수업의 집중 흐름을 볼 권한이 없어요"
          detail="내가 참여한 수업인지 확인해 주세요."
        />
      );
    }

    if (status === "failed" || timeline === null) {
      return (
        <div className="px-5 py-12 text-center text-ink-fainter">
          <div className="mb-3 flex justify-center">
            <PictoWarn size={36} />
          </div>
          <div className="mb-1 font-bold text-ink-muted">집중 흐름을 불러오지 못했어요</div>
          <button type="button" onClick={retry} className="z-btn z-btn-outline z-btn-md mt-3">
            다시 시도
          </button>
        </div>
      );
    }

    if (timeline.focusFlow.points.length === 0) {
      return (
        <Notice
          icon={<PictoInbox size={36} />}
          title="이 수업에는 기록이 없어요"
          detail="참여도 관측이 한 건도 남지 않아 그릴 흐름이 없어요."
        />
      );
    }

    const { focusFlow, durationSeconds, stateIntervals, sections } = timeline;
    const points = focusFlow.points;
    const intervalSeconds = focusFlow.intervalSeconds;
    const blanks = blankRunsOf(points, intervalSeconds);
    const blankSeconds = blanks.reduce((sum, run) => sum + (run.end - run.start), 0);
    const total = durationSeconds > 0 ? durationSeconds : points.length * intervalSeconds;

    // 서버가 병합해 준 구간을 그대로 넘긴다. 다시 병합하지 않는다(설계 문서 §2.13).
    const segments: TrackSegment[] = stateIntervals.map((interval) => ({
      startSeconds: interval.startSeconds,
      endSeconds: interval.endSeconds,
      state: interval.state,
    }));

    // 흐름을 수업 내용 구간으로 나눠 그린다. 248 이 구간을 채우기 전 세션은 한 줄로 그린다.
    const hasSections = sections.length > 0;
    const rows: readonly object[] = hasSections ? toSectionRows(points, sections) : points;
    // 상태 막대에서 고른 자리가 어느 내용 구간인지 차트에서도 짚어 준다.
    const activeSection =
      hasSections && segments.length > 0
        ? sectionIndexAt(sections, segments[Math.min(selectedIndex, segments.length - 1)].startSeconds)
        : null;

    // 차트 자체는 읽을 수 없는 그림이므로 요약을 이름으로 준다. 상호작용은 아래 상태 막대가 맡는다.
    const chartLabel =
      `집중 흐름 그래프. 겹치지 않는 30초 구간마다 1~4 단계 평균을 낸 참고용 파생 지표. ` +
      `전체 ${formatOffset(total)} 중 ${formatOffset(blankSeconds)}는 값이 없어 비워 뒀습니다.` +
      (hasSections ? ` 수업 내용 구간 ${sections.length}개로 나눠 색을 달리했습니다.` : "");

    return (
      <>
        <div role="img" aria-label={chartLabel}>
          <ResponsiveContainer width="100%" height={264}>
            <ComposedChart
              data={rows as FocusPoint[]}
              margin={{ top: hasSections ? 34 : 12, right: 18, left: 4, bottom: 4 }}
            >
              <defs>
                {sections.map((section, index) => {
                  const color = sectionColorOf(section.focusLevel);
                  return (
                    <linearGradient
                      key={`grad-${section.startSeconds}`}
                      id={`studentFlow${index}`}
                      x1={0}
                      y1={0}
                      x2={0}
                      y2={1}
                    >
                      <stop offset="0%" stopColor={color} stopOpacity={0.75} />
                      <stop offset="100%" stopColor={color} stopOpacity={0.18} />
                    </linearGradient>
                  );
                })}
              </defs>
              <CartesianGrid horizontal vertical={false} stroke="#eef0f6" />
              {blanks.map((run) => (
                <ReferenceArea
                  key={`blank-${run.start}`}
                  x1={run.start}
                  x2={run.end}
                  y1={1}
                  y2={4}
                  fill="#c9cdde"
                  fillOpacity={0.35}
                  stroke="none"
                  ifOverflow="extendDomain"
                />
              ))}
              <XAxis
                dataKey="offsetSeconds"
                type="number"
                domain={[0, total]}
                tickFormatter={formatOffset}
                tickLine={false}
                axisLine={{ stroke: "#e6e8f2" }}
                tick={{ fill: "#8a90b4", fontSize: 11 }}
              />
              {/* 1~4 단계 척도다. 0~100 으로 환산하지 않는다. */}
              <YAxis
                type="number"
                domain={[1, 4]}
                ticks={[1, 2, 3, 4]}
                tickFormatter={(value: number) => `${value}단계`}
                tickLine={false}
                axisLine={false}
                width={52}
                tick={{ fill: "#8a90b4", fontSize: 11, fontWeight: 700 }}
              />
              {/* 구간이 갈리는 자리를 점선으로 짚는다. */}
              {sectionBounds(sections).map((boundary) => (
                <ReferenceLine
                  key={`bound-${boundary}`}
                  x={boundary}
                  stroke="#c6ccd4"
                  strokeWidth={1.2}
                  strokeDasharray="4 5"
                />
              ))}
              {/* connectNulls 를 켜면 관측이 없던 구간이 이어져 "쭉 집중했다"로 보인다. */}
              {hasSections ? (
                sections.map((section, index) => (
                  <Area
                    key={`area-${section.startSeconds}`}
                    type="monotone"
                    dataKey={sectionKeyOf(index)}
                    stroke={sectionColorOf(section.focusLevel)}
                    strokeWidth={2.4}
                    fill={`url(#studentFlow${index})`}
                    dot={false}
                    activeDot={false}
                    connectNulls={false}
                    isAnimationActive={false}
                  />
                ))
              ) : (
                <Area
                  type="monotone"
                  dataKey="focusLevel"
                  stroke="#16c582"
                  strokeWidth={2.4}
                  fill="#16c582"
                  fillOpacity={0.14}
                  dot={false}
                  activeDot={false}
                  connectNulls={false}
                  isAnimationActive={false}
                />
              )}
              {/* 이름표는 마지막에 얹는다 — 앞에 두면 계열이 위로 덮는다. */}
              {sections.map((section, index) => (
                <ReferenceLine
                  key={`callout-${section.startSeconds}`}
                  x={sectionMidpoint(section)}
                  stroke="transparent"
                  label={sectionCallout(index + 1, index === activeSection)}
                />
              ))}
            </ComposedChart>
          </ResponsiveContainer>
        </div>

        <p className="mb-3.5 mt-1 text-[11.5px] font-semibold text-ink-fainter">
          회색으로 비워 둔 구간은 값이 없는 시간이에요. 1단계가 아니라 기록이 없다는 뜻이에요.
        </p>

        {segments.length > 0 ? (
          <TimelineStatusBar
            segments={segments}
            selectedIndex={Math.min(selectedIndex, segments.length - 1)}
            onSelect={setSelectedIndex}
            label="구간별 내 상태"
          />
        ) : (
          <p className="text-xs font-semibold text-ink-faint">구간 정보가 없어요.</p>
        )}

        <SectionAverages sections={sections} />
      </>
    );
  };

  return (
    <Card className="px-6 pb-5 pt-[22px]">
      <div className="mb-1 flex flex-wrap items-center gap-2">
        <div className="z-section-title">
          집중 흐름
        </div>
        <span className="text-[11.5px] text-ink-fainter">
          수업 시간 순서대로 본 내 참여 상태예요.
        </span>
      </div>

      {body()}

      <p className="mt-3.5 text-[11.5px] leading-[1.6] text-ink-fainter">
        겹치지 않는 30초 구간마다 낸 <b className="font-extrabold text-ink-faint">1~4 단계 평균</b>
        이에요. 참고용 파생 지표이며 점수가 아닙니다.
      </p>
    </Card>
  );
}
