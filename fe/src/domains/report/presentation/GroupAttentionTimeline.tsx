"use client";

import { useState, type ReactNode } from "react";
import {
  Area,
  CartesianGrid,
  ComposedChart,
  Line,
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
  requestGroupAttentionTimeline,
  type GroupFocusPoint,
  type GroupSignalPoint,
  type GroupTimelineRequester,
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
 * 강사 익명 집단 타임라인 카드.
 *
 * <p>개인으로 가는 길을 만들지 않는다(REPORT-I-002). 서버가 식별자를 내려보내지 않고, 화면도
 * 집단 값만 그린다.
 *
 * <p><b>주 계열은 집단 집중 흐름이다.</b> 겹치지 않는 30초 구간의 1~4 단계 평균이며 왼쪽 축에
 * 그린다. 확인 필요 비율과 카메라 꺼짐 비율은 5초 격자의 0.0~1.0 분수라 오른쪽 축의 보조 계열이다.
 * 두 척도를 같은 축에 놓지 않는다(설계 문서 §2.8 · §5.3).
 *
 * <p><b>보조 계열 둘의 분모도 다르다.</b> 확인 필요 비율의 분모는 `eligibleCount`(측정 가능한
 * 인원), 카메라 꺼짐 비율의 분모는 `connectedCount`(접속한 인원 전체)다. 더하거나 견주면 안 되는
 * 값이라 화면에도 그 사실을 적는다(§2.3).
 *
 * <p>값은 서버가 계산한 것을 그대로 그린다. 비율은 표시할 때만 100 을 곱한다.
 */

/** 5명 미만이면 서버가 값을 `null` 로 감춘다(REPORT-I-005). 그 판단을 화면이 뒤집지 않는다. */
const MIN_AGGREGATE_HEADCOUNT = 5;

const percentOf = (ratio: number | null): string =>
  ratio === null ? "—" : `${Math.round(ratio * 100)}%`;

/**
 * 인원이 모자라 감춘 구간을 찾는다. 값을 다시 세지 않고 인원 수만 본다.
 *
 * <p>판정은 30초 격자로 한다. 서버가 그 칸에 걸친 5초 스냅샷의 최솟값을 담아 주므로(§2.10),
 * 5초 점을 따로 보지 않는다.
 */
const shortageRunsOf = (
  points: readonly GroupFocusPoint[],
  intervalSeconds: number,
): { start: number; end: number }[] => {
  const runs: { start: number; end: number }[] = [];
  for (const point of points) {
    if (point.eligibleCount >= MIN_AGGREGATE_HEADCOUNT) continue;
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

const MIX_FIELDS = [
  { key: "confusedRatio", label: "헷갈림" },
  { key: "missedRatio", label: "놓침" },
  { key: "nonResponseRatio", label: "무응답" },
  { key: "unmeasurableRatio", label: "측정 불가" },
] as const;

export interface GroupAttentionTimelineProps {
  readonly sessionId: string;
  /** 테스트에서 갈아끼우기 위한 선택 인자. 기본값이 실제 어댑터다. */
  readonly request?: GroupTimelineRequester;
}

export function GroupAttentionTimeline({
  sessionId,
  request = requestGroupAttentionTimeline,
}: GroupAttentionTimelineProps) {
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
          detail="내가 진행한 수업인지 확인해 주세요."
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

    const { focusFlow, signals, durationSeconds, distractedIntervals, sections } = timeline;

    // 격자가 둘이라 둘 다 비어야 관측이 없는 세션이다.
    if (focusFlow.points.length === 0 && signals.points.length === 0) {
      return (
        <Notice
          icon={<PictoInbox size={36} />}
          title="이 수업에는 기록이 없어요"
          detail="참여도 관측이 한 건도 남지 않아 그릴 흐름이 없어요."
        />
      );
    }

    const total =
      durationSeconds > 0 ? durationSeconds : focusFlow.points.length * focusFlow.intervalSeconds;
    const shortages = shortageRunsOf(focusFlow.points, focusFlow.intervalSeconds);

    // 상태 막대는 학생 상태가 아니라 흐트러짐 구간을 담는다. 강사 화면에는 개인 상태가 없다.
    const segments: TrackSegment[] = distractedIntervals.map((interval) => ({
      startSeconds: interval.startSeconds,
      endSeconds: interval.endSeconds,
      state: null,
    }));

    const activeIndex = segments.length === 0 ? 0 : Math.min(selectedIndex, segments.length - 1);
    const activeSegment = segments[activeIndex];
    // 구간 시작 시점의 5초 포인트를 그대로 읽는다. 여러 포인트를 평탄화하면 서버가 계산한 값이
    // 아니라 화면이 지어낸 값이 된다.
    const selectedPoint: GroupSignalPoint | undefined =
      activeSegment === undefined
        ? signals.points[0]
        : (signals.points.find((point) => point.offsetSeconds >= activeSegment.startSeconds) ??
          signals.points[0]);

    // 흐름을 수업 내용 구간으로 나눠 그린다. 248 이 구간을 채우기 전 세션은 한 줄로 그린다.
    const hasSections = sections.length > 0;
    const sectionRows = hasSections ? toSectionRows(focusFlow.points, sections) : [];
    // 상태 막대에서 고른 흐트러짐 구간이 어느 내용 구간인지 차트에서도 짚어 준다.
    const activeSection =
      hasSections && activeSegment !== undefined
        ? sectionIndexAt(sections, activeSegment.startSeconds)
        : null;

    const chartLabel =
      `집단 집중 흐름 그래프. 30초 구간마다 1~4 단계 평균을 낸 주 계열과 ` +
      `확인 필요 비율·카메라 꺼짐 비율 보조 계열. ` +
      `전체 ${formatOffset(total)}, 흐트러짐 구간 ${distractedIntervals.length}개.` +
      (hasSections ? ` 수업 내용 구간 ${sections.length}개로 나눠 색을 달리했습니다.` : "");

    return (
      <>
        <div role="img" aria-label={chartLabel}>
          <ResponsiveContainer width="100%" height={hasSections ? 264 : 240}>
            {/* 배열이 둘이라 차트에 data 를 주지 않고 계열마다 자기 data 를 준다. */}
            <ComposedChart margin={{ top: hasSections ? 34 : 12, right: 44, left: 4, bottom: 4 }}>
              <defs>
                {sections.map((section, index) => {
                  const color = sectionColorOf(section.focusLevel);
                  return (
                    <linearGradient
                      key={`grad-${section.startSeconds}`}
                      id={`groupFlow${index}`}
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
                {/* recharts 에 패턴 채우기 API 가 없어 SVG 패턴을 직접 선언한다. 색만으로
                    계열을 나누면 색각 이상인 사람에게 두 선이 같아 보인다(NFR-UX-005). */}
                <pattern
                  id="cameraOffHatch"
                  patternUnits="userSpaceOnUse"
                  width={6}
                  height={6}
                  patternTransform="rotate(45)"
                >
                  <rect width={6} height={6} fill="#5e9ec6" fillOpacity={0.08} />
                  <line x1={0} y1={0} x2={0} y2={6} stroke="#5e9ec6" strokeWidth={2.2} />
                </pattern>
              </defs>
              <CartesianGrid horizontal vertical={false} stroke="#eef0f6" />

              {shortages.map((run) => (
                <ReferenceArea
                  key={`shortage-${run.start}`}
                  yAxisId="level"
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
              {distractedIntervals.map((interval) => (
                <ReferenceArea
                  key={`distracted-${interval.startSeconds}`}
                  yAxisId="level"
                  x1={interval.startSeconds}
                  x2={interval.endSeconds}
                  y1={1}
                  y2={4}
                  fill="#e0455f"
                  fillOpacity={0.1}
                  stroke="#e0455f"
                  strokeOpacity={0.35}
                  ifOverflow="extendDomain"
                />
              ))}

              <XAxis
                dataKey="offsetSeconds"
                type="number"
                domain={[0, total]}
                allowDuplicatedCategory={false}
                tickFormatter={formatOffset}
                tickLine={false}
                axisLine={{ stroke: "#e6e8f2" }}
                tick={{ fill: "#8a90b4", fontSize: 11 }}
              />

              {/* 왼쪽: 집중 흐름 1~4. 주 계열이다. */}
              <YAxis
                yAxisId="level"
                type="number"
                domain={[1, 4]}
                ticks={[1, 2, 3, 4]}
                tickFormatter={(value: number) => `${value}단계`}
                tickLine={false}
                axisLine={false}
                width={52}
                tick={{ fill: "#8a90b4", fontSize: 11, fontWeight: 700 }}
              />
              {/* 오른쪽: 비율 0~1. 왼쪽 축과 단위가 다르다. 같은 축에 놓지 않는다. */}
              <YAxis
                yAxisId="ratio"
                orientation="right"
                type="number"
                domain={[0, 1]}
                ticks={[0, 0.5, 1]}
                // 데이터는 0.0~1.0 분수 그대로 두고 눈금에서만 100 을 곱한다.
                tickFormatter={(value: number) => `${Math.round(value * 100)}%`}
                tickLine={false}
                axisLine={false}
                width={40}
                tick={{ fill: "#8a90b4", fontSize: 11, fontWeight: 700 }}
              />

              <Area
                yAxisId="ratio"
                data={signals.points as GroupSignalPoint[]}
                type="monotone"
                dataKey="cameraOffRatio"
                stroke="none"
                fill="url(#cameraOffHatch)"
                connectNulls={false}
                isAnimationActive={false}
              />
              <Line
                yAxisId="ratio"
                data={signals.points as GroupSignalPoint[]}
                type="monotone"
                dataKey="checkNeededRatio"
                stroke="#e0455f"
                strokeWidth={1.6}
                strokeDasharray="2 3"
                dot={false}
                activeDot={false}
                connectNulls={false}
                isAnimationActive={false}
              />
              <Line
                yAxisId="ratio"
                data={signals.points as GroupSignalPoint[]}
                type="monotone"
                dataKey="cameraOffRatio"
                stroke="#5e9ec6"
                strokeWidth={1.6}
                strokeDasharray="6 4"
                dot={false}
                activeDot={false}
                connectNulls={false}
                isAnimationActive={false}
              />
              {/* 구간이 갈리는 자리를 점선으로 짚는다. */}
              {sectionBounds(sections).map((boundary) => (
                <ReferenceLine
                  key={`bound-${boundary}`}
                  yAxisId="level"
                  x={boundary}
                  stroke="#c6ccd4"
                  strokeWidth={1.2}
                  strokeDasharray="4 5"
                />
              ))}
              {/* 주 계열을 마지막에 그려 위로 올린다. 구간이 있으면 구간마다 색을 달리한다. */}
              {hasSections &&
                sections.map((section, index) => (
                  <Area
                    key={`area-${section.startSeconds}`}
                    yAxisId="level"
                    data={sectionRows}
                    type="monotone"
                    dataKey={sectionKeyOf(index)}
                    stroke={sectionColorOf(section.focusLevel)}
                    strokeWidth={2.8}
                    fill={`url(#groupFlow${index})`}
                    dot={false}
                    activeDot={false}
                    connectNulls={false}
                    isAnimationActive={false}
                  />
                ))}
              {!hasSections && (
                <Line
                  yAxisId="level"
                  data={focusFlow.points as GroupFocusPoint[]}
                  type="monotone"
                  dataKey="focusLevel"
                  stroke="#16c582"
                  strokeWidth={2.8}
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
                  yAxisId="level"
                  x={sectionMidpoint(section)}
                  stroke="transparent"
                  label={sectionCallout(index + 1, index === activeSection)}
                />
              ))}
            </ComposedChart>
          </ResponsiveContainer>
        </div>

        <ul className="mt-1 flex list-none flex-wrap gap-x-4 gap-y-1.5 p-0 text-[11.5px] font-semibold text-ink-faint">
          <li className="flex items-center gap-1.5">
            <span aria-hidden="true" className="inline-block h-1.5 w-5 rounded-full bg-[#16c582]" />
            집단 집중 흐름 (왼쪽 축)
          </li>
          <li className="flex items-center gap-1.5">
            <span
              aria-hidden="true"
              className="inline-block h-0.5 w-5 rounded-full"
              style={{
                background: "repeating-linear-gradient(90deg, #e0455f 0 2px, transparent 2px 5px)",
              }}
            />
            확인 필요 비율 (오른쪽 축, 점선)
          </li>
          <li className="flex items-center gap-1.5">
            <span
              aria-hidden="true"
              className="inline-block h-3 w-5 rounded-[3px] border border-[#5e9ec6]"
              style={{
                background:
                  "repeating-linear-gradient(45deg, #5e9ec6 0 2px, transparent 2px 6px)",
              }}
            />
            카메라 꺼짐 비율 (오른쪽 축, 빗금)
          </li>
          <li className="flex items-center gap-1.5">
            <span
              aria-hidden="true"
              className="inline-block h-3 w-5 rounded-[3px] border border-[#e0455f]/40 bg-[#e0455f]/10"
            />
            집중 흐트러짐 구간
          </li>
        </ul>

        <p className="mt-2 text-[11.5px] font-semibold leading-[1.6] text-ink-faint">
          왼쪽 축은 집중 흐름 1~4 단계, 오른쪽 축은 비율 0~100%입니다. 축이 다르니 두 선의 높이를
          견주지 마세요.
        </p>
        <p className="mt-1 text-[11.5px] font-semibold leading-[1.6] text-ink-faint">
          확인 필요 비율의 분모는 측정 가능한 인원, 카메라 꺼짐 비율의 분모는 접속한 인원 전체라
          분모가 다릅니다. 두 값을 더하지 마세요.
        </p>

        {shortages.length > 0 && (
          <p className="mt-1.5 text-[11.5px] font-semibold leading-[1.6] text-ink-faint">
            회색 구간은 집계 인원이 부족합니다 — {MIN_AGGREGATE_HEADCOUNT}명 미만이라 값을 감췄어요.
          </p>
        )}

        {segments.length > 0 ? (
          <div className="mt-4">
            <div className="mb-1.5 text-xs font-extrabold text-ink-sub">집중 흐트러짐 구간</div>
            <TimelineStatusBar
              segments={segments}
              selectedIndex={activeIndex}
              onSelect={setSelectedIndex}
              label="집중 흐트러짐 구간"
              renderLabel={(segment) =>
                `집중 흐트러짐 ${formatOffset(segment.startSeconds)}~${formatOffset(segment.endSeconds)}`
              }
            />
          </div>
        ) : (
          <p className="mt-4 text-xs font-semibold text-ink-faint">
            집중이 흐트러진 구간은 잡히지 않았어요.
          </p>
        )}

        {selectedPoint !== undefined && (
          <div className="mt-4 rounded-xl bg-canvas px-4 py-3.5">
            <div className="mb-2.5 text-xs font-extrabold text-ink-sub">
              {formatOffset(selectedPoint.offsetSeconds)} 시점의 응답 분포
            </div>
            <div className="grid grid-cols-4 gap-3">
              {MIX_FIELDS.map((field) => (
                <div key={field.key}>
                  <div className="mb-1 text-[11.5px] text-ink-faint">{field.label}</div>
                  <div className="text-lg font-extrabold tracking-[-.3px]">
                    {percentOf(selectedPoint[field.key])}
                  </div>
                </div>
              ))}
            </div>
            {selectedPoint.eligibleCount < MIN_AGGREGATE_HEADCOUNT && (
              <p className="mt-2.5 text-[11.5px] font-semibold text-ink-faint">
                이 시점은 인원이 모자라 분포를 감췄어요.
              </p>
            )}
          </div>
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
          수업 시간 순서대로 본 익명 집단 집중 흐름이에요.
        </span>
      </div>

      {body()}
    </Card>
  );
}
