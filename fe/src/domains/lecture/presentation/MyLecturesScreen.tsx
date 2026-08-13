"use client";

import { useMemo, useState } from "react";
import { CalendarIcon, ListIcon, PictoInbox, SearchIcon, SortIcon } from "@/shared/ui";
import type { SessionListRequester } from "@/domains/lecture/infrastructure/sessionListApi";
import type { MyLecture } from "./myLectures";
import { useMyLectures } from "./useMyLectures";
import { LectureCard } from "./components/LectureCard";
import { LectureCalendar } from "./components/LectureCalendar";

/** 참여/진행 탭 토글. 활성일 때 초록 배경으로 채운다. */
function tabCls(active: boolean) {
  return `cursor-pointer rounded-full border-0 px-5 py-2 font-sans text-[13.5px] font-extrabold transition-colors ${
    active ? "bg-primary text-white" : "bg-transparent text-[#8388a6]"
  }`;
}

/** 리스트/캘린더 보기 토글. 참여/진행 토글과 같은 방식으로 활성 쪽을 초록으로 채운다. */
function viewCls(active: boolean) {
  return `flex h-8 w-10 cursor-pointer items-center justify-center rounded-full border-0 transition-colors ${
    active ? "bg-primary text-white" : "bg-transparent text-ink-fainter"
  }`;
}

/**
 * 정렬 키. 날짜 문자열(`date`)은 시:분이 잘려 있어 같은 날 수업끼리 순서가 정해지지 않는다.
 *
 * <p>시각 값으로 비교하는 이유: 서버가 `Instant` 를 내리면서 소수점 자릿수가 항목마다 달라질 수 있어(`...:00.500Z` vs `...:00Z`) 문자열 사전순은 뒤집힌다.
 *
 * <p>읽을 수 없는 값은 목록에서 빼지 않고 가장 오래된 것으로 둔다 — 시각을 모른다고 수업이 없었던 것은 아니다.
 */
function startedMs(l: MyLecture): number {
  const at = new Date(l.startedAt).getTime();
  return Number.isNaN(at) ? -Infinity : at;
}

/**
 * SC-05 내 강의실. 참여/진행 강의를 검색·정렬·리스트/캘린더로 확인한다.
 * 검색·정렬·보기 전환은 받아 온 목록 위에서 화면이 처리한다(서버는 페이지네이션 없이 전체를 준다).
 *
 * <p><b>분석에 실패한 강의도 보여준다.</b> 리포트가 실패했다고 수업이 없었던 것은 아니다. 목록에서 지우면 강사는 자기 수업이 사라진 것으로 보고, 재처리를 요청할 방법도 없어진다.
 */
export function MyLecturesScreen({ requestList }: { requestList?: SessionListRequester }) {
  const [tab, setTab] = useState<"student" | "instructor">("student");
  const [search, setSearch] = useState("");
  const [sortDesc, setSortDesc] = useState(true);
  const [view, setView] = useState<"list" | "cal">("list");

  const { lectures, loading, error } = useMyLectures(requestList);

  // 정렬은 목록을 만들 때 한 번만 한다. 리스트와 캘린더가 같은 배열을 받아 순서가 갈리지 않는다.
  const mine = useMemo(
    () => lectures.filter((l) => l.role === tab).sort((a, b) => startedMs(b) - startedMs(a)),
    [lectures, tab],
  );

  // 캘린더는 늘 최신순이라 mine 을 그대로 쓰고, 리스트만 토글에 따라 뒤집는다.
  const visible = useMemo(() => {
    const filtered = mine.filter((l) =>
      l.title.toLowerCase().includes(search.trim().toLowerCase()),
    );
    return sortDesc ? filtered : filtered.reverse();
  }, [mine, search, sortDesc]);

  return (
    <>
      <h1 className="mb-1 text-[26px] font-extrabold tracking-[-.5px]">내 강의실</h1>
      <p className="mb-[22px] text-ink-muted">
        실제로 생성했거나 참여한 수업만 모아서 보여드려요.
      </p>

      <div className="mb-9 flex flex-wrap items-center gap-2.5">
        <div className="inline-flex rounded-full border border-shell-toggle bg-surface p-1">
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

        {/* 라벨이 "최신순"(3자)과 "오래된순"(4자)을 오가므로 폭을 긴 쪽에 맞춰 잡아 둔다.
            폭이 라벨을 따라가면 전환할 때마다 왼쪽 검색 필드가 좌우로 밀린다. */}
        <button
          type="button"
          onClick={() => setSortDesc((v) => !v)}
          className="z-btn h-10 min-w-28 gap-[7px] rounded-xl border border-line-muted bg-surface px-3.5 text-[13px] font-bold text-ink-muted"
        >
          <SortIcon />
          {sortDesc ? "최신순" : "오래된순"}
        </button>

        <div className="inline-flex gap-0.5 rounded-full border border-shell-toggle bg-surface p-1">
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

      {/* 실패를 빈 목록과 같은 문구로 보여주면 사용자는 자기 수업이 사라진 줄 안다. 반드시 갈라 놓는다. */}
      {loading ? (
        <div className="px-5 py-[70px] text-center text-ink-fainter" role="status">
          강의 목록을 불러오는 중이에요…
        </div>
      ) : error !== null ? (
        <div className="px-5 py-[70px] text-center" role="alert">
          <div className="mb-1 font-bold text-ink-muted">{error}</div>
        </div>
      ) : view === "list" ? (
        visible.length === 0 ? (
          <div className="px-5 py-[70px] text-center text-ink-fainter">
            <div className="mb-3.5 flex justify-center">
              <PictoInbox size={44} />
            </div>
            <div className="mb-1 font-bold text-ink-muted">
              {tab === "student" ? "아직 참여한 수업이 없습니다." : "아직 진행한 수업이 없습니다."}
            </div>
            <div className="text-[13.5px]">홈에서 강의를 열거나 초대 코드로 참여해보세요.</div>
          </div>
        ) : (
          <div className="grid grid-cols-3 gap-[22px]">
            {/* grid-cols-3 의 첫 행(3장)만 우선 로드한다 — 뷰포트 최상단이라 LCP 후보이고,
                나머지까지 eager 면 접힌 카드들이 첫 행과 대역폭을 다툰다. */}
            {visible.map((l, i) => (
              <LectureCard key={l.id} lecture={l} priority={i < 3} />
            ))}
          </div>
        )
      ) : (
        <LectureCalendar lectures={mine} />
      )}
    </>
  );
}
