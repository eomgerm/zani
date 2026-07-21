"use client";

import { useState } from "react";
import Link from "next/link";
import { color } from "@/shared/lib/theme";
import { lectures } from "./fixtures";

const TOTAL_SECONDS = 2 * 3600 + 5 * 60 + 30; // 2:05:30

function pctToTime(p: number) {
  const tot = Math.round((p / 100) * TOTAL_SECONDS);
  const hh = Math.floor(tot / 3600);
  const mm = Math.floor((tot % 3600) / 60);
  const ss = tot % 60;
  const pad = (n: number) => String(n).padStart(2, "0");
  return hh > 0 ? `${hh}:${pad(mm)}:${pad(ss)}` : `${pad(mm)}:${pad(ss)}`;
}

/**
 * 영상 편집기 · 구간 컷. 타임라인에서 구간을 선택해 잘라낸다.
 * 실제 영상 처리는 붙이지 않았고 선택/삭제 구간만 로컬 상태로 관리한다.
 */
export function VideoEditorScreen({ lectureId }: { lectureId: string }) {
  const lecture = lectures.find((l) => l.id === lectureId) ?? lectures[2];
  const [start, setStart] = useState(20);
  const [end, setEnd] = useState(35);
  const [cuts, setCuts] = useState<{ s: number; e: number }[]>([]);

  const lo = Math.min(start, end);
  const hi = Math.max(start, end);
  const removed = cuts.reduce((sum, c) => sum + (c.e - c.s), 0);
  const remainPct = Math.max(0, 100 - removed);

  const applyCut = () => {
    if (hi - lo <= 0) return;
    setCuts((prev) => [...prev, { s: lo, e: hi }]);
  };

  return (
    <div style={{ minHeight: "100vh", background: color.inkPanel, color: color.inkText, display: "flex", flexDirection: "column" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 14, padding: "16px 24px", borderBottom: `1px solid ${color.inkBorder}` }}>
        <Link href={`/my-lectures/${lecture.id}/report`} style={darkBtn}>
          ←
        </Link>
        <div style={{ flex: 1 }}>
          <div style={{ fontWeight: 800, fontSize: 17 }}>영상 편집기 · 구간 컷</div>
          <div style={{ color: color.inkTextMuted, fontSize: 12.5 }}>{lecture.title} · 2:05:30</div>
        </div>
        <Link href={`/my-lectures/${lecture.id}/report`} style={{ ...darkBtn, width: "auto", padding: "11px 18px", fontSize: 13.5, fontWeight: 800 }}>
          취소
        </Link>
        <button
          style={{ padding: "11px 20px", borderRadius: 11, border: "none", background: color.primary, color: "#fff", fontWeight: 800, cursor: "pointer", fontSize: 13.5, fontFamily: "inherit" }}
        >
          저장
        </button>
      </div>

      <div style={{ flex: 1, display: "flex", flexDirection: "column", maxWidth: 1200, width: "100%", margin: "0 auto", padding: "26px 24px", gap: 22, boxSizing: "border-box" }}>
        {/* 미리보기 */}
        <div style={{ background: "#161a2e", border: `1px solid ${color.inkBorder}`, borderRadius: 16, aspectRatio: "16/7", display: "flex", alignItems: "center", justifyContent: "center", position: "relative", overflow: "hidden" }}>
          <div style={{ fontFamily: "var(--font-space-mono), monospace", color: "#54eab0", fontSize: 30, fontWeight: 800 }}>
            {lecture.title}
          </div>
          <span style={{ position: "absolute", bottom: 16, left: "50%", transform: "translateX(-50%)", width: 54, height: 54, borderRadius: "50%", background: "#ffffff22", display: "flex", alignItems: "center", justifyContent: "center", color: "#fff", fontSize: 20, cursor: "pointer" }}>
            ▶
          </span>
        </div>

        {/* 타임라인 */}
        <div style={{ background: color.inkPanel2, border: `1px solid ${color.inkBorder}`, borderRadius: 16, padding: "20px 22px" }}>
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 16, flexWrap: "wrap", gap: 10 }}>
            <div style={{ fontWeight: 800, fontSize: 14.5 }}>
              타임라인 · 자를 구간을 선택하세요{" "}
              <span style={{ color: color.inkTextMuted, fontWeight: 600, fontSize: 12.5, marginLeft: 6 }}>
                선택 {pctToTime(lo)} – {pctToTime(hi)}
              </span>
            </div>
            <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <span style={{ fontSize: 12, color: color.inkTextMuted }}>
                남은 길이 <b style={{ color: "#54eab0" }}>{pctToTime(remainPct)}</b>
              </span>
              <button
                onClick={applyCut}
                style={{ padding: "8px 14px", borderRadius: 9, border: "none", background: color.red, color: "#fff", fontWeight: 800, cursor: "pointer", fontSize: 12.5, fontFamily: "inherit" }}
              >
                ✂ 선택 구간 자르기
              </button>
            </div>
          </div>

          <div style={{ position: "relative", height: 64, background: color.inkPanel, borderRadius: 10, overflow: "hidden", border: `1px solid ${color.inkBorder}` }}>
            <div style={{ position: "absolute", inset: 0, display: "flex", alignItems: "center", gap: 2, padding: "0 4px" }}>
              <div style={{ flex: 1, height: 38, background: "repeating-linear-gradient(90deg,#2a2f4e,#2a2f4e 3px,#232744 3px,#232744 6px)", borderRadius: 4 }} />
            </div>
            {cuts.map((c, i) => (
              <div
                key={i}
                style={{ position: "absolute", top: 0, bottom: 0, left: `${c.s}%`, width: `${c.e - c.s}%`, background: "#e0455f55", borderLeft: `2px solid ${color.red}`, borderRight: `2px solid ${color.red}` }}
              />
            ))}
            <div
              style={{ position: "absolute", top: 0, bottom: 0, left: `${lo}%`, width: `${hi - lo}%`, background: "#7c6bf033", border: `2px solid ${color.purple}`, borderRadius: 6 }}
            />
          </div>

          <div style={{ marginTop: 14, display: "flex", flexDirection: "column", gap: 10 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <span style={{ fontSize: 11.5, color: color.inkTextMuted, width: 60, flexShrink: 0 }}>시작 {start}%</span>
              <input type="range" min={0} max={100} value={start} onChange={(e) => setStart(+e.target.value)} style={{ flex: 1, accentColor: color.purple, cursor: "pointer" }} />
            </div>
            <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <span style={{ fontSize: 11.5, color: color.inkTextMuted, width: 60, flexShrink: 0 }}>끝 {end}%</span>
              <input type="range" min={0} max={100} value={end} onChange={(e) => setEnd(+e.target.value)} style={{ flex: 1, accentColor: color.purple, cursor: "pointer" }} />
            </div>
          </div>

          <div style={{ display: "flex", justifyContent: "space-between", fontSize: 11, color: color.inkTextMuted, marginTop: 8, fontFamily: "var(--font-space-mono), monospace" }}>
            <span>00:00</span>
            <span>30:00</span>
            <span>1:00:00</span>
            <span>1:30:00</span>
            <span>2:05:30</span>
          </div>

          {cuts.length > 0 && (
            <div style={{ marginTop: 16 }}>
              <div style={{ fontSize: 12, color: color.inkTextMuted, marginBottom: 8 }}>잘라낸 구간</div>
              <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
                {cuts.map((c, i) => (
                  <div key={i} style={{ display: "flex", alignItems: "center", gap: 8, background: "#2a1e2c", border: "1px solid #5a3040", borderRadius: 9, padding: "8px 12px", fontSize: 12.5 }}>
                    <span style={{ color: "#ff8a9f", fontWeight: 800, fontFamily: "var(--font-space-mono), monospace" }}>
                      {pctToTime(c.s)} – {pctToTime(c.e)}
                    </span>
                    <button
                      onClick={() => setCuts((prev) => prev.filter((_, j) => j !== i))}
                      style={{ border: "none", background: "none", color: "#ff8a9f", cursor: "pointer" }}
                    >
                      ✕
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

const darkBtn = {
  width: 38,
  height: 38,
  borderRadius: 11,
  border: `1px solid ${color.inkBorderSoft}`,
  background: "#1c2036",
  cursor: "pointer",
  color: color.inkTextFaint,
  fontSize: 16,
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  textDecoration: "none",
  flexShrink: 0,
} as const;
