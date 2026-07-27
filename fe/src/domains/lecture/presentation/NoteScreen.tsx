"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { lectures } from "./fixtures";

/**
 * 수업 종료 후 강사 사후 메모 작성 화면.
 * 분석 시작 시 메모 유무에 따라 다른 확인 모달을 보여준다(시연용 로컬 상태).
 */
export function NoteScreen({ lectureId }: { lectureId: string }) {
  const router = useRouter();
  const lecture = lectures.find((l) => l.id === lectureId) ?? lectures[0];
  const [note, setNote] = useState("");
  const [confirmOpen, setConfirmOpen] = useState(false);

  const isEmpty = !note.trim();

  return (
    <div className="min-h-screen bg-canvas px-6 py-8">
      <div className="mx-auto max-w-[760px]">
        <Link
          href="/my-lectures"
          className="mb-4 inline-flex items-center gap-1.5 text-sm font-bold text-ink-faint no-underline"
        >
          ← 내 강의실로 돌아가기
        </Link>

        <div className="z-card-lg px-8 py-[30px]">
          <div className="z-pill mb-4 bg-warn-soft px-3 py-[5px] text-[12.5px] text-warn-text">
            사후 메모 작성
          </div>
          <h1 className="mb-1.5 text-2xl font-extrabold tracking-[-.4px]">{lecture.title}</h1>
          <p className="mb-[22px] text-sm text-ink-faint">{lecture.date}</p>
          <p className="mb-3.5 text-sm leading-[1.7] text-ink-sub">
            수업에서 중요하게 설명한 내용, 다시 강조하고 싶은 개념, 수업 중 느낀 점이나 AI가 분석할
            때 참고할 내용을 자유롭게 작성해 주세요.
          </p>

          <textarea
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="예) Context 리렌더링 파트에서 학생들이 많이 헷갈려 했다. 다음 시간에 Provider value 참조 개념을 예시로 다시 짚어주면 좋겠다."
            className="z-input z-textarea min-h-[260px] rounded-[14px] bg-faint px-5 py-[18px]"
          />

          <div className="mt-[18px] flex items-center gap-3">
            <div className="flex-1" />
            <button
              onClick={() => setConfirmOpen(true)}
              className="z-btn z-btn-primary z-btn-lg whitespace-nowrap"
            >
              분석 시작
            </button>
          </div>
        </div>
      </div>

      {confirmOpen && (
        <div className="z-backdrop">
          <div className="z-modal max-w-[420px]">
            <h2 className="mb-2.5 text-[19px] font-extrabold">
              {isEmpty ? "메모 없이 분석을 시작할까요?" : "분석을 시작할까요?"}
            </h2>
            <p className="mb-[22px] text-sm leading-[1.6] text-ink-muted">
              {isEmpty
                ? "작성한 사후 메모가 없습니다. 메모 없이도 분석을 진행할 수 있습니다."
                : "분석을 시작하면 사후 메모를 수정할 수 없습니다."}
            </p>
            <div className="flex gap-2.5">
              <button
                onClick={() => setConfirmOpen(false)}
                className="z-btn z-btn-outline flex-1 rounded-[13px] py-[13px]"
              >
                {isEmpty ? "계속 작성" : "취소"}
              </button>
              <button
                onClick={() => router.push("/my-lectures")}
                className="z-btn z-btn-primary flex-1 rounded-[13px] py-[13px]"
              >
                {isEmpty ? "메모 없이 분석 시작" : "분석 시작"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
