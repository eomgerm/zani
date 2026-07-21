"use client";

import { useState } from "react";
import Link from "next/link";
import { color } from "@/shared/lib/theme";
import { learnSegments, lectures } from "./fixtures";
import { ReportClipTab } from "./components/report/ReportClipTab";
import { InstructorReport } from "./components/report/InstructorReport";
import { StudentReport } from "./components/report/StudentReport";
import { SegmentModal } from "./components/report/SegmentModal";

function tabStyle(active: boolean) {
  return {
    padding: "13px 22px",
    border: "none",
    background: "none",
    cursor: "pointer",
    fontFamily: "inherit",
    fontWeight: 800,
    fontSize: 14.5,
    color: active ? color.primary : color.textFaint,
    borderBottom: `2px solid ${active ? color.primary : "transparent"}`,
    marginBottom: -1,
  } as const;
}

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
        style={{
          display: "inline-flex",
          alignItems: "center",
          gap: 7,
          color: color.textSub,
          fontSize: 14,
          marginBottom: 16,
          fontWeight: 800,
          textDecoration: "none",
        }}
      >
        ← 내 강의실
      </Link>

      <div style={{ display: "flex", alignItems: "center", gap: 16, marginBottom: 20 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <h1 style={{ fontSize: 24, fontWeight: 800, margin: "0 0 4px", letterSpacing: "-.5px" }}>{lecture.title}</h1>
          <div style={{ color: color.textFainter, fontSize: 13.5, fontWeight: 600 }}>{meta}</div>
        </div>
        {!failed && (
          <button
            style={{
              display: "inline-flex",
              alignItems: "center",
              gap: 8,
              padding: "13px 22px",
              borderRadius: 12,
              border: "none",
              background: color.primary,
              color: "#fff",
              fontWeight: 800,
              cursor: "pointer",
              fontSize: 14,
              flexShrink: 0,
              fontFamily: "inherit",
            }}
          >
            ⭳ 리포트 다운로드
          </button>
        )}
      </div>

      {failed && (
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 14,
            background: color.primarySofter,
            border: `1px solid ${color.borderMuted}`,
            borderRadius: 16,
            padding: "20px 22px",
            marginBottom: 8,
          }}
        >
          <span style={{ width: 44, height: 44, borderRadius: 12, background: color.redSoft, color: color.red, display: "flex", alignItems: "center", justifyContent: "center", fontSize: 20, flexShrink: 0 }}>
            ⚠️
          </span>
          <div style={{ flex: 1 }}>
            <div style={{ fontWeight: 800 }}>결과를 생성하지 못했어요</div>
            <div style={{ color: color.textMuted, fontSize: 13 }}>
              분석 중 문제가 발생했어요. 잠시 후 다시 시도하거나 지원팀에 문의해 주세요.
            </div>
          </div>
          <Link
            href="/my-lectures"
            style={{ padding: "11px 18px", borderRadius: 12, border: `1px solid ${color.borderMuted}`, background: "#fff", color: color.textMuted, fontWeight: 800, fontSize: 14, textDecoration: "none" }}
          >
            내 강의실로
          </Link>
        </div>
      )}

      {/* 탭 */}
      <div style={{ display: "flex", borderBottom: `1px solid ${color.border}`, margin: "22px 0" }}>
        <button onClick={() => setTab("clip")} style={tabStyle(tab === "clip")}>
          {isInstructor ? "수업 클립" : "복습 클립"}
        </button>
        <button onClick={() => setTab("report")} style={tabStyle(tab === "report")}>
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
