"use client";

import { useState } from "react";
import Link from "next/link";
import { ChevronLeftIcon, ChevronRightIcon } from "@/shared/ui";
import type { MyLecture } from "../myLectures";
import { isLectureOpenable } from "../status";

const WEEKDAYS = [
  { label: "일", cls: "text-sunday" },
  { label: "월", cls: "text-ink-faint" },
  { label: "화", cls: "text-ink-faint" },
  { label: "수", cls: "text-ink-faint" },
  { label: "목", cls: "text-ink-faint" },
  { label: "금", cls: "text-ink-faint" },
  { label: "토", cls: "text-saturday" },
];

/**
 * 처음 보여줄 달. 오늘이 속한 달에서 시작한다.
 *
 * <p>값을 모듈 상수로 굳히지 않고 함수로 두는 이유: 자정을 넘겨 열어 둔 화면이 어제 달에 머무는 것을 막고, 테스트가 "지금"을 고정해도 같은 결과를 보게 하기 위해서다.
 */
function thisMonth() {
  const today = new Date();
  return { year: today.getFullYear(), month: today.getMonth() + 1 };
}

const navBtn =
  "flex size-8 cursor-pointer items-center justify-center rounded-[9px] border border-line-muted bg-surface text-ink-muted hover:bg-primary-softer";

/** 강의 캘린더. 강의가 있는 날을 강조하고 월 단위로 이동한다. */
export function LectureCalendar({ lectures }: { lectures: MyLecture[] }) {
  // 초기값 계산을 지연시킨다. 매 렌더마다 new Date() 를 만들면 상태와 무관한 비용이 계속 든다.
  const [{ year, month }, setYm] = useState(thisMonth);

  const shift = (delta: number) =>
    setYm(({ year: y, month: m }) => {
      const next = m + delta;
      if (next < 1) return { year: y - 1, month: 12 };
      if (next > 12) return { year: y + 1, month: 1 };
      return { year: y, month: next };
    });

  const firstDow = new Date(year, month - 1, 1).getDay();
  const daysInMonth = new Date(year, month, 0).getDate();
  const prefix = `${year}-${String(month).padStart(2, "0")}`;

  const byDay = new Map<number, MyLecture[]>();
  for (const l of lectures) {
    if (!l.date.startsWith(prefix)) continue;
    const d = parseInt(l.date.slice(8), 10);
    byDay.set(d, [...(byDay.get(d) ?? []), l]);
  }

  return (
    <div className="z-card-lg px-[26px] py-6">
      <div className="mb-4 flex items-center gap-2.5">
        <div className="text-lg font-extrabold">
          {year}년 {month}월
        </div>
        <button type="button" onClick={() => shift(-1)} aria-label="이전 달" className={navBtn}>
          <ChevronLeftIcon />
        </button>
        <button type="button" onClick={() => shift(1)} aria-label="다음 달" className={navBtn}>
          <ChevronRightIcon />
        </button>
      </div>

      <div className="mb-2 grid grid-cols-7 gap-2">
        {WEEKDAYS.map((w) => (
          <div key={w.label} className={`py-0.5 text-center text-[11.5px] font-extrabold ${w.cls}`}>
            {w.label}
          </div>
        ))}
      </div>

      <div className="grid grid-cols-7 gap-2">
        {Array.from({ length: firstDow }, (_, i) => (
          <div key={`blank-${i}`} />
        ))}
        {Array.from({ length: daysInMonth }, (_, i) => i + 1).map((day) => {
          const dow = new Date(year, month - 1, day).getDay();
          const items = byDay.get(day) ?? [];
          const has = items.length > 0;
          const numCls = dow === 0 ? "text-sunday" : dow === 6 ? "text-saturday" : "text-ink-muted";

          return (
            <div
              key={day}
              className={`flex min-h-24 flex-col items-stretch overflow-hidden rounded-xl border px-[7px] py-2 ${
                has ? "border-[#e0e4f5] bg-[#fafbff]" : "border-[#f1f2f8] bg-surface"
              }`}
            >
              <span className={`text-xs font-extrabold ${numCls}`}>{day}</span>
              {items.map((l) => {
                const chipCls = `mt-1 block truncate rounded-md px-[7px] py-[3px] text-[10.5px] font-bold leading-[1.35] text-primary-dark no-underline ${
                  l.role === "instructor" ? "bg-primary-soft" : "bg-primary-mint"
                }`;
                // 분석이 끝나지 않은 강의는 열 화면이 없어 링크로 만들지 않는다.
                return isLectureOpenable(l.status) ? (
                  <Link
                    key={l.id}
                    href={l.status === "LIVE" ? `/room/${l.id}` : `/my-lectures/${l.id}/report`}
                    className={chipCls}
                  >
                    {l.title}
                  </Link>
                ) : (
                  <span key={l.id} className={chipCls}>
                    {l.title}
                  </span>
                );
              })}
            </div>
          );
        })}
      </div>
    </div>
  );
}
