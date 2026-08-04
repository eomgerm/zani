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
import { SectionTimeline } from "./SectionTimeline";
import {
  sectionBounds,
  sectionCallout,
  sectionColorOf,
  sectionKeyOf,
  sectionMidpoint,
  toSectionRows,
} from "./sectionFlow";
import { formatOffset } from "./offsetTime";
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
  /** 구간 상세에서 클립 탭으로 옮길 때 쓴다. 배선이 없으면 상세에 버튼이 나오지 않는다. */
  readonly onJumpToClip?: (offsetSeconds: number) => void;
}

export function GroupAttentionTimeline({
  sessionId,
  request = requestGroupAttentionTimeline,
  onJumpToClip,
}: GroupAttentionTimelineProps) {
  const { status, timeline, retry } = useAttentionTimeline({ sessionId, enabled: true, request });
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [detailIndex, setDetailIndex] = useState<number | null>(null);

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

    // 흐름을 수업 내용 구간으로 나눠 그린다. 248 이 구간을 채우기 전 세션은 한 줄로 그린다.
    const hasSections = sections.length > 0;
    const sectionRows = hasSections ? toSectionRows(focusFlow.points, sections) : [];
    // 아래 타임라인에서 고른 구간을 차트에서도 짚어 준다 — 두 그림이 같은 자리를 가리킨다.
    const activeSection = hasSections ? Math.min(selectedIndex, sections.length - 1) : null;

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
            <ComposedChart margin={{ top: hasSections ? 34 : 12, right: 18, left: 4, bottom: 4 }}>
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
              </defs>
              <CartesianGrid horizontal vertical={false} stroke="#eef0f6" />

              {/* 선이 끊기는 자리를 다 덮는다 — 빈 칸에만 맞추면 왼쪽에 흰 틈이 남는다. */}
              {shortages.map((run) => (
                <ReferenceArea
                  key={`shortage-${run.start}`}
                  yAxisId="level"
                  x1={Math.max(0, run.start - focusFlow.intervalSeconds)}
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
                allowDuplicatedCategory={false}
                tickFormatter={formatOffset}
                tickLine={false}
                axisLine={{ stroke: "#e6e8f2" }}
                tick={{ fill: "#8a90b4", fontSize: 11 }}
              />

              {/* 1~4 단계 척도다. 0~100 으로 환산하지 않는다. */}
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
                <Area
                  yAxisId="level"
                  data={focusFlow.points as GroupFocusPoint[]}
                  type="monotone"
                  dataKey="focusLevel"
                  stroke="#16c582"
                  strokeWidth={2.8}
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
                  yAxisId="level"
                  x={sectionMidpoint(section)}
                  stroke="transparent"
                  label={sectionCallout(index + 1, index === activeSection, () => {
                    setSelectedIndex(index);
                    setDetailIndex(index);
                  })}
                />
              ))}
            </ComposedChart>
          </ResponsiveContainer>
        </div>
        {shortages.length > 0 && (
          <p className="mt-1 text-[11.5px] font-semibold leading-[1.6] text-ink-faint">
            회색 구간은 집계 인원이 부족합니다 — {MIN_AGGREGATE_HEADCOUNT}명 미만이라 값을 감췄어요.
          </p>
        )}
      </>
    );
  };

  // 구간은 차트와 타임라인이 같은 응답에서 나온다. 두 자리에 따로 받아 오지 않는다.
  const sections = timeline?.sections ?? [];
  const activeSectionIndex = Math.min(selectedIndex, Math.max(0, sections.length - 1));
  const activeSection = sections[activeSectionIndex];
  // 고른 구간이 시작되는 시점의 5초 포인트를 그대로 읽는다. 여러 포인트를 평탄화하면 서버가
  // 계산한 값이 아니라 화면이 지어낸 값이 된다.
  const selectedPoint: GroupSignalPoint | undefined =
    timeline === null
      ? undefined
      : activeSection === undefined
        ? timeline.signals.points[0]
        : (timeline.signals.points.find(
            (point) => point.offsetSeconds >= activeSection.startSeconds,
          ) ?? timeline.signals.points[0]);

  return (
    <>
      <div className="z-report-head flex flex-wrap items-center justify-between gap-2.5">
        <div className="z-section-title">집중 흐름</div>
        <div className="flex flex-wrap items-center gap-3.5 text-xs font-bold text-ink-muted">
          {/* 1~4 단계다. 시안 범례의 0 은 쓰지 않는다 — 0 단계 판정은 없다. */}
          <span className="flex items-center gap-[7px]">
            <span
              aria-hidden="true"
              className="h-2 w-9 rounded-full bg-[linear-gradient(90deg,#e0455f,#f4c325,#16c582)]"
            />
            1 낮음 → 4 높음
          </span>
          <span className="flex items-center gap-[7px]">
            <span
              aria-hidden="true"
              className="h-2 w-4 rounded-[3px] border border-line-light bg-[#c9cdde]/[.55]"
            />
            인원 부족
          </span>
        </div>
      </div>

      <div className="z-report-box px-6 pb-3 pt-[18px]">{body()}</div>

      {status === "ready" && timeline !== null && (
        <>
          <div className="z-report-head">
            <div className="z-section-title">타임라인</div>
            <div className="z-report-sub">
              구간을 눌러 어느 내용에서 집단 집중 흐름이 오르내렸는지 확인해 보세요.
            </div>
          </div>
          <div className="z-report-box px-6 py-[22px]">
            <SectionTimeline
              sections={sections}
              selectedIndex={activeSectionIndex}
              onSelect={setSelectedIndex}
              scopeLabel="전체 집중도"
              onJumpToClip={onJumpToClip}
              detailIndex={detailIndex}
              onDetailChange={setDetailIndex}
            />

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
          </div>
        </>
      )}
    </>
  );
}
