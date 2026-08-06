"use client";

import { useState } from "react";
import Link from "next/link";
import { ChevronLeftIcon, PictoClockMuted, PictoLock, PictoWarn } from "@/shared/ui";
import { formatSessionStartedAt } from "./myLectures";
import { ReportClipTab } from "./components/report/ReportClipTab";
import { InstructorReport } from "./components/report/InstructorReport";
import { StudentReport } from "./components/report/StudentReport";
import { useSessionRole } from "./useSessionRole";
import type { ClipSeekRequest } from "@/domains/report";

const tabCls = (active: boolean) =>
  `-mb-px cursor-pointer border-0 border-b-[2.5px] bg-transparent px-0.5 py-[13px] font-sans text-[15px] font-extrabold ${
    active ? "border-primary text-ink" : "border-transparent text-ink-fainter"
  }`;

/**
 * 역할이 확정되기 전까지 두 탭이 함께 쓰는 안내.
 *
 * <p>역할을 모르는 채로 본문을 그리면 두 탭 모두 어느 엔드포인트를 부를지 모른다. 짐작으로
 * 고르면 학생이 강사 경로를(또는 그 반대를) 불러 403 만 받는다 — 잘못 고른 화면을 잠깐이라도
 * 보여주느니 아무것도 그리지 않는다.
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
 *
 * <p>제목·날짜·역할은 모두 세션 목록 응답에서 온다. fixture 를 폴백으로 두지 않는다 — 실제 세션
 * id 는 fixture 에 없어 늘 첫 강의로 떨어지고, 그러면 남의 강의 제목을 내 리포트로 읽는다. 게다가
 * 그 fixture 는 강사라서 학생이 강사용 경로를 불러 403 을 받는다(설계 문서 §2.7).
 */
export function ReportScreen({
  lectureId,
  initialTab = "clip",
  initialSeekSeconds = null,
}: {
  lectureId: string;
  /**
   * 처음 펼칠 탭. 기본은 클립이다 — 내 강의실에서 카드를 누르면 먼저 보고 싶은 것이 다시 보기다.
   *
   * <p>리포트 탭에서 떠났던 화면(퀴즈)이 돌아올 때는 `report` 로 들어온다. 그러지 않으면 리포트를
   * 보다 나갔는데 클립 탭으로 되돌아와, 방금까지 보던 자리를 다시 찾아 들어가야 한다.
   */
  initialTab?: "clip" | "report";
  /**
   * 클립 탭을 열 때 곧바로 이동할 시각(초). 퀴즈 해설의 "관련 강의 구간 다시 보기" 가 주소로
   * 넘긴다. 값이 없으면 녹화 자체의 초기 위치에서 시작한다.
   */
  initialSeekSeconds?: number | null;
}) {
  const { status: roleStatus, role, lecture } = useSessionRole(lectureId);
  const [tab, setTab] = useState<"clip" | "report">(initialTab);

  /**
   * 구간 상세의 "클립 바로가기". 클립 탭으로 옮기고 화면을 맨 위로 올린 뒤 그 시각을 넘긴다.
   *
   * <p>nonce 를 함께 올리는 이유: 같은 구간을 연달아 누르면 시각이 같아 상태가 바뀌지 않고,
   * 그러면 두 번째 이동이 묻힌다. 실제 재생 위치 이동은 학생 플레이어(113)가 맡는다.
   *
   * <p>클립 탭 안의 수업 요약 카드도 구간 시각으로 같은 문에 들어온다. 이미 클립 탭이라 탭 전환은
   * 아무 일도 하지 않지만, 플레이어가 그 카드보다 위에 있어 맨 위로 올리는 동작은 그대로 필요하다 —
   * 그러지 않으면 재생 위치만 조용히 바뀌고 화면에는 아무 변화가 보이지 않는다.
   */
  const [seekRequest, setSeekRequest] = useState<ClipSeekRequest | null>(
    initialSeekSeconds === null ? null : { seconds: initialSeekSeconds, nonce: 1 },
  );
  const jumpToClip = (offsetSeconds: number) => {
    setSeekRequest((previous) => ({
      seconds: offsetSeconds,
      nonce: (previous?.nonce ?? 0) + 1,
    }));
    setTab("clip");
    window.scrollTo({ top: 0 });
  };

  // 역할과 세션을 모르는 채로는 제목도 탭도 그릴 수 없다. 어느 엔드포인트를 부를지 몰라 짐작으로
  // 고르면 403 만 받는다. 잘못 고른 화면을 잠깐이라도 보여주느니 아무것도 그리지 않는다.
  if (roleStatus !== "ready" || lecture === null || role === null) {
    return (
      <>
        <div className="mb-5 flex items-center gap-4">
          <Link
            href="/my-lectures"
            aria-label="내 강의실 목록으로 돌아가기"
            className="flex size-10 shrink-0 items-center justify-center rounded-full border border-shell-toggle bg-surface text-ink-sub no-underline hover:bg-[#f3f5f3]"
          >
            <ChevronLeftIcon size={17} />
          </Link>
        </div>
        <RoleNotice status={roleStatus === "loading" ? "loading" : "unknown"} />
      </>
    );
  }

  const isInstructor = role === "INSTRUCTOR";
  const failed = lecture.status === "FAILED";
  // 분석이 끝나지 않은 강의는 보여줄 결과가 없어 탭과 본문을 모두 감춘다(프로토타입 reportOk).
  // 내 강의실에서 카드가 링크되지 않으므로 URL 직접 진입에만 해당한다.
  const ready = lecture.status === "COMPLETED";
  const meta = `${lecture.dur} | ${formatSessionStartedAt(lecture.startedAt)}`;

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
          <h1 className="mb-1 text-2xl font-extrabold tracking-[-.5px]">{lecture.title}</h1>
          <div className="text-[13.5px] font-semibold text-ink-fainter">{meta}</div>
        </div>
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

          {tab === "clip" ? (
            <ReportClipTab
              title={lecture.title}
              sessionId={lectureId}
              isStudent={role === "STUDENT"}
              seekRequest={seekRequest}
              onSeek={jumpToClip}
            />
          ) : isInstructor ? (
            <InstructorReport sessionId={lectureId} onJumpToClip={jumpToClip} />
          ) : (
            <StudentReport sessionId={lectureId} onJumpToClip={jumpToClip} />
          )}
        </>
      )}

    </>
  );
}
