"use client";

import { useState } from "react";
import Link from "next/link";
import {
  ChevronLeftIcon,
  DownloadIcon,
  PictoClockMuted,
  PictoLock,
  PictoWarn,
} from "@/shared/ui";
import { lectures } from "./fixtures";
import { ReportClipTab } from "./components/report/ReportClipTab";
import { InstructorReport } from "./components/report/InstructorReport";
import { StudentReport } from "./components/report/StudentReport";
import { useSessionRole } from "./useSessionRole";
import type { ClipSeekRequest } from "@/domains/report";

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

/** `2026-07-14T01:00:00Z` → `2026.07.14 (화) 10:00`. 강사가 기억하는 것은 UTC 가 아니라 자기 시계다. */
function startedLabel(iso: string): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return "";

  const pad = (value: number) => `${value}`.padStart(2, "0");
  const date = `${at.getFullYear()}.${pad(at.getMonth() + 1)}.${pad(at.getDate())}`;
  return `${date} (${WEEKDAYS[at.getDay()]}) ${pad(at.getHours())}:${pad(at.getMinutes())}`;
}

const tabCls = (active: boolean) =>
  `-mb-px cursor-pointer border-0 border-b-[2.5px] bg-transparent px-0.5 py-[13px] font-sans text-[15px] font-extrabold ${
    active ? "border-primary text-ink" : "border-transparent text-ink-fainter"
  }`;

/**
 * 역할이 확정되기 전까지 두 탭이 함께 쓰는 안내.
 *
 * <p>역할을 모르는 채로 본문을 그리면 리포트 탭은 어느 엔드포인트를 부를지 모르고, 클립 탭은
 * 강사용 목업(다른 강의 제목·박제된 재생 시간·fixture 전사)을 학생에게 먼저 보여 준 뒤 실제
 * 화면으로 바꾼다. 잠깐이라도 그럴듯한 가짜를 보여주느니 아무것도 그리지 않는다.
 */
function RoleNotice({ status }: { status: "loading" | "unknown" }) {
  return status === "loading" ? (
    <div className="px-5 py-[70px] text-center text-ink-fainter">
      <div className="mb-3.5 flex justify-center">
        <PictoClockMuted size={44} />
      </div>
      <div className="font-bold text-ink-muted">리포트를 불러오는 중이에요</div>
    </div>
  ) : (
    <div className="px-5 py-[70px] text-center text-ink-fainter">
      <div className="mb-3.5 flex justify-center">
        <PictoLock size={44} />
      </div>
      <div className="mb-1 font-bold text-ink-muted">이 수업의 리포트를 볼 수 없어요</div>
      <div className="text-[13.5px]">내가 참여한 수업이 맞는지 확인해 주세요.</div>
    </div>
  );
}

/**
 * SC-06 강의 리포트. 역할(강사/학생)에 따라 클립 탭과 리포트 탭을 보여준다.
 * 탭 전환·구간 선택·상세 모달은 시연용 로컬 상태로 동작한다.
 */
