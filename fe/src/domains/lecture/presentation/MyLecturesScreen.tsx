"use client";

import { useMemo, useState } from "react";
import { CalendarIcon, ListIcon, SearchIcon, SortIcon } from "@/shared/ui";
import { lectures } from "./fixtures";
import { LectureCard } from "./components/LectureCard";
import { LectureCalendar } from "./components/LectureCalendar";

/** 참여/진행 탭 토글. 활성일 때 초록 배경으로 채운다. */
function tabCls(active: boolean) {
  return `cursor-pointer rounded-full border-0 px-5 py-2 font-sans text-[13.5px] font-extrabold transition-colors ${
    active ? "bg-primary text-white" : "bg-transparent text-[#8388a6]"
  }`;
}

/** 리스트/캘린더 보기 토글. 활성일 때 흰 배경 + 그림자로 떠 보이게 둔다. */
function viewCls(active: boolean) {
  return `flex h-8 w-10 cursor-pointer items-center justify-center rounded-full border-0 transition-colors ${
    active
      ? "bg-surface text-primary shadow-[0_2px_8px_rgba(24,74,62,.12)]"
      : "bg-transparent text-ink-fainter"
  }`;
}

/**
 * SC-05 내 강의실. 참여/진행 강의를 검색·정렬·리스트/캘린더로 확인한다.
 * 필터·검색·정렬·보기 전환은 시연용 로컬 상태로 동작한다.
 */
export function MyLecturesScreen() {
  const [tab, setTab] = useState<"student" | "instructor">("student");
  const [search, setSearch] = useState("");
  const [sortDesc, setSortDesc] = useState(true);
  const [view, setView] = useState<"list" | "cal">("list");

  // 분석에 실패한 강의는 보여줄 결과가 없어 목록·캘린더 모두에서 제외한다.
  const mine = useMemo(
    () => lectures.filter((l) => l.status !== "FAILED").filter((l) => l.role === tab),
    [tab],
  );

  const visible = useMemo(() => {
    const filtered = mine.filter((l) =>
      l.title.toLowerCase().includes(search.trim().toLowerCase()),
    );
    return [...filtered].sort((a, b) =>
      sortDesc ? b.date.localeCompare(a.date) : a.date.localeCompare(b.date),
    );
  }, [mine, search, sortDesc]);

  return (
    <>
      <h1 className="mb-1 text-[26px] font-extrabold tracking-[-.5px]">내 강의실</h1>
      <p className="mb-[22px] text-ink-muted">
        실제로 생성했거나 참여한 수업만 모아서 보여드려요.
      </p>

      <div className="mb-9 flex flex-wrap items-center gap-2.5">
        <div className="inline-flex rounded-full border border-line-mint bg-primary-softer p-1">
          {(["student", "instructor"] as const).map((k) => (
            <button key={k} type="button" onClick={() => setTab(k)} className={tabCls(tab === k)}>
              {k === "student" ? "참여강의" : "진행강의"}
            </button>
          ))}
        </div>

        <div className="min-w-[20px] flex-1" />

        <div className="flex h-10 min-w-[200px] items-center gap-2 rounded-xl border border-line-muted bg-surface px-3.5">
          <SearchIcon className="shrink-0 text-ink-fainter" />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="강의 제목 검색"
            className="w-full border-0 bg-transparent font-sans text-[13.5px] text-ink outline-none"
          />
        </div>

        <button
          type="button"
          onClick={() => setSortDesc((v) => !v)}
          className="z-btn h-10 gap-[7px] rounded-xl border border-line-muted bg-surface px-3.5 text-[13px] font-bold text-ink-muted"
        >
          <SortIcon />
          {sortDesc ? "최신순" : "오래된순"}
        </button>

        <div className="inline-flex gap-0.5 rounded-full border border-line-mint bg-primary-softer p-1">
          <button
            type="button"
            onClick={() => setView("list")}
            title="리스트 보기"
            aria-label="리스트 보기"
            aria-pressed={view === "list"}
            className={viewCls(view === "list")}
          >
            <ListIcon />
          </button>
          <button
            type="button"
            onClick={() => setView("cal")}
            title="캘린더 보기"
            aria-label="캘린더 보기"
            aria-pressed={view === "cal"}
            className={viewCls(view === "cal")}
          >
            <CalendarIcon />
          </button>
        </div>
      </div>

      {view === "list" ? (
        visible.length === 0 ? (
          <div className="px-5 py-[70px] text-center text-ink-fainter">
            <div className="mb-3.5 text-[44px]">📭</div>
            <div className="mb-1 font-bold text-ink-muted">
              {tab === "student" ? "아직 참여한 수업이 없습니다." : "아직 진행한 수업이 없습니다."}
            </div>
            <div className="text-[13.5px]">홈에서 강의를 열거나 초대 코드로 참여해보세요.</div>
          </div>
        ) : (
          <div className="grid grid-cols-3 gap-[22px]">
            {visible.map((l) => (
              <LectureCard key={l.id} lecture={l} />
            ))}
          </div>
        )
      ) : (
        <LectureCalendar lectures={mine} />
      )}
    </>
  );
}
