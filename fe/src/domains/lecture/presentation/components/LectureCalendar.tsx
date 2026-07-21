import Link from "next/link";
import { color } from "@/shared/lib/theme";
import type { Lecture } from "../fixtures";

const WEEKDAYS = [
  { label: "일", color: "#e0616f" },
  { label: "월", color: color.textFaint },
  { label: "화", color: color.textFaint },
  { label: "수", color: color.textFaint },
  { label: "목", color: color.textFaint },
  { label: "금", color: color.textFaint },
  { label: "토", color: "#4a7bd6" },
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
    const list = byDay.get(d) ?? [];
    list.push(l);
    byDay.set(d, list);
  }

  const cells: ({ blank: true } | { blank: false; day: number })[] = [];
  for (let i = 0; i < firstDow; i++) cells.push({ blank: true });
  for (let d = 1; d <= daysInMonth; d++) cells.push({ blank: false, day: d });
  while (cells.length % 7 !== 0) cells.push({ blank: true });

  return (
    <div
      style={{
        background: "#fff",
        border: `1px solid ${color.border}`,
        borderRadius: 20,
        padding: "24px 26px",
        boxShadow: "0 4px 22px rgba(24,74,62,.05)",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 16 }}>
        <div style={{ fontWeight: 800, fontSize: 18 }}>2026년 7월</div>
      </div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(7,1fr)", gap: 8, marginBottom: 8 }}>
        {WEEKDAYS.map((w) => (
          <div key={w.label} style={{ textAlign: "center", fontSize: 11.5, fontWeight: 800, color: w.color, padding: "2px 0" }}>
            {w.label}
          </div>
        ))}
      </div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(7,1fr)", gap: 8 }}>
        {cells.map((cell, i) => {
          if (cell.blank) return <div key={i} />;
          const { day } = cell;
          const dow = (firstDow + day - 1) % 7;
          const isToday = day === 16;
          const items = byDay.get(day) ?? [];
          const numColor = isToday ? color.primary : dow === 0 ? "#e0616f" : dow === 6 ? "#4a7bd6" : color.textMuted;
          return (
            <div
              key={i}
              style={{
                minHeight: 84,
                border: `1px solid ${isToday ? "#c6eedf" : "#eff1f8"}`,
                borderRadius: 12,
                padding: "7px 8px",
                background: isToday ? color.primarySofter : "#fff",
                display: "flex",
                flexDirection: "column",
                gap: 3,
                overflow: "hidden",
              }}
            >
              <span style={{ fontSize: 12, fontWeight: 800, color: numColor }}>{day}</span>
              {items.slice(0, 2).map((l) => {
                const c = l.role === "instructor" ? color.primary : "#4a6fd6";
                return (
                  <Link
                    key={l.id}
                    href={l.status === "LIVE" ? `/room/${l.id}` : `/my-lectures/${l.id}/report`}
                    style={{
                      fontSize: 10,
                      padding: "2px 5px",
                      borderRadius: 5,
                      background: `${c}22`,
                      color: c,
                      whiteSpace: "nowrap",
                      overflow: "hidden",
                      textOverflow: "ellipsis",
                      fontWeight: 700,
                      textDecoration: "none",
                    }}
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
