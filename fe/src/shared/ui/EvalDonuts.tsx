"use client";

import { Cell, Pie, PieChart, ResponsiveContainer } from "recharts";

export interface EvalDatum {
  name: string;
  /** 0–100 점수 */
  value: number;
  color: string;
}

/**
 * 분야별 평가 도넛 그리드. 프로토타입 focus-flow-chart.js의 buildDonuts를 옮긴 것.
 * 각 항목을 링 게이지 + 중앙 점수로 표시한다.
 */
export function EvalDonuts({ data }: { data: EvalDatum[] }) {
  return (
    <div style={{ display: "grid", gridTemplateColumns: "repeat(2,1fr)", gap: "18px 8px", placeItems: "center" }}>
      {data.map((d) => {
        const pdata = [
          { name: "v", value: d.value },
          { name: "rest", value: Math.max(0, 100 - d.value) },
        ];
        return (
          <div key={d.name} style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 6 }}>
            <div style={{ position: "relative", width: 118, height: 118 }}>
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={pdata}
                    dataKey="value"
                    innerRadius={36}
                    outerRadius={52}
                    startAngle={90}
                    endAngle={-270}
                    stroke="none"
                    cx="50%"
                    cy="50%"
                    isAnimationActive={false}
                  >
                    <Cell fill={d.color} />
                    <Cell fill="#eef0f6" />
                  </Pie>
                </PieChart>
              </ResponsiveContainer>
              <div
                style={{
                  position: "absolute",
                  inset: 0,
                  display: "flex",
                  flexDirection: "column",
                  alignItems: "center",
                  justifyContent: "center",
                }}
              >
                <span style={{ fontSize: 22, fontWeight: 900, color: "#2c3049", letterSpacing: "-.5px" }}>
                  {d.value}
                </span>
                <span style={{ fontSize: 10.5, fontWeight: 800, color: "#a7adcb" }}>점</span>
              </div>
            </div>
            <span style={{ fontSize: 12.5, fontWeight: 800, color: "#4a4f6d" }}>{d.name}</span>
          </div>
        );
      })}
    </div>
  );
}
