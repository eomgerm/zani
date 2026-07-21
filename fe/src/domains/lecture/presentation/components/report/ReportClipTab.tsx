import { color } from "@/shared/lib/theme";
import { summarySections, transcript } from "../../fixtures";

/** 리포트 탭 1 (수업 클립 / 복습 클립): 강의 영상 + 수업 내용 전사 + AI 요약 문서. */
export function ReportClipTab({ title }: { title: string }) {
  return (
    <>
      <div style={{ display: "grid", gridTemplateColumns: "1.35fr 1fr", gap: 20, alignItems: "stretch", marginBottom: 20 }}>
        {/* 강의 영상 */}
        <div style={{ background: "#161a2e", borderRadius: 16, overflow: "hidden", boxShadow: "0 8px 30px rgba(20,25,50,.22)", display: "flex", flexDirection: "column" }}>
          <div style={{ position: "relative", aspectRatio: "16/9", background: "linear-gradient(120deg,#1c2036,#20263f 55%,#1a1f34)", display: "flex", alignItems: "center", justifyContent: "center" }}>
            <div style={{ width: 66, height: 66, borderRadius: "50%", background: "rgba(255,255,255,.14)", display: "flex", alignItems: "center", justifyContent: "center", backdropFilter: "blur(4px)" }}>
              <span style={{ color: "#fff", fontSize: 22, marginLeft: 5 }}>▶</span>
            </div>
            <div style={{ position: "absolute", left: 22, right: 22, bottom: 18 }}>
              <div style={{ color: "#fff", fontSize: 16, fontWeight: 800, textShadow: "0 2px 8px rgba(0,0,0,.4)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                {title}
              </div>
              <div style={{ color: "#aeb4d8", fontSize: 12, marginTop: 2 }}>강의 다시보기</div>
            </div>
          </div>
          <div style={{ height: 4, background: "#2f344f" }}>
            <div style={{ height: "100%", width: "34%", background: color.purple }} />
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 16, padding: "12px 16px", color: "#c7ccf0" }}>
            <span style={{ fontSize: 15 }}>▶</span>
            <span style={{ fontSize: 15 }}>⏭</span>
            <span style={{ fontSize: 14 }}>🔊</span>
            <span style={{ fontFamily: "var(--font-space-mono), monospace", fontSize: 12.5, color: "#aeb4d8" }}>42:30 / 2:05:30</span>
            <span style={{ flex: 1 }} />
            <span style={{ fontSize: 12.5, fontWeight: 700 }}>1.0x</span>
            <span style={{ fontSize: 14 }}>⛶</span>
          </div>
        </div>

        {/* 수업 내용 전사 */}
        <div style={{ position: "relative", minHeight: 220 }}>
          <div style={{ position: "absolute", inset: 0, display: "flex", flexDirection: "column", background: "#fff", border: `1px solid ${color.border}`, borderRadius: 16, boxShadow: "0 4px 22px rgba(24,74,62,.05)", overflow: "hidden" }}>
            <div style={{ padding: "15px 18px", borderBottom: `1px solid ${color.borderLight}`, display: "flex", alignItems: "center", gap: 8, fontWeight: 800, fontSize: 15, flexShrink: 0 }}>
              ☰ 수업 내용
            </div>
            <div style={{ flex: 1, overflowY: "auto", padding: "6px 8px", minHeight: 0 }}>
              {transcript.map((t, i) => (
                <div key={i} style={{ display: "flex", gap: 12, padding: "9px 8px", borderRadius: 9, cursor: "pointer" }}>
                  <span style={{ fontFamily: "var(--font-space-mono), monospace", fontSize: 12, color: color.primary, fontWeight: 700, flexShrink: 0, width: 42 }}>
                    {t.t}
                  </span>
                  <div style={{ fontSize: 13, color: color.textSub, lineHeight: 1.55 }}>
                    <span style={{ fontWeight: 700, color: color.textLabel, marginRight: 6 }}>{t.speaker}</span>
                    {t.text}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* AI 요약 문서 */}
      <div style={{ background: "#fff", border: `1px solid ${color.border}`, borderRadius: 18, boxShadow: "0 4px 22px rgba(24,74,62,.05)", padding: "24px 28px" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8, fontWeight: 800, fontSize: 16, marginBottom: 16 }}>
          <span style={{ color: color.primary }}>📝</span>수업 요약 레포트
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
          {summarySections.map((s) => (
            <div key={s.h}>
              <div style={{ fontWeight: 800, fontSize: 14.5, marginBottom: 6 }}>{s.h}</div>
              <p style={{ margin: 0, color: color.textSub, fontSize: 13.5, lineHeight: 1.75 }}>{s.p}</p>
            </div>
          ))}
        </div>
      </div>
    </>
  );
}
