import Link from "next/link";
import { color, shadow } from "@/shared/lib/theme";
import { thumbPalette, type Lecture } from "../fixtures";
import { statusInfo } from "../status";

/** 상태에 따라 카드 클릭 시 이동할 경로 (LIVE→강의실, 그 외→리포트) */
function hrefFor(l: Lecture) {
  if (l.status === "LIVE") return `/room/${l.id}`;
  return `/my-lectures/${l.id}/report`;
}

export function LectureCard({ lecture, index }: { lecture: Lecture; index: number }) {
  const si = statusInfo(lecture.status);
  const palette = thumbPalette[index % thumbPalette.length];
  const dateDot = lecture.date.replace(/-/g, ".").slice(2);

  return (
    <Link
      href={hrefFor(lecture)}
      style={{
        display: "block",
        background: "#fff",
        border: `1px solid ${color.border}`,
        borderRadius: 18,
        padding: 14,
        boxShadow: shadow.card,
        textDecoration: "none",
        color: color.text,
      }}
    >
      <div
        style={{
          position: "relative",
          aspectRatio: "16/9",
          borderRadius: 12,
          background: palette.bg,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          overflow: "hidden",
        }}
      >
        <span
          style={{
            fontFamily: "var(--font-space-mono), monospace",
            fontWeight: 700,
            fontSize: 15,
            color: palette.fg,
            padding: "0 16px",
            textAlign: "center",
            opacity: 0.85,
          }}
        >
          {lecture.title}
        </span>
        <div style={{ position: "absolute", left: 14, top: 14 }}>
          <span
            style={{
              display: "inline-flex",
              alignItems: "center",
              gap: 5,
              background: si.bg,
              color: si.fg,
              fontSize: 11,
              fontWeight: 800,
              padding: "4px 9px",
              borderRadius: 7,
            }}
          >
            <span style={{ width: 6, height: 6, borderRadius: "50%", background: si.dot }} />
            {si.label}
          </span>
        </div>
        {lecture.status === "LIVE" && (
          <div style={{ position: "absolute", right: 14, top: 14 }}>
            <span
              style={{
                display: "inline-flex",
                alignItems: "center",
                gap: 5,
                background: color.red,
                color: "#fff",
                fontSize: 11,
                fontWeight: 800,
                padding: "4px 9px",
                borderRadius: 7,
              }}
            >
              <span style={{ width: 6, height: 6, borderRadius: "50%", background: "#fff" }} />
              LIVE
            </span>
          </div>
        )}
      </div>
      <div
        style={{
          fontWeight: 800,
          fontSize: 17,
          lineHeight: 1.4,
          padding: "16px 6px 0",
          whiteSpace: "nowrap",
          overflow: "hidden",
          textOverflow: "ellipsis",
        }}
      >
        {lecture.title}
      </div>
      <div style={{ height: 1, background: color.borderLight, margin: "14px 6px" }} />
      <div style={{ display: "flex", alignItems: "center", gap: 14, padding: "0 6px", color: color.textFainter, fontSize: 13 }}>
        <span style={{ display: "flex", alignItems: "center", gap: 6 }}>🕐 {lecture.dur}</span>
        <span style={{ width: 1, height: 12, background: color.borderMuted }} />
        <span style={{ display: "flex", alignItems: "center", gap: 6 }}>📅 {dateDot}</span>
      </div>
    </Link>
  );
}
