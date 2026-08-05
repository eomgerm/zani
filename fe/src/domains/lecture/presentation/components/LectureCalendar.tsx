"use client";

import { useState } from "react";
import Link from "next/link";
import { ChevronLeftIcon, ChevronRightIcon } from "@/shared/ui";
import type { LectureStatus, MyLecture } from "../myLectures";
import { isLectureOpenable, statusInfo } from "../status";

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

/** 범례. 목록과 같은 상태 색을 쓰고, 실패한 강의도 목록처럼 캘린더에 남는다. */
const LEGEND: LectureStatus[] = ["COMPLETED", "PROCESSING", "LIVE", "FAILED"];

/**
 * 처음 보여줄 달. 오늘이 속한 달에서 시작한다.
 *
 * <p>값을 모듈 상수로 굳히지 않고 함수로 두는 이유: 자정을 넘겨 열어 둔 화면이 어제 달에 머무는 것을 막고, 테스트가 "지금"을 고정해도 같은 결과를 보게 하기 위해서다.
 */
function today() {
  const now = new Date();
  return { year: now.getFullYear(), month: now.getMonth() + 1, day: now.getDate() };
}

const navBtn =
  "flex size-[30px] cursor-pointer items-center justify-center rounded-lg border-0 bg-transparent text-ink-faint hover:bg-[#f1f3f2]";

/** 날짜 숫자 색. 오늘은 초록 원으로 덮으므로 주말 색을 쓰지 않는다. */
const dayNumCls = (dow: number, isToday: boolean) => {
  if (isToday) return "bg-primary font-extrabold text-white";
  const tone = dow === 0 ? "text-sunday" : dow === 6 ? "text-saturday" : "text-[#5d6461]";
  return `font-medium ${tone}`;
};

/** 강의 캘린더. 강의가 있는 날에 상태 점이 붙은 칩을 얹고 월 단위로 이동한다. */
export function LectureCalendar({ lectures }: { lectures: MyLecture[] }) {
  // 초기값 계산을 지연시킨다. 매 렌더마다 new Date() 를 만들면 상태와 무관한 비용이 계속 든다.
  const [now] = useState(today);
  const [{ year, month }, setYm] = useState({ year: now.year, month: now.month });

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

  // 마지막 줄을 빈 칸으로 채운다. 칸이 비면 격자 선이 중간에서 끊겨 보인다.
  const trailing = (7 - ((firstDow + daysInMonth) % 7)) % 7;
  const blankCls = "min-h-28 border-b border-r border-[#f0f2f1] bg-[#fcfcfc]";

  return (
    <div>
      <div className="mb-3.5 flex items-center justify-between gap-2.5">
        <div className="text-lg font-extrabold tracking-[-.4px]">
          {year}년 {month}월
        </div>
        <div className="flex items-center gap-3.5">
          <ul className="flex list-none flex-wrap items-center gap-3 p-0 text-xs font-bold text-ink-faint">
            {LEGEND.map((status) => {
              const si = statusInfo(status);
              return (
                <li key={status} className="flex items-center gap-1.5">
                  <span
                    aria-hidden="true"
                    className="size-[7px] rounded-full"
                    style={{ background: si.dot }}
                  />
                  {si.label}
                </li>
              );
            })}
          </ul>
          <div className="flex items-center gap-0.5">
            <button type="button" onClick={() => shift(-1)} aria-label="이전 달" className={navBtn}>
              <ChevronLeftIcon />
            </button>
            <button type="button" onClick={() => shift(1)} aria-label="다음 달" className={navBtn}>
              <ChevronRightIcon />
            </button>
          </div>
        </div>
      </div>

      <div className="overflow-hidden rounded-[10px] border border-[#e8eae9] bg-surface">
        <div className="grid grid-cols-7 border-b border-[#e8eae9]">
          {WEEKDAYS.map((label) => (
            <div
              key={label}
              className="py-[9px] text-center text-xs font-bold text-[#9aa09c]"
            >
              {label}
            </div>
          ))}
        </div>

        <div className="grid grid-cols-7">
          {Array.from({ length: firstDow }, (_, i) => (
            <div key={`blank-${i}`} className={blankCls} />
          ))}
          {Array.from({ length: daysInMonth }, (_, i) => i + 1).map((day) => {
            const dow = new Date(year, month - 1, day).getDay();
            const items = byDay.get(day) ?? [];
            const isToday = year === now.year && month === now.month && day === now.day;

            return (
              <div
                key={day}
                className="flex min-h-28 min-w-0 flex-col items-stretch overflow-hidden border-b border-r border-[#f0f2f1] bg-surface px-2 pb-[9px] pt-[7px]"
              >
                <div className="flex justify-end">
                  {/* 오늘은 초록 원으로 표시한다. 색만으로는 눈으로 보지 않는 사람에게 전달되지
                      않으므로 aria-current 로도 알린다(NFR-UX-005). */}
                  <span
                    aria-current={isToday ? "date" : undefined}
                    className={`rounded-full px-[7px] py-[5px] text-[13px] leading-none ${dayNumCls(dow, isToday)}`}
                  >
                    {day}
                  </span>
                </div>
                {items.map((l) => {
                  const chipCls =
                    "mt-[5px] flex items-center gap-1.5 truncate rounded-[7px] border border-[#e4e8e6] bg-surface px-2 py-1 text-xs font-bold leading-[1.4] text-[#2b322e] no-underline shadow-[0_1px_2px_rgba(20,30,25,.05)]";
                  const dot = (
                    <span
                      aria-hidden="true"
                      className="size-[7px] shrink-0 rounded-full"
                      style={{ background: statusInfo(l.status).dot }}
                    />
                  );
                  // 분석이 끝나지 않은 강의는 열 화면이 없어 링크로 만들지 않는다.
                  return isLectureOpenable(l.status) ? (
                    <Link
                      key={l.id}
                      href={l.status === "LIVE" ? `/room/${l.id}` : `/my-lectures/${l.id}/report`}
                      className={chipCls}
                    >
                      {dot}
                      <span className="truncate">{l.title}</span>
                    </Link>
                  ) : (
                    <span key={l.id} className={chipCls}>
                      {dot}
                      <span className="truncate">{l.title}</span>
                    </span>
                  );
                })}
              </div>
            );
          })}
          {Array.from({ length: trailing }, (_, i) => (
            <div key={`trailing-${i}`} className={blankCls} />
          ))}
        </div>
      </div>
    </div>
  );
}
