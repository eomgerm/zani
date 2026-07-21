import { color } from "@/shared/lib/theme";
import { focusColor, focusBg, focusLabel, type LearnSegment } from "../../fixtures";

interface SegmentModalProps {
  segment: LearnSegment;
  role: "instructor" | "student";
  onClose: () => void;
}

/** 타임라인 구간 상세 모달. 집중 평가 · 구간 설명 · 복습 클립 바로가기. */
export function SegmentModal({ segment, role, onClose }: SegmentModalProps) {
  const score = role === "instructor" ? segment.fAll : segment.fMine;
  const c = focusColor(score);
  const ev = role === "instructor" ? segment.evAll : segment.evMine;
  const scopeLabel = role === "instructor" ? "전체 집중" : "내 집중";

  return (
    <div
      onClick={onClose}
      style={{
        position: "fixed",
        inset: 0,
        background: "rgba(24,28,52,.5)",
        zIndex: 80,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: 24,
        animation: "zPop .16s",
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{ width: "100%", maxWidth: 560, maxHeight: "88vh", overflowY: "auto", background: "#fff", borderRadius: 20, boxShadow: "0 30px 70px rgba(24,28,52,.4)" }}
      >
        <div style={{ position: "relative", padding: "24px 26px 4px" }}>
          <button onClick={onClose} style={{ position: "absolute", right: 18, top: 18, width: 32, height: 32, borderRadius: 9, border: "none", background: color.primarySofter, color: color.textFaint, fontSize: 15, cursor: "pointer" }}>
            ✕
          </button>
          <div style={{ fontSize: 12.5, fontWeight: 800, color: "#3bbd8b", marginBottom: 6 }}>선택 구간 · {segment.range}</div>
          <div style={{ fontSize: 21, fontWeight: 800, letterSpacing: "-.4px", color: color.text }}>{segment.title}</div>
        </div>
        <div style={{ padding: "18px 26px 26px" }}>
          <div style={{ border: `1px solid ${color.borderLight}`, borderRadius: 14, padding: "16px 18px", marginBottom: 16 }}>
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 10, marginBottom: 12 }}>
              <span style={{ fontSize: 12.5, fontWeight: 800, color: color.textFaint }}>{scopeLabel} 평가</span>
              <span style={{ display: "inline-flex", alignItems: "baseline", gap: 4, color: c }}>
                <b style={{ fontSize: 26, fontWeight: 900, letterSpacing: "-.5px", fontFamily: "var(--font-space-grotesk), monospace" }}>{score}</b>
                <span style={{ fontSize: 13, fontWeight: 800, color: color.textFainter }}>/ 4</span>
              </span>
            </div>
            <div style={{ height: 9, borderRadius: 999, background: "#eef0f6", overflow: "hidden", marginBottom: 11 }}>
              <div style={{ height: "100%", width: `${(score / 4) * 100}%`, background: c, borderRadius: 999 }} />
            </div>
            <div style={{ display: "flex", alignItems: "center", gap: 9 }}>
              <span style={{ fontSize: 11.5, fontWeight: 800, padding: "3px 10px", borderRadius: 999, color: c, background: focusBg(score) }}>
                {focusLabel(score)}
              </span>
              <span style={{ fontSize: 12.5, color: color.textSub, lineHeight: 1.5, flex: 1 }}>{ev}</span>
            </div>
          </div>
          <div style={{ fontWeight: 800, fontSize: 13.5, color: color.text, marginBottom: 8 }}>이 구간 설명</div>
          <p style={{ margin: "0 0 22px", color: color.textSub, fontSize: 13.5, lineHeight: 1.7 }}>{segment.desc}</p>
          <button
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              gap: 8,
              width: "100%",
              padding: 14,
              borderRadius: 13,
              border: "none",
              background: color.primary,
              color: "#fff",
              fontFamily: "inherit",
              fontWeight: 800,
              fontSize: 14.5,
              cursor: "pointer",
            }}
          >
            ↗ {segment.seek} 복습 클립 바로가기
          </button>
        </div>
      </div>
    </div>
  );
}
