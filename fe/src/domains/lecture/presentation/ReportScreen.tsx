"use client";

import { useState } from "react";
import Link from "next/link";
import { learnSegments, lectures } from "./fixtures";
import { ReportClipTab } from "./components/report/ReportClipTab";
import { InstructorReport } from "./components/report/InstructorReport";
import { StudentReport } from "./components/report/StudentReport";
import { SegmentModal } from "./components/report/SegmentModal";

const tabCls = (active: boolean) =>
  `-mb-px cursor-pointer border-0 border-b-2 bg-transparent px-[22px] py-[13px] font-sans text-[14.5px] font-extrabold ${
    active ? "border-primary text-primary" : "border-transparent text-ink-faint"
  }`;

/**
 * SC-06 강의 리포트. 역할(강사/학생)에 따라 클립 탭과 리포트 탭을 보여준다.
 * 탭 전환·구간 선택·상세 모달은 시연용 로컬 상태로 동작한다.
 */
export function ReportScreen({ lectureId }: { lectureId: string }) {
  const lecture = lectures.find((l) => l.id === lectureId) ?? lectures[0];
  const isInstructor = lecture.role === "instructor";
  const failed = lecture.status === "FAILED";

  const [tab, setTab] = useState<"clip" | "report">("clip");
  const [activeSeg, setActiveSeg] = useState(2);
  const [segModal, setSegModal] = useState<number | null>(null);

  const onSelectSeg = (i: number) => {
    setActiveSeg(i);
    setSegModal(i);
  };

  const meta = isInstructor
    ? `수강생 ${lecture.students ?? 0}명 · ${lecture.date} · ${lecture.dur}`
    : `강사 ${lecture.instructor ?? "박서준"} · ${lecture.date} · ${lecture.dur}`;

  return (
    <>
      <Link
        href="/my-lectures"
        className="mb-4 inline-flex items-center gap-[7px] text-sm font-extrabold text-ink-sub no-underline"
      >
        ← 내 강의실
      </Link>

      <div className="mb-5 flex items-center gap-4">
        <div className="min-w-0 flex-1">
          <h1 className="mb-1 text-2xl font-extrabold tracking-[-.5px]">{lecture.title}</h1>
          <div className="text-[13.5px] font-semibold text-ink-fainter">{meta}</div>
        </div>
        {!failed && (
          <button className="z-btn z-btn-primary z-btn-md shrink-0">⭳ 리포트 다운로드</button>
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

      {/* 탭 */}
      <div className="my-[22px] flex border-b border-line">
        <button onClick={() => setTab("clip")} className={tabCls(tab === "clip")}>
          {isInstructor ? "수업 클립" : "복습 클립"}
        </button>
        <button onClick={() => setTab("report")} className={tabCls(tab === "report")}>
          {isInstructor ? "수업 리포트" : "학습 리포트"}
        </button>
      </div>

      {tab === "clip" ? (
        <ReportClipTab title={lecture.title} />
      ) : isInstructor ? (
        <InstructorReport activeSeg={activeSeg} onSelect={onSelectSeg} />
      ) : (
        <StudentReport lectureId={lecture.id} activeSeg={activeSeg} onSelect={onSelectSeg} />
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
