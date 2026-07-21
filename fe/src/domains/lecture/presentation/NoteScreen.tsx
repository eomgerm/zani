"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { color } from "@/shared/lib/theme";
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
  const meta = `수강생 ${lecture.students ?? 0}명 · ${lecture.date} · ${lecture.dur}`;

  return (
    <div style={{ minHeight: "100vh", background: color.bg, padding: "32px 24px" }}>
      <div style={{ maxWidth: 760, margin: "0 auto" }}>
        <Link
          href="/my-lectures"
          style={{ display: "inline-flex", alignItems: "center", gap: 6, color: color.textFaint, fontSize: 14, marginBottom: 16, fontWeight: 700, textDecoration: "none" }}
        >
          ← 내 강의실로 돌아가기
        </Link>

        <div style={{ background: "#fff", border: `1px solid ${color.border}`, borderRadius: 20, padding: "30px 32px", boxShadow: "0 4px 22px rgba(24,74,62,.05)" }}>
          <div style={{ display: "inline-flex", alignItems: "center", gap: 8, background: color.amberSoft, color: color.amberText, padding: "5px 12px", borderRadius: 999, fontSize: 12.5, fontWeight: 800, marginBottom: 16 }}>
            ✏️ 사후 메모 작성
          </div>
          <h1 style={{ fontSize: 24, fontWeight: 800, margin: "0 0 6px", letterSpacing: "-.4px" }}>{lecture.title}</h1>
          <p style={{ color: color.textFaint, margin: "0 0 22px", fontSize: 14 }}>{meta}</p>
          <p style={{ color: color.textSub, fontSize: 14, lineHeight: 1.7, margin: "0 0 14px" }}>
            수업에서 중요하게 설명한 내용, 다시 강조하고 싶은 개념, 수업 중 느낀 점이나 AI가 분석할 때 참고할 내용을
            자유롭게 작성해 주세요.
          </p>
          <textarea
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="예) Context 리렌더링 파트에서 학생들이 많이 헷갈려 했다. 다음 시간에 Provider value 참조 개념을 예시로 다시 짚어주면 좋겠다."
            style={{
              width: "100%",
              minHeight: 260,
              padding: "18px 20px",
              border: `1px solid ${color.borderMuted}`,
              borderRadius: 14,
              fontSize: 14.5,
              lineHeight: 1.7,
              background: color.surfaceFaint,
              outline: "none",
              resize: "vertical",
              fontFamily: "inherit",
              color: color.text,
              boxSizing: "border-box",
            }}
          />
          <div style={{ display: "flex", alignItems: "center", gap: 12, marginTop: 18 }}>
            <p style={{ flex: 1, margin: 0, fontSize: 12.5, color: "#a7adcb", lineHeight: 1.5 }}>
              AI가 녹화 영상·전사·수업 이벤트·채팅·학생 반응과 함께 메모를 분석해 관련 영상 구간을 자동으로 연결해요.
            </p>
            <button
              onClick={() => setConfirmOpen(true)}
              style={{
                padding: "14px 26px",
                borderRadius: 14,
                border: "none",
                background: color.primary,
                color: "#fff",
                fontWeight: 800,
                fontSize: 15,
                cursor: "pointer",
                whiteSpace: "nowrap",
                fontFamily: "inherit",
              }}
            >
              분석 시작
            </button>
          </div>
        </div>
      </div>

      {confirmOpen && (
        <div style={{ position: "fixed", inset: 0, background: "rgba(28,32,58,.42)", display: "flex", alignItems: "center", justifyContent: "center", padding: 24, zIndex: 100 }}>
          <div style={{ width: "100%", maxWidth: 420, background: "#fff", borderRadius: 20, padding: "26px 28px", boxShadow: "0 24px 60px rgba(28,32,58,.35)", animation: "zPop .18s" }}>
            <h2 style={{ fontSize: 19, fontWeight: 800, margin: "0 0 10px" }}>
              {isEmpty ? "메모 없이 분석을 시작할까요?" : "분석을 시작할까요?"}
            </h2>
            <p style={{ color: color.textMuted, margin: "0 0 22px", fontSize: 14, lineHeight: 1.6 }}>
              {isEmpty
                ? "작성한 사후 메모가 없습니다. 메모 없이도 분석을 진행할 수 있습니다."
                : "분석을 시작하면 사후 메모를 수정할 수 없습니다."}
            </p>
            <div style={{ display: "flex", gap: 10 }}>
              <button
                onClick={() => setConfirmOpen(false)}
                style={{ flex: 1, padding: 13, borderRadius: 13, border: `1px solid ${color.borderMuted}`, background: "#fff", color: color.textMuted, fontWeight: 800, cursor: "pointer", fontFamily: "inherit" }}
              >
                {isEmpty ? "계속 작성" : "취소"}
              </button>
              <button
                onClick={() => router.push("/my-lectures")}
                style={{ flex: 1, padding: 13, borderRadius: 13, border: "none", background: color.primary, color: "#fff", fontWeight: 800, cursor: "pointer", fontFamily: "inherit" }}
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
