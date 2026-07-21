import { color } from "@/shared/lib/theme";
import { Card, FocusFlowChart, EvalDonuts, StatCard } from "@/shared/ui";
import {
  evalDonutData,
  improveTips,
  insights,
  instructorGlance,
  instructorSummary,
  learnSegments,
} from "../../fixtures";
import { TimelineSegments } from "./TimelineSegments";

interface Props {
  activeSeg: number;
  onSelect: (i: number) => void;
}

/** 리포트 탭 2 (강사) — 한눈에 보기 · 학습 흐름 · 타임라인 · 인사이트 · AI 수업 피드백. */
export function InstructorReport({ activeSeg, onSelect }: Props) {
  const flow = learnSegments.map((s) => ({ range: s.range, score: s.fAll }));

  return (
    <>
      <Card padding="22px 24px" style={{ marginBottom: 20 }}>
        <div style={{ fontWeight: 800, fontSize: 17, marginBottom: 18 }}>한눈에 보기</div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(5,1fr)", gap: 14 }}>
          {instructorGlance.map((g) => (
            <StatCard
              key={g.label}
              label={
                <>
                  <span style={{ color: g.iconColor }}>{g.icon}</span>
                  {g.label}
                </>
              }
              value={g.value}
              suffix={
                g.badge ? (
                  <span style={{ fontSize: 10.5, fontWeight: 800, color: color.amberText, background: "#fdf6df", padding: "2px 7px", borderRadius: 6 }}>
                    {g.badge}
                  </span>
                ) : undefined
              }
            />
          ))}
        </div>
      </Card>

      <Card padding="22px 24px 14px" style={{ marginBottom: 20 }}>
        <FlowHeader />
        <FocusFlowChart segments={flow} activeSeg={activeSeg} onSelect={onSelect} />
      </Card>

      <Card padding="22px 24px" style={{ marginBottom: 20 }}>
        <TimelineHeader />
        <TimelineSegments segments={learnSegments} role="instructor" activeSeg={activeSeg} onSelect={onSelect} />
      </Card>

      <Card padding="22px 24px" style={{ marginBottom: 20 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8, fontWeight: 800, fontSize: 16, marginBottom: 18 }}>
          <span style={{ color: color.primary }}>💡</span>인사이트
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(4,1fr)", gap: 14 }}>
          {insights.map((it, i) => (
            <div key={i} style={{ border: `1px solid ${color.borderLight}`, borderRadius: 14, padding: 16, background: color.surfaceFaint }}>
              <div style={{ width: 34, height: 34, borderRadius: 10, background: it.bg, display: "flex", alignItems: "center", justifyContent: "center", marginBottom: 11 }}>
                {it.icon}
              </div>
              <div style={{ fontSize: 12.5, color: color.textLabel, lineHeight: 1.55, fontWeight: 600 }}>{it.text}</div>
            </div>
          ))}
        </div>
      </Card>

      <Card padding="22px 24px" style={{ marginBottom: 20 }}>
        <div style={{ fontWeight: 800, fontSize: 16, marginBottom: 14 }}>AI 수업 피드백</div>
        <div style={{ background: color.bg, borderRadius: 12, padding: "16px 20px", marginBottom: 22 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8, fontWeight: 800, fontSize: 13.5, marginBottom: 8 }}>
            <span style={{ color: color.primary }}>✨</span>종합 포인트
          </div>
          <p style={{ margin: 0, color: color.textSub, fontSize: 13, lineHeight: 1.75 }}>{instructorSummary}</p>
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 26, alignItems: "start" }}>
          <div>
            <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 14 }}>
              <span style={{ fontWeight: 800, fontSize: 14 }}>분야별 평가</span>
              <span style={{ fontSize: 11.5, color: color.textFainter }}>AI가 4개 항목으로 평가했어요.</span>
            </div>
            <div style={{ background: color.surfaceFaint, border: "1px solid #f1f2f8", borderRadius: 14, padding: "18px 16px" }}>
              <EvalDonuts data={evalDonutData} />
            </div>
          </div>
          <div>
            <div style={{ fontWeight: 800, fontSize: 14, marginBottom: 14 }}>수업 개선 TIP</div>
            <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
              {improveTips.map((t) => (
                <div key={t.title} style={{ border: `1px solid ${color.borderLight}`, borderRadius: 13, padding: "14px 16px" }}>
                  <div style={{ display: "flex", alignItems: "center", gap: 7, fontWeight: 800, fontSize: 12.5, marginBottom: 6 }}>
                    <span style={{ color: t.color }}>{t.icon}</span>
                    {t.title}
                  </div>
                  <p style={{ margin: "0 0 6px", fontSize: 11.5, color: color.textFaint, lineHeight: 1.5 }}>{t.obs}</p>
                  <div style={{ fontSize: 11, color: color.textSub, lineHeight: 1.5 }}>{t.tip}</div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </Card>
    </>
  );
}

function FlowHeader() {
  return (
    <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: 10, marginBottom: 6 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8, fontWeight: 800, fontSize: 16 }}>
        <span style={{ color: color.primary }}>📈</span>학습 흐름
      </div>
      <div style={{ display: "flex", gap: 14, fontSize: 12, color: color.textMuted, flexWrap: "wrap", fontWeight: 700 }}>
        <span style={{ fontSize: 11, fontWeight: 800, color: color.primaryDeep, background: "#eaf7f2", padding: "3px 10px", borderRadius: 999 }}>전체 집중</span>
        <span style={{ display: "flex", alignItems: "center", gap: 7 }}>
          <span style={{ width: 36, height: 8, borderRadius: 999, background: "linear-gradient(90deg,#e0455f,#f4c325,#16c582)" }} />0 낮음 → 4 높음
        </span>
      </div>
    </div>
  );
}

function TimelineHeader() {
  return (
    <>
      <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap", marginBottom: 4 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8, fontWeight: 800, fontSize: 16 }}>
          <span style={{ color: color.primary }}>🎬</span>타임라인
        </div>
        <span style={{ fontSize: 11.5, color: color.textFainter }}>구간을 누르면 집중도 평가와 설명, 복습 클립 바로가기가 열려요.</span>
      </div>
      <div style={{ fontSize: 12, color: color.textFaint, fontWeight: 700, margin: "6px 0 12px" }}>수업 내용 기반 구간 · 전체 집중 점수 (0–4)</div>
    </>
  );
}
