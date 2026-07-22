"use client";

import {
  Area,
  CartesianGrid,
  ComposedChart,
  Line,
  ReferenceArea,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

export interface FlowSegment {
  /** "00:00–08:39" 형태의 구간 범위 */
  range: string;
  /** 이 구간의 집중 점수(0–4) */
  score: number;
}

interface FocusFlowChartProps {
  segments: FlowSegment[];
  /** 강조할 구간 인덱스 */
  activeSeg: number;
  /** 마커(원형 번호) 클릭 시 */
  onSelect?: (seg: number) => void;
}

const FSCORE = ["매우 낮음", "낮음", "보통", "높음", "매우 높음"];

const pad = (n: number) => (n < 10 ? "0" : "") + Math.round(n);
function fmtTime(v: number) {
  const tot = Math.round(v * 60);
  const hh = Math.floor(tot / 3600);
  const mm = Math.floor((tot % 3600) / 60);
  const ss = tot % 60;
  return hh > 0 ? `${hh}:${pad(mm)}:${pad(ss)}` : `${pad(mm)}:${pad(ss)}`;
}
const colOf = (f: number) =>
  f >= 3.5 ? "#16c582" : f >= 2.5 ? "#5bc79d" : f >= 1.5 ? "#f4c325" : f >= 0.5 ? "#e0714f" : "#e0455f";
const regOf = (f: number) =>
  f >= 3.5 ? "#eaf7f2" : f >= 2.5 ? "#eef8ef" : f >= 1.5 ? "#fdf8e7" : f >= 0.5 ? "#fdefe8" : "#fdeeee";
const toMin = (s: string) => {
  const p = (s || "0:0").split(":");
  return +p[0] + (+p[1] || 0) / 60;
};

/**
 * 학습(집중) 흐름 차트. 프로토타입의 focus-flow-chart.js(Recharts)를 React 컴포넌트로 옮긴 것.
 * 타임라인 구간에서 점수를 계단형(stepAfter)으로 그리고, 각 구간 중앙에 번호 마커를 찍는다.
 */
export function FocusFlowChart({ segments, activeSeg, onSelect }: FocusFlowChartProps) {
  const spans = segments.map((sg) => {
    const [a, b] = sg.range.split("–");
    return { s: toMin(a), e: toMin(b), f: sg.score };
  });
  const total = spans.length ? spans[spans.length - 1].e : 60;

  const data: { t: number; f: number }[] = [];
  spans.forEach((sp) => {
    data.push({ t: +sp.s.toFixed(2), f: sp.f });
    data.push({ t: +sp.e.toFixed(2), f: sp.f });
  });

  const ticks = (() => {
    const step = total > 90 ? 20 : total > 50 ? 10 : 5;
    const arr: number[] = [];
    for (let tt = 0; tt < total - step * 0.4; tt += step) arr.push(tt);
    arr.push(+total.toFixed(2));
    return arr;
  })();

  return (
    <ResponsiveContainer width="100%" height={250}>
      <ComposedChart data={data} margin={{ top: 46, right: 18, left: 4, bottom: 4 }}>
        <defs>
          <linearGradient id="flowg" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#1cdd93" stopOpacity={0.2} />
            <stop offset="100%" stopColor="#1cdd93" stopOpacity={0.02} />
          </linearGradient>
        </defs>
        {spans.map((sp, i) => (
          <ReferenceArea
            key={`rg${i}`}
            x1={sp.s}
            x2={sp.e}
            y1={0}
            y2={4}
            fill={regOf(sp.f)}
            fillOpacity={1}
            stroke="none"
            ifOverflow="extendDomain"
          />
        ))}
        <CartesianGrid horizontal vertical={false} stroke="#eef0f6" />
        <XAxis
          dataKey="t"
          type="number"
          domain={[0, total]}
          ticks={ticks}
          tickFormatter={fmtTime}
          tickLine={false}
          axisLine={{ stroke: "#e6e8f2" }}
          tick={{ fill: "#8a90b4", fontSize: 11 }}
          interval={0}
        />
        <YAxis
          type="number"
          domain={[0, 4]}
          ticks={[0, 1, 2, 3, 4]}
          tickLine={false}
          axisLine={false}
          width={26}
          tick={{ fill: "#8a90b4", fontSize: 11, fontWeight: 700 }}
        />
        <Area
          type="stepAfter"
          dataKey="f"
          stroke="none"
          fill="url(#flowg)"
          isAnimationActive={false}
          connectNulls
        />
        <Line
          type="stepAfter"
          dataKey="f"
          stroke="#16c582"
          strokeWidth={2.8}
          dot={{ r: 2.8, fill: "#16c582", stroke: "#fff", strokeWidth: 1.4 }}
          activeDot={false}
          isAnimationActive={false}
          connectNulls
        />
        <Tooltip
          cursor={{ stroke: "#c9cdf0", strokeDasharray: "4 4" }}
          formatter={(v: number) => [`${v} / 4 · ${FSCORE[Math.round(v)]}`, "집중 점수"]}
          labelFormatter={fmtTime}
          contentStyle={{ borderRadius: 10, border: "1px solid #e6e8f2", fontSize: 12 }}
        />
        {spans.map((sp, i) => {
          const active = i === activeSeg;
          const c = colOf(sp.f);
          const x = +((sp.s + sp.e) / 2).toFixed(2);
          return (
            <ReferenceLine
              key={`c${i}`}
              x={x}
              stroke={active ? "#1cdd93" : c}
              strokeWidth={active ? 1.6 : 1}
              strokeDasharray="4 4"
              strokeOpacity={active ? 0.7 : 0.4}
              label={(props: { viewBox?: { x?: number } }) => {
                const vx = props.viewBox?.x ?? 0;
                const r = active ? 15 : 12;
                return (
                  <g
                    style={{ cursor: "pointer" }}
                    onClick={(e) => {
                      e.stopPropagation();
                      onSelect?.(i);
                    }}
                  >
                    <circle cx={vx} cy={16} r={r + 3} fill="transparent" />
                    <circle
                      cx={vx}
                      cy={16}
                      r={r}
                      fill={active ? "#1cdd93" : "#fff"}
                      stroke="#1cdd93"
                      strokeWidth={active ? 2.8 : 1.8}
                    />
                    <text
                      x={vx}
                      y={20}
                      textAnchor="middle"
                      fill={active ? "#fff" : c}
                      fontSize={active ? 13 : 12}
                      fontWeight={800}
                      style={{ pointerEvents: "none" }}
                    >
                      {i + 1}
                    </text>
                  </g>
                );
              }}
            />
          );
        })}
      </ComposedChart>
    </ResponsiveContainer>
  );
}
