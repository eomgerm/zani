import Link from "next/link";
import type { Lecture } from "../fixtures";

const WEEKDAYS = [
  { label: "일", cls: "text-sunday" },
  { label: "월", cls: "text-ink-faint" },
  { label: "화", cls: "text-ink-faint" },
  { label: "수", cls: "text-ink-faint" },
  { label: "목", cls: "text-ink-faint" },
  { label: "금", cls: "text-ink-faint" },
  { label: "토", cls: "text-saturday" },
];

/** 2026년 7월 강의 캘린더. 내 강의실 캘린더 보기 전용. */
export function LectureCalendar({ lectures }: { lectures: Lecture[] }) {
  const year = 2026;
  const month = 6; // July (0-based)
  const firstDow = new Date(year, month, 1).getDay();
  const daysInMonth = new Date(year, month + 1, 0).getDate();

  const byDay = new Map<number, Lecture[]>();
  for (const l of lectures) {
    if (!l.date.startsWith("2026-07")) continue;
    const d = parseInt(l.date.slice(8), 10);
    byDay.set(d, [...(byDay.get(d) ?? []), l]);
  }

  const cells: ({ blank: true } | { blank: false; day: number })[] = [];
  for (let i = 0; i < firstDow; i++) cells.push({ blank: true });
  for (let d = 1; d <= daysInMonth; d++) cells.push({ blank: false, day: d });
  while (cells.length % 7 !== 0) cells.push({ blank: true });

  return (
    <div className="z-card-lg px-[26px] py-6">
      <div className="mb-4 flex items-center gap-2.5">
        <div className="text-lg font-extrabold">2026년 7월</div>
      </div>

      <div className="mb-2 grid grid-cols-7 gap-2">
        {WEEKDAYS.map((w) => (
          <div key={w.label} className={`py-0.5 text-center text-[11.5px] font-extrabold ${w.cls}`}>
            {w.label}
          </div>
        ))}
      </div>

      <div className="grid grid-cols-7 gap-2">
        {cells.map((cell, i) => {
          if (cell.blank) return <div key={i} />;
          const { day } = cell;
          const dow = (firstDow + day - 1) % 7;
          const isToday = day === 16;
          const items = byDay.get(day) ?? [];
          const numCls = isToday
            ? "text-primary"
            : dow === 0
              ? "text-sunday"
              : dow === 6
                ? "text-saturday"
                : "text-ink-muted";

          return (
            <div
              key={i}
              className={`flex min-h-[84px] flex-col gap-[3px] overflow-hidden rounded-xl border px-2 py-[7px] ${
                isToday ? "border-line-primary bg-primary-softer" : "border-[#eff1f8] bg-surface"
              }`}
            >
              <span className={`text-xs font-extrabold ${numCls}`}>{day}</span>
              {items.slice(0, 2).map((l) => {
                const isIns = l.role === "instructor";
                return (
                  <Link
                    key={l.id}
                    href={l.status === "LIVE" ? `/room/${l.id}` : `/my-lectures/${l.id}/report`}
                    className={`truncate rounded-[5px] px-[5px] py-0.5 text-[10px] font-bold no-underline ${
                      isIns ? "bg-primary/15 text-primary" : "bg-info/15 text-info"
                    }`}
                  >
                    {l.title}
                  </Link>
                );
              })}
            </div>
          );
        })}
      </div>
    </div>
  );
}
