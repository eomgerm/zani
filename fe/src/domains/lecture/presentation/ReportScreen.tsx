"use client";

import { useState } from "react";
import Link from "next/link";
import { DownloadIcon } from "@/shared/ui";
import { learnSegments, lectures } from "./fixtures";
import { ReportClipTab } from "./components/report/ReportClipTab";
import { InstructorReport } from "./components/report/InstructorReport";
import { StudentReport } from "./components/report/StudentReport";
import { SegmentModal } from "./components/report/SegmentModal";
import { useSessionRole } from "./useSessionRole";

const tabCls = (active: boolean) =>
  `-mb-px cursor-pointer border-0 border-b-[2.5px] bg-transparent px-0.5 py-[13px] font-sans text-[15px] font-extrabold ${
    active ? "border-primary text-ink" : "border-transparent text-ink-fainter"
  }`;

/**
 * SC-06 강의 리포트. 역할(강사/학생)에 따라 클립 탭과 리포트 탭을 보여준다.
 * 탭 전환·구간 선택·상세 모달은 시연용 로컬 상태로 동작한다.
 */
export function ReportScreen({ lectureId }: { lectureId: string }) {
  const lecture = lectures.find((l) => l.id === lectureId) ?? lectures[0];
  // 제목·날짜·클립 탭은 아직 fixture 다(110 범위). 역할만 서버 값으로 판정한다 — 실제 세션 id 는
  // fixture 에 없어 늘 첫 강의(강사)로 떨어지고, 그러면 학생이 강사용 경로를 불러 403 을 받는다.
  const { status: roleStatus, role } = useSessionRole(lectureId);
  const isInstructor = roleStatus === "ready" ? role === "INSTRUCTOR" : lecture.role === "instructor";
  const failed = lecture.status === "FAILED";

  // 분석이 끝나지 않은 강의는 보여줄 결과가 없어 탭과 본문을 모두 감춘다(프로토타입 reportOk).
  // 내 강의실에서 카드가 링크되지 않으므로 URL 직접 진입에만 해당한다.
  const ready = !failed && lecture.status !== "PROCESSING" && lecture.status !== "LIVE";

  const [tab, setTab] = useState<"clip" | "report">("clip");
  const [activeSeg, setActiveSeg] = useState(2);
  const [segModal, setSegModal] = useState<number | null>(null);

  const onSelectSeg = (i: number) => {
    setActiveSeg(i);
    setSegModal(i);
  };

  const meta = `${lecture.dur} | ${lecture.date.replace(/-/g, ".")} (목) 14:00`;

  return (
    <>
      <Link
        href="/my-lectures"
        className="mb-4 inline-flex items-center gap-[7px] text-sm font-extrabold text-ink-sub no-underline"
      >
        ← {isInstructor ? "진행강의" : "참여강의"}
      </Link>

      <div className="mb-5 flex items-center gap-4">
        <div className="min-w-0 flex-1">
          <h1 className="mb-1 text-2xl font-extrabold tracking-[-.5px]">{lecture.title}</h1>
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
        <div className="mb-2 flex items-center gap-3.5 rounded-2xl border border-line-muted bg-primary-softer px-[22px] py-5">
          <span className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-danger-soft text-xl text-danger">
            ⚠️
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
            <div className="mb-3.5 text-[44px]">⏳</div>
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
            <ReportClipTab title={lecture.title} />
          ) : roleStatus === "loading" ? (
            /* 역할을 모르는 채로 그리면 어느 엔드포인트를 부를지도 모른다. 어느 쪽도 그리지 않는다. */
            <div className="px-5 py-[70px] text-center text-ink-fainter">
              <div className="mb-3.5 text-[44px]">⏳</div>
              <div className="font-bold text-ink-muted">리포트를 불러오는 중이에요</div>
            </div>
          ) : roleStatus === "unknown" ? (
            <div className="px-5 py-[70px] text-center text-ink-fainter">
              <div className="mb-3.5 text-[44px]">🔒</div>
              <div className="mb-1 font-bold text-ink-muted">이 수업의 리포트를 볼 수 없어요</div>
              <div className="text-[13.5px]">내가 참여한 수업이 맞는지 확인해 주세요.</div>
            </div>
          ) : isInstructor ? (
            <InstructorReport sessionId={lectureId} activeSeg={activeSeg} onSelect={onSelectSeg} />
          ) : (
            <StudentReport
              lectureId={lecture.id}
              sessionId={lectureId}
              activeSeg={activeSeg}
              onSelect={onSelectSeg}
            />
          )}
        </>
      )}

      {segModal !== null && (
        <SegmentModal
          segment={learnSegments[segModal]}
          role={isInstructor ? "instructor" : "student"}
          onClose={() => setSegModal(null)}
        />
      )}
    </>
  );
}
