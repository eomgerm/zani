"use client";

import { useState } from "react";
import {
  CartesianGrid,
  ComposedChart,
  Line,
  ReferenceArea,
  ResponsiveContainer,
  XAxis,
  YAxis,
} from "recharts";

import { Card } from "@/shared/ui";
import {
  requestStudentAttentionTimeline,
  type StudentTimelinePoint,
  type StudentTimelineRequester,
} from "../infrastructure/attentionTimelineApi";
import { formatOffset, TimelineStatusBar } from "./TimelineStatusBar";
import { mergeSegments } from "./timelineTrack";
import { useAttentionTimeline } from "./useAttentionTimeline";

/**
 * 학생 개인 집중 흐름 카드.
 *
 * <p>이 값은 점수가 아니라 30초 이동창으로 계산한 참고용 파생 지표다(NFR-UX-006). 전체 점수도,
 * 다른 학생과 견주는 수치도 만들지 않는다 — 만드는 순간 학생이 성적표로 읽는다.
 *
 * <p>단계·모델 확률·세부 수치는 내려오지도, 보여주지도 않는다(REPORT-S-010).
 *
 * <p>값이 없는 구간은 0 이 아니라 공백이다. `connectNulls={false}` 로 선을 끊고 회색 밴드로
 * 사유를 표시한다(REPORT-S-007). `shared/ui/FocusFlowChart` 는 `connectNulls` 가 켜져 있어
 * 이 요구사항에 쓸 수 없다.
 */

/** 값이 없는 구간을 찾는다. 비율을 다시 계산하는 것이 아니라 빈 자리를 짚기만 한다. */
const blankRunsOf = (
  points: readonly StudentTimelinePoint[],
  intervalSeconds: number,
): { start: number; end: number }[] => {
  const runs: { start: number; end: number }[] = [];
  for (const point of points) {
    if (point.focusPercent !== null) continue;
    const last = runs[runs.length - 1];
    if (last !== undefined && last.end === point.offsetSeconds) {
      last.end = point.offsetSeconds + intervalSeconds;
      continue;
    }
    runs.push({ start: point.offsetSeconds, end: point.offsetSeconds + intervalSeconds });
  }
  return runs;
};

const Notice = ({ icon, title, detail }: { icon: string; title: string; detail: string }) => (
  <div className="px-5 py-12 text-center text-ink-fainter">
    <div className="mb-3 text-[36px]">{icon}</div>
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
      return <Notice icon="⏳" title="집중 흐름을 불러오는 중이에요" detail="잠시만 기다려 주세요." />;
    }

    if (status === "live") {
      return (
        <Notice
          icon="🎥"
          title="아직 진행 중인 수업이에요"
          detail="수업이 끝나면 집중 흐름을 볼 수 있어요."
        />
      );
    }

    if (status === "forbidden") {
      return (
        <Notice
          icon="🔒"
          title="이 수업의 집중 흐름을 볼 권한이 없어요"
          detail="내가 참여한 수업인지 확인해 주세요."
        />
      );
    }

    if (status === "failed" || timeline === null) {
      return (
        <div className="px-5 py-12 text-center text-ink-fainter">
          <div className="mb-3 text-[36px]">⚠️</div>
          <div className="mb-1 font-bold text-ink-muted">집중 흐름을 불러오지 못했어요</div>
          <button type="button" onClick={retry} className="z-btn z-btn-outline z-btn-md mt-3">
            다시 시도
          </button>
        </div>
      );
    }

    if (timeline.points.length === 0) {
      return (
        <Notice
          icon="📭"
          title="이 수업에는 기록이 없어요"
          detail="참여도 관측이 한 건도 남지 않아 그릴 흐름이 없어요."
        />
      );
    }

    const { points, intervalSeconds, durationSeconds } = timeline;
    const blanks = blankRunsOf(points, intervalSeconds);
    const blankSeconds = blanks.reduce((sum, run) => sum + (run.end - run.start), 0);
    const total = durationSeconds > 0 ? durationSeconds : points.length * intervalSeconds;
    const segments = mergeSegments(points, intervalSeconds);

    // 차트 자체는 읽을 수 없는 그림이므로 요약을 이름으로 준다. 상호작용은 아래 상태 막대가 맡는다.
    const chartLabel =
      `집중 흐름 그래프. 30초 이동창으로 계산한 참고용 파생 지표. ` +
      `전체 ${formatOffset(total)} 중 ${formatOffset(blankSeconds)}는 값이 없어 비워 뒀습니다.`;

    return (
      <>
        <div role="img" aria-label={chartLabel}>
          <ResponsiveContainer width="100%" height={240}>
            <ComposedChart
              data={points as StudentTimelinePoint[]}
              margin={{ top: 12, right: 18, left: 4, bottom: 4 }}
            >
              <CartesianGrid horizontal vertical={false} stroke="#eef0f6" />
              {blanks.map((run) => (
                <ReferenceArea
                  key={`blank-${run.start}`}
                  x1={run.start}
                  x2={run.end}
                  y1={0}
                  y2={100}
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
              <YAxis
                type="number"
                domain={[0, 100]}
                ticks={[0, 50, 100]}
                tickFormatter={(value: number) => `${value}%`}
                tickLine={false}
                axisLine={false}
                width={40}
                tick={{ fill: "#8a90b4", fontSize: 11, fontWeight: 700 }}
              />
              {/* connectNulls 를 켜면 관측이 없던 구간이 이어져 "쭉 집중했다"로 보인다. */}
              <Line
                type="monotone"
                dataKey="focusPercent"
                stroke="#16c582"
                strokeWidth={2.4}
                dot={false}
                activeDot={false}
                connectNulls={false}
                isAnimationActive={false}
              />
            </ComposedChart>
          </ResponsiveContainer>
        </div>

        <p className="mb-3.5 mt-1 text-[11.5px] font-semibold text-ink-fainter">
          회색으로 비워 둔 구간은 값이 없는 시간이에요. 0% 가 아니라 기록이 없다는 뜻이에요.
        </p>

        <TimelineStatusBar
          segments={segments}
          selectedIndex={Math.min(selectedIndex, segments.length - 1)}
          onSelect={setSelectedIndex}
          label="구간별 내 상태"
        />
      </>
    );
  };

  return (
    <Card className="px-6 pb-5 pt-[22px]">
      <div className="mb-1 flex flex-wrap items-center gap-2">
        <div className="z-section-title">
          <span className="text-primary">📈</span>집중 흐름
        </div>
        <span className="text-[11.5px] text-ink-fainter">
          수업 시간 순서대로 본 내 참여 상태예요.
        </span>
      </div>

      {body()}

      <p className="mt-3.5 text-[11.5px] leading-[1.6] text-ink-fainter">
        30초 이동창으로 계산한 <b className="font-extrabold text-ink-faint">참고용 파생 지표</b>
        입니다. 점수가 아닙니다.
      </p>
    </Card>
  );
}
