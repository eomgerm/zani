"use client";

import { useMemo, useState } from "react";
import { color } from "@/shared/lib/theme";
import { lectures } from "./fixtures";
import { LectureCard } from "./components/LectureCard";
import { LectureCalendar } from "./components/LectureCalendar";

/**
 * SC-05 내 강의실. 참여/진행 강의를 검색·정렬·리스트/캘린더로 확인한다.
 * 필터·검색·정렬·보기 전환은 시연용 로컬 상태로 동작한다.
 */
export function MyLecturesScreen() {
  const [tab, setTab] = useState<"student" | "instructor">("student");
  const [search, setSearch] = useState("");
  const [sortDesc, setSortDesc] = useState(true);
  const [view, setView] = useState<"list" | "cal">("list");

  const visible = useMemo(() => {
    const filtered = lectures
      .filter((l) => l.role === tab)
      .filter((l) => l.title.toLowerCase().includes(search.trim().toLowerCase()));
    return [...filtered].sort((a, b) =>
      sortDesc ? b.date.localeCompare(a.date) : a.date.localeCompare(b.date),
    );
  }, [tab, search, sortDesc]);

  return (
    <>
      <h1 style={{ fontSize: 26, fontWeight: 800, margin: "0 0 4px", letterSpacing: "-.5px" }}>내 강의실</h1>
      <p style={{ color: color.textMuted, margin: "0 0 22px" }}>
        실제로 생성했거나 참여한 수업만 모아서 보여드려요.
      </p>

      <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 22, flexWrap: "wrap" }}>
        <div style={{ display: "inline-flex", background: color.primarySofter, border: `1px solid ${color.borderMint}`, borderRadius: 999, padding: 4 }}>
          {(["student", "instructor"] as const).map((k) => (
            <button key={k} onClick={() => setTab(k)} style={pillToggle(tab === k)}>
              {k === "student" ? "참여강의" : "진행강의"}
            </button>
          ))}
        </div>
        <div style={{ flex: 1, minWidth: 20 }} />
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 8,
            padding: "0 14px",
            height: 40,
            borderRadius: 12,
            border: `1px solid ${color.borderMuted}`,
            background: "#fff",
            minWidth: 200,
          }}
        >
          <span style={{ color: color.textFainter }}>🔍</span>
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="강의 제목 검색"
            style={{ border: "none", outline: "none", background: "none", fontSize: 13.5, fontFamily: "inherit", width: "100%", color: color.text }}
          />
        </div>
        <button onClick={() => setSortDesc((v) => !v)} style={outlineBtn}>
          ⇅ {sortDesc ? "최신순" : "오래된순"}
        </button>
        <div style={{ display: "inline-flex", background: color.primarySofter, border: `1px solid ${color.borderMint}`, borderRadius: 999, padding: 4, gap: 2 }}>
          <button onClick={() => setView("list")} title="리스트 보기" style={viewToggle(view === "list")}>
            ☰
          </button>
          <button onClick={() => setView("cal")} title="캘린더 보기" style={viewToggle(view === "cal")}>
            📅
          </button>
        </div>
      </div>

      {view === "list" ? (
        visible.length === 0 ? (
          <div style={{ textAlign: "center", padding: "70px 20px", color: color.textFainter }}>
            <div style={{ fontSize: 44, marginBottom: 14 }}>📭</div>
            <div style={{ fontWeight: 700, color: color.textMuted, marginBottom: 4 }}>강의가 없어요</div>
            <div style={{ fontSize: 13.5 }}>홈에서 강의를 열거나 초대 코드로 참여해보세요.</div>
          </div>
        ) : (
          <div style={{ display: "grid", gridTemplateColumns: "repeat(3,minmax(0,1fr))", gap: 22 }}>
            {visible.map((l, i) => (
              <LectureCard key={l.id} lecture={l} index={i} />
            ))}
          </div>
        )
      ) : (
        <LectureCalendar lectures={lectures.filter((l) => l.role === tab)} />
      )}
    </>
  );
}

function pillToggle(active: boolean) {
  return {
    padding: "8px 18px",
    borderRadius: 999,
    border: "none",
    cursor: "pointer",
    fontFamily: "inherit",
    fontWeight: 800,
    fontSize: 13.5,
    background: active ? "#fff" : "transparent",
    color: active ? color.primary : color.textFaint,
    boxShadow: active ? "0 2px 6px rgba(24,74,62,.1)" : "none",
  } as const;
}

function viewToggle(active: boolean) {
  return {
    width: 34,
    height: 30,
    borderRadius: 999,
    border: "none",
    cursor: "pointer",
    fontFamily: "inherit",
    fontSize: 14,
    background: active ? "#fff" : "transparent",
    color: active ? color.primary : color.textFaint,
    boxShadow: active ? "0 2px 6px rgba(24,74,62,.1)" : "none",
  } as const;
}

const outlineBtn = {
  display: "flex",
  alignItems: "center",
  gap: 7,
  height: 40,
  padding: "0 14px",
  borderRadius: 12,
  border: `1px solid ${color.borderMuted}`,
  background: "#fff",
  cursor: "pointer",
  fontFamily: "inherit",
  fontWeight: 700,
  fontSize: 13,
  color: color.textMuted,
} as const;