export function ReportScreen({ lectureId }: { lectureId: string }) {
  // 클립 탭 목업만 아직 fixture 다. 실제 세션 id 는 fixture 에 없어 늘 첫 강의로 떨어진다.
  const lecture = lectures.find((l) => l.id === lectureId) ?? lectures[0];
  const { status: roleStatus, role, lecture: served } = useSessionRole(lectureId);
  const isInstructor = roleStatus === "ready" ? role === "INSTRUCTOR" : lecture.role === "instructor";

  /**
   * 분석이 끝나지 않은 강의는 보여줄 결과가 없어 탭과 본문을 모두 감춘다(프로토타입 reportOk).
   * 내 강의실에서 카드가 링크되지 않으므로 URL 직접 진입에만 해당한다.
   *
   * <p><b>서버가 답하기 전에는 감추지 않는다.</b> 아직 모르는 것을 "분석이 끝나지 않았어요" 로
   * 말하면 기다림을 실패로 알리는 셈이다. 그 사이의 안내는 RoleNotice 가 맡는다.
   */
  const status = served?.status ?? null;
  const failed = status === "FAILED";
  const ready = status === null || (!failed && status !== "PROCESSING" && status !== "LIVE");

  const [tab, setTab] = useState<"clip" | "report">("clip");

  /**
   * 구간 상세의 "클립 바로가기". 클립 탭으로 옮기고 화면을 맨 위로 올린 뒤 그 시각을 넘긴다.
   *
   * <p>nonce 를 함께 올리는 이유: 같은 구간을 연달아 누르면 시각이 같아 상태가 바뀌지 않고,
   * 그러면 두 번째 이동이 묻힌다. 실제 재생 위치 이동은 학생 플레이어(113)가 맡는다.
   */
  const [seekRequest, setSeekRequest] = useState<ClipSeekRequest | null>(null);
  const jumpToClip = (offsetSeconds: number) => {
    setSeekRequest((previous) => ({
      seconds: offsetSeconds,
      nonce: (previous?.nonce ?? 0) + 1,
    }));
    setTab("clip");
    window.scrollTo({ top: 0 });
  };

  /**
   * 서버가 준 제목만 적는다. fixture 로 물러나지 않는다.
   *
   * <p>실제 세션 id 는 fixture 에 없어 늘 첫 강의로 떨어진다. 그 값을 쓰면 로딩 중에는 남의 수업
   * 제목이 잠깐 뜨고, 볼 권한이 없는 수업에서는 "React 상태관리 심화 / 이 수업의 리포트를 볼 수
   * 없어요" 처럼 **없는 사실을 지어낸 화면**이 된다. 모르면 비워 두는 편이 낫다.
   */
  const title = served?.title ?? "";
  const meta = served ? `${served.dur} | ${startedLabel(served.startedAt)}` : "";

  return (
    <>
      <div className="mb-5 flex items-center gap-4">
        {/* 돌아갈 목록이 하나뿐이라 아이콘만 둔다. 어디로 가는지는 이름으로 알린다. */}
        <Link
          href="/my-lectures"
          aria-label={`${isInstructor ? "진행강의" : "참여강의"} 목록으로 돌아가기`}
          className="flex size-10 shrink-0 items-center justify-center rounded-full border border-shell-toggle bg-surface text-ink-sub no-underline hover:bg-[#f3f5f3]"
        >
          <ChevronLeftIcon size={17} />
        </Link>
        <div className="min-w-0 flex-1">
          <h1 className="mb-1 text-2xl font-extrabold tracking-[-.5px]">{title}</h1>
          <div className="text-[13.5px] font-semibold text-ink-fainter">{meta}</div>
        </div>
        {/* 다운로드는 리포트 탭에서만 노출한다(클립 탭에는 내려받을 문서가 없다). */}
        {ready && tab === "report" && (
          <button
            type="button"
            className="z-btn z-btn-primary shrink-0 gap-2 rounded-xl px-[22px] py-[13px] text-sm"
          >
            <DownloadIcon />
            리포트 다운로드
          </button>
        )}
      </div>

      {failed && (
        <div className="mb-2 flex items-center gap-3.5 rounded-2xl border border-shell-line bg-shell px-[22px] py-5">
          <span className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-surface">
            <PictoWarn size={22} />
          </span>
          <div className="flex-1">
            <div className="font-extrabold">결과를 생성하지 못했어요</div>
            <div className="text-[13px] text-ink-muted">
              분석 중 문제가 발생했어요. 잠시 후 다시 시도하거나 지원팀에 문의해 주세요.
            </div>
          </div>
          <Link href="/my-lectures" className="z-btn z-btn-outline z-btn-md">
            내 강의실로
          </Link>
        </div>
      )}

      {!ready ? (
        /* 프로토타입은 이 상태에서 본문을 비우지만, URL 직접 진입 시 빈 화면이 되므로
           왜 볼 것이 없는지만 짧게 알린다. */
        !failed && (
          <div className="mt-[22px] px-5 py-[70px] text-center text-ink-fainter">
            <div className="mb-3.5 flex justify-center">
              <PictoClockMuted size={44} />
            </div>
            <div className="mb-1 font-bold text-ink-muted">아직 분석이 끝나지 않았어요</div>
            <div className="text-[13.5px]">분석이 완료되면 리포트를 확인할 수 있어요.</div>
          </div>
        )
      ) : (
        <>
          {/* 탭 */}
          <div className="mb-[22px] flex border-b border-line">
            <button
              type="button"
              onClick={() => setTab("clip")}
              className={`${tabCls(tab === "clip")} mr-[30px]`}
            >
              {isInstructor ? "수업 클립" : "복습 클립"}
            </button>
            <button
              type="button"
              onClick={() => setTab("report")}
              className={tabCls(tab === "report")}
            >
              {isInstructor ? "수업 리포트" : "학습 리포트"}
            </button>
          </div>

          {roleStatus !== "ready" ? (
            /* 두 탭 모두 역할을 기다린다. 클립 탭도 예외가 아니다 — 목업을 먼저 보여 주면
               학생이 남의 강의 전사를 자기 수업으로 읽는다. */
            <RoleNotice status={roleStatus} />
          ) : tab === "clip" ? (
            <ReportClipTab
              title={lecture.title}
              sessionId={lectureId}
              isStudent={role === "STUDENT"}
              seekRequest={seekRequest}
            />
          ) : isInstructor ? (
            <InstructorReport sessionId={lectureId} onJumpToClip={jumpToClip} />
          ) : (
            <StudentReport
              lectureId={lecture.id}
              sessionId={lectureId}
              onJumpToClip={jumpToClip}
            />
          )}
        </>
      )}

    </>
  );
}
