import type { Participant } from "../../fixtures";
import { color } from "@/shared/lib/theme";

/** 갤러리 보기의 참가자 타일. 카메라 대신 이니셜 배경으로 표현한다. */
export function RoomTile({
  participant,
  canControl,
}: {
  participant: Participant;
  canControl: boolean;
}) {
  const { name, color: c, host, hand, mic } = participant;
  return (
    <div style={{ position: "relative", borderRadius: 12, overflow: "hidden", aspectRatio: "4/3", background: `linear-gradient(135deg,${c}cc,${c}88)` }}>
      <div style={{ position: "absolute", inset: 0, display: "flex", alignItems: "center", justifyContent: "center", color: "#fff", fontSize: 26, fontWeight: 800 }}>
        {name.charAt(0)}
      </div>
      {host && (
        <span style={{ position: "absolute", top: 8, right: 8, background: color.primary, color: "#fff", fontSize: 10, fontWeight: 800, padding: "3px 8px", borderRadius: 7 }}>
          강사
        </span>
      )}
      {hand && (
        <div style={{ position: "absolute", top: 8, left: 8, background: color.amber, color: "#372b03", padding: "1px 7px", borderRadius: 7, fontSize: 12, fontWeight: 800 }}>
          ✋
        </div>
      )}
      <div
        style={{
          position: "absolute",
          left: 8,
          bottom: 8,
          right: 8,
          display: "flex",
          alignItems: "center",
          gap: 5,
          background: "#0009",
          padding: "4px 8px",
          borderRadius: 8,
          backdropFilter: "blur(4px)",
        }}
      >
        {!mic && <span style={{ fontSize: 10 }}>🔇</span>}
        <span style={{ fontSize: 11, fontWeight: 700, color: "#fff", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
          {name}
        </span>
      </div>
      {canControl && (
        <div style={{ position: "absolute", top: 6, right: 6, display: "flex", gap: 4 }}>
          <button title="음소거" style={tileBtn}>🔇</button>
          <button title="퇴장" style={tileBtn}>⏏</button>
        </div>
      )}
    </div>
  );
}

const tileBtn = {
  width: 26,
  height: 26,
  borderRadius: 8,
  border: "none",
  background: "#000000b0",
  color: "#fff",
  cursor: "pointer",
  fontSize: 11,
  backdropFilter: "blur(4px)",
} as const;
