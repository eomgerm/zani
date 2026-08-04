"use client";

import { useState } from "react";
import { Cell, Pie, PieChart, ResponsiveContainer } from "recharts";

export interface EvalDatum {
  name: string;
  /** 0–100 점수 */
  value: number;
  color: string;
  /** 이 항목이 무엇을 본 것인지. 마우스를 올리면 보여준다. */
  desc?: string;
}

/**
 * 분야별 평가 도넛 그리드. 항목마다 링 게이지 + 항목명 + 점수를 낸다.
 *
 * <p>점수만 보면 무엇을 잰 것인지 알 수 없어 설명을 함께 준다. 네 항목의 설명을 늘 펼쳐 두면
 * 도넛보다 글이 넓어지므로, 올려 볼 때만 띄우고 눌러서 읽을 수도 있게 버튼으로 둔다.
 */
export function EvalDonuts({ data }: { data: EvalDatum[] }) {
  const [openName, setOpenName] = useState<string | null>(null);

  return (
    <div className="grid grid-cols-2 place-items-center gap-x-2 gap-y-[18px]">
      {data.map((d) => {
        const pdata = [
          { name: "v", value: d.value },
          { name: "rest", value: Math.max(0, 100 - d.value) },
        ];
        const open = openName === d.name;
        return (
          <div key={d.name} className="relative flex flex-col items-center">
            <span className="mb-1.5 text-[12.5px] font-extrabold text-ink-label">{d.name}</span>
            <button
              type="button"
              aria-describedby={d.desc === undefined ? undefined : `donut-${d.name}`}
              aria-expanded={d.desc === undefined ? undefined : open}
              onMouseEnter={() => setOpenName(d.name)}
              onMouseLeave={() => setOpenName(null)}
              onFocus={() => setOpenName(d.name)}
              onBlur={() => setOpenName(null)}
              onClick={() => setOpenName(open ? null : d.name)}
              className={`relative size-[118px] border-0 bg-transparent p-0 ${
                d.desc === undefined ? "cursor-default" : "cursor-help"
              }`}
            >
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
              {/* 링 안쪽 정중앙. items-baseline 로 맞추면 글자 밑선이 기준이 되어 위로 붙는다. */}
              <span className="absolute inset-0 flex items-center justify-center">
                <span className="flex items-baseline gap-0.5">
                  <span className="text-[22px] font-extrabold tracking-[-.5px] text-ink">
                    {d.value}
                  </span>
                  <span className="text-[10.5px] font-extrabold text-ink-ghost">점</span>
                </span>
              </span>
            </button>

            {d.desc !== undefined && (
              <span
                id={`donut-${d.name}`}
                role="tooltip"
                className={`pointer-events-none absolute bottom-full left-1/2 z-20 mb-1 w-[200px] -translate-x-1/2 rounded-[10px] bg-[#26263a] px-[11px] py-[9px] text-[11.5px] font-semibold leading-[1.55] text-white shadow-[0_10px_26px_rgba(20,25,50,.3)] ${
                  open ? "" : "hidden"
                }`}
              >
                {d.desc}
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}
