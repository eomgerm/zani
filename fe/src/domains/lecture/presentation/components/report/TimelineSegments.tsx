import { color } from "@/shared/lib/theme";
import { focusColor, focusBg, type LearnSegment } from "../../fixtures";

interface TimelineSegmentsProps {
  segments: LearnSegment[];
  role: "instructor" | "student";
  activeSeg: number;
  onSelect: (i: number) => void;
}

/** 리포트 타임라인 구간 카드 가로 스크롤. 구간을 누르면 상세 모달을 연다. */
export function TimelineSegments({ segments, role, activeSeg, onSelect }: TimelineSegmentsProps) {
  return (
    <div style={{ display: "flex", gap: 10, overflowX: "auto", paddingBottom: 6 }}>
      {segments.map((s, i) => {
        const score = role === "instructor" ? s.fAll : s.fMine;
        const active = i === activeSeg;
        const c = focusColor(score);
        return (
          <div
            key={i}
            onClick={() => onSelect(i)}
            style={{
              flexShrink: 0,
              width: 168,
              border: `1px solid ${active ? "#c6eedf" : color.borderLight}`,
              background: active ? color.primarySofter : "#fff",
              borderRadius: 13,
              padding: "13px 14px",
              cursor: "pointer",
            }}
          >
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 9 }}>
              <span style={{ fontFamily: "var(--font-space-mono), monospace", fontSize: 11, color: color.textFainter, fontWeight: 700 }}>
                {s.range}
              </span>
              <span
                style={{
                  width: 26,
                  height: 26,
                  borderRadius: "50%",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  fontSize: 12,
                  fontWeight: 900,
                  color: c,
                  background: focusBg(score),
                  border: `1.5px solid ${c}`,
                }}
              >
                {score}
              </span>
            </div>
            <div style={{ fontSize: 12.5, fontWeight: 800, color: "#3a3f5c", lineHeight: 1.4 }}>{s.title}</div>
          </div>
        );
      })}
    </div>
  );
}
