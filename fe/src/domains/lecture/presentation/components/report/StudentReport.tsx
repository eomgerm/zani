import Link from "next/link";
import { color } from "@/shared/lib/theme";
import { Badge, Card, FocusFlowChart } from "@/shared/ui";
import { learnSegments, recommendations, studentGlance, studentSummary } from "../../fixtures";
import { TimelineSegments } from "./TimelineSegments";

interface Props {
  lectureId: string;
  activeSeg: number;
  onSelect: (i: number) => void;
}

/** 리포트 탭 2 (학생) — 한눈에 보기 · 참여도 요약 · 집중 흐름 · 타임라인 · 복습 추천 + 퀴즈. */
export function StudentReport({ lectureId, activeSeg, onSelect }: Props) {
  const flow = learnSegments.map((s) => ({ range: s.range, score: s.fMine }));

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 20 }}>
      <Card padding="22px 24px">
        <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 16 }}>
          <span style={{ color: color.primary }}>📊</span>
          <span style={{ fontWeight: 800, fontSize: 16 }}>한눈에 보기</span>
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(4,1fr)", gap: 12 }}>
          {studentGlance.map((g) => (
            <div key={g.label} style={{ border: `1px solid ${color.borderLight}`, borderRadius: 14, padding: 16 }}>
              <div style={{ fontSize: 12, color: color.textFaint, marginBottom: 9 }}>{g.label}</div>
              <div style={{ fontSize: 24, fontWeight: 900, letterSpacing: "-.5px" }}>{g.value}</div>
            </div>
          ))}
        </div>
      </Card>

      <Card padding="22px 24px">
        <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 14 }}>
          <span style={{ color: color.primary }}>✨</span>
          <span style={{ fontWeight: 800, fontSize: 16 }}>수업 참여도 요약</span>
          <span style={{ fontSize: 11.5, color: color.textFainter }}>AI가 분석한 전반적인 수업 참여도예요.</span>
        </div>
        <div style={{ background: color.bg, borderRadius: 12, padding: "16px 18px" }}>
          <p style={{ margin: 0, color: color.textSub, fontSize: 13, lineHeight: 1.75 }}>{studentSummary}</p>
        </div>
      </Card>

      <Card padding="22px 24px 14px">
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: 10, marginBottom: 6 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8, fontWeight: 800, fontSize: 16 }}>
            <span style={{ color: color.primary }}>📈</span>집중 흐름
          </div>
          <div style={{ display: "flex", gap: 14, fontSize: 12, color: color.textMuted, flexWrap: "wrap", fontWeight: 700 }}>
            <span style={{ fontSize: 11, fontWeight: 800, color: color.primaryDeep, background: "#eaf7f2", padding: "3px 10px", borderRadius: 999 }}>내 집중</span>
            <span style={{ display: "flex", alignItems: "center", gap: 7 }}>
              <span style={{ width: 36, height: 8, borderRadius: 999, background: "linear-gradient(90deg,#e0455f,#f4c325,#16c582)" }} />0 낮음 → 4 높음
            </span>
          </div>
        </div>
        <FocusFlowChart segments={flow} activeSeg={activeSeg} onSelect={onSelect} />
      </Card>

      <Card padding="22px 24px">
        <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap", marginBottom: 4 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8, fontWeight: 800, fontSize: 16 }}>
            <span style={{ color: color.primary }}>🎬</span>타임라인
          </div>
          <span style={{ fontSize: 11.5, color: color.textFainter }}>구간을 누르면 집중도 평가와 설명, 복습 클립 바로가기가 열려요.</span>
        </div>
        <div style={{ fontSize: 12, color: color.textFaint, fontWeight: 700, margin: "6px 0 12px" }}>수업 내용 기반 구간 · 내 집중 점수 (0–4)</div>
        <TimelineSegments segments={learnSegments} role="student" activeSeg={activeSeg} onSelect={onSelect} />
      </Card>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 20, alignItems: "start" }}>
        <Card padding="22px 24px">
          <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap", marginBottom: 16 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 8, fontWeight: 800, fontSize: 16 }}>
              <span style={{ color: color.red }}>🎯</span>나의 복습 추천
            </div>
            <span style={{ fontSize: 11.5, color: color.textFainter }}>자기보고 · 질문 · 반복된 확인 필요가 결합된 구간만 골라요 (최대 5개)</span>
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
            {recommendations.map((r) => (
              <div key={r.t} style={{ display: "flex", gap: 14, border: `1px solid ${color.borderMint}`, borderRadius: 13, padding: 12, cursor: "pointer" }}>
                <div style={{ position: "relative", width: 74, height: 50, borderRadius: 9, background: "#20233a", flexShrink: 0, display: "flex", alignItems: "center", justifyContent: "center" }}>
                  <span style={{ width: 26, height: 26, borderRadius: "50%", background: "#ffffffcc", display: "flex", alignItems: "center", justifyContent: "center", color: color.primary, fontSize: 11 }}>▶</span>
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 4, flexWrap: "wrap" }}>
                    <span style={{ fontFamily: "var(--font-space-mono), monospace", fontSize: 11.5, color: color.primary, fontWeight: 800 }}>{r.t}</span>
                    <Badge bg={`${r.color}22`} fg={r.color}>{r.tag}</Badge>
                    <span style={{ fontWeight: 800, fontSize: 13.5 }}>{r.title}</span>
                  </div>
                  <div style={{ color: color.textFaint, fontSize: 12, lineHeight: 1.5 }}>{r.reason}</div>
                </div>
              </div>
            ))}
          </div>
        </Card>

        <Card padding="22px 24px">
          <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 16 }}>
            <span style={{ color: color.primary }}>📋</span>
            <span style={{ fontWeight: 800, fontSize: 16 }}>AI 이해도 퀴즈</span>
            <span style={{ fontSize: 11.5, color: color.textFainter }}>강의 내용과 어려워했던 구간을 바탕으로 AI가 맞춤 퀴즈를 만들었어요.</span>
          </div>
          <div style={{ display: "flex", gap: 18, alignItems: "center", marginBottom: 18 }}>
            <div style={{ flex: 1, background: "linear-gradient(135deg,#e7f7f1,#f2fbf8)", borderRadius: 14, height: 120, display: "flex", alignItems: "center", justifyContent: "center" }}>
              <span style={{ fontSize: 44 }}>📝</span>
            </div>
            <div style={{ flex: 1, display: "flex", flexDirection: "column", gap: 10 }}>
              {["📄 총 5문제", "🕐 약 3분", "⭐ 주요 개념 3개"].map((t) => (
                <div key={t} style={{ display: "flex", alignItems: "center", gap: 9, background: color.bg, borderRadius: 11, padding: "12px 14px", fontSize: 13, fontWeight: 700, color: color.textLabel }}>
                  {t}
                </div>
              ))}
            </div>
          </div>
          <Link
            href={`/my-lectures/${lectureId}/quiz`}
            style={{ display: "block", width: "100%", padding: 15, borderRadius: 13, border: "none", background: color.primary, color: "#fff", fontWeight: 800, fontSize: 15, textAlign: "center", textDecoration: "none" }}
          >
            퀴즈 풀어보기 ›
          </Link>
        </Card>
      </div>
    </div>
  );
}
