import { color } from "@/shared/lib/theme";
import { Avatar } from "@/shared/ui";
import type { ChatMessage, Participant } from "../../fixtures";

interface RoomSidePanelProps {
  panel: "people" | "chat";
  onPanel: (p: "people" | "chat") => void;
  chatTab: "public" | "dm";
  onChatTab: (t: "public" | "dm") => void;
  participants: Participant[];
  messages: ChatMessage[];
  meId: string;
  isInstructor: boolean;
}

/** 강의실 발표자 보기 사이드 패널(어두운 테마) — 참여자/채팅 탭. */
export function RoomSidePanel({
  panel,
  onPanel,
  chatTab,
  onChatTab,
  participants,
  messages,
  meId,
  isInstructor,
}: RoomSidePanelProps) {
  const count = participants.length;
  const handQueue = participants.filter((p) => p.hand);

  return (
    <div
      style={{
        width: 340,
        flexShrink: 0,
        background: color.inkPanel,
        borderRadius: 18,
        display: "flex",
        flexDirection: "column",
        overflow: "hidden",
        color: color.inkText,
      }}
    >
      <div style={{ display: "flex", padding: 6, gap: 4, borderBottom: `1px solid ${color.roomBorder}` }}>
        <button onClick={() => onPanel("people")} style={tabStyle(panel === "people")}>
          참여자 {count}
        </button>
        <button onClick={() => onPanel("chat")} style={tabStyle(panel === "chat")}>
          채팅
        </button>
      </div>

      {panel === "chat" ? (
        <>
          <div style={{ display: "flex", gap: 6, padding: "10px 12px 6px" }}>
            <button onClick={() => onChatTab("public")} style={chatTabStyle(chatTab === "public")}>
              전체
            </button>
            <button onClick={() => onChatTab("dm")} style={chatTabStyle(chatTab === "dm")}>
              1:1 채팅
            </button>
          </div>
          <div style={{ flex: 1, overflowY: "auto", padding: "8px 14px", display: "flex", flexDirection: "column", gap: 12 }}>
            {messages.map((m) => (
              <div key={m.id} style={{ display: "flex", flexDirection: "column", alignItems: m.mine ? "flex-end" : "flex-start" }}>
                <div style={{ display: "flex", alignItems: "center", gap: 7, marginBottom: 3 }}>
                  <span style={{ width: 6, height: 6, borderRadius: "50%", background: m.host ? color.primary : m.mine ? "#65cba4" : "#8a90b8" }} />
                  <span style={{ fontSize: 12, fontWeight: 700, color: "#cfd3f0" }}>{m.author}</span>
                  {m.host && (
                    <span style={{ fontSize: 10, background: color.primary, color: "#fff", padding: "1px 6px", borderRadius: 6 }}>강사</span>
                  )}
                </div>
                <div
                  style={{
                    maxWidth: 230,
                    padding: "9px 13px",
                    borderRadius: 14,
                    fontSize: 13.5,
                    lineHeight: 1.45,
                    background: m.mine ? color.primary : color.roomBorder,
                    color: m.mine ? "#fff" : "#dfe2f4",
                    borderBottomRightRadius: m.mine ? 4 : 14,
                    borderBottomLeftRadius: m.mine ? 14 : 4,
                  }}
                >
                  {m.text}
                </div>
              </div>
            ))}
          </div>
          <div style={{ padding: 12, borderTop: `1px solid ${color.roomBorder}`, display: "flex", gap: 8 }}>
            <input
              placeholder={chatTab === "public" ? "전체에게 메시지 보내기" : "강사에게만 보내기"}
              style={{ flex: 1, padding: "11px 14px", borderRadius: 12, border: `1px solid ${color.roomBorderSoft}`, background: color.roomInput, color: color.inkText, outline: "none", fontSize: 14 }}
            />
            <button style={{ width: 44, borderRadius: 12, border: "none", background: color.primary, color: "#fff", fontSize: 16, cursor: "pointer" }}>↑</button>
          </div>
        </>
      ) : (
        <div style={{ flex: 1, overflowY: "auto", padding: "10px 12px" }}>
          {handQueue.length > 0 && (
            <>
              <div style={{ fontSize: 11, color: "#8a90b8", fontWeight: 800, padding: "6px 6px 8px" }}>✋ 손든 참가자</div>
              {handQueue.map((p) => (
                <div key={p.id} style={{ display: "flex", alignItems: "center", gap: 10, padding: 8, background: "#f5a52415", borderRadius: 10, marginBottom: 6 }}>
                  <Avatar initial={p.name.charAt(0)} size={34} bg={p.color} />
                  <span style={{ flex: 1, fontSize: 13.5, color: color.inkText, fontWeight: 700 }}>{p.name}</span>
                  <span style={{ fontSize: 14 }}>✋</span>
                </div>
              ))}
              <div style={{ height: 1, background: color.roomBorder, margin: "8px 2px 12px" }} />
            </>
          )}
          {participants.map((p) => {
            const isMe = p.id === meId;
            const nameTag = p.name + (p.host ? " · 강사" : isMe ? " · 나" : "");
            const canControl = isInstructor && !p.host && !isMe;
            return (
              <div key={p.id} style={{ display: "flex", alignItems: "center", gap: 10, padding: "8px 6px" }}>
                <Avatar initial={p.name.charAt(0)} size={34} bg={p.color} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 13.5, fontWeight: 700, color: color.inkText, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                    {nameTag}
                  </div>
                  <div style={{ fontSize: 11, color: "#7b81a8" }}>{p.cam ? "카메라 켜짐" : "카메라 꺼짐"}{p.mic ? "" : " · 음소거"}</div>
                </div>
                {canControl && (
                  <>
                    <button title="음소거" style={rowBtn}>🔇</button>
                    <button title="퇴장" style={rowBtn}>⏏</button>
                  </>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function tabStyle(active: boolean) {
  return {
    flex: 1,
    padding: 11,
    border: "none",
    borderRadius: 10,
    cursor: "pointer",
    fontFamily: "inherit",
    fontWeight: 800,
    fontSize: 13,
    background: active ? color.roomBorder : "transparent",
    color: active ? "#fff" : "#8a90b8",
  } as const;
}

function chatTabStyle(active: boolean) {
  return {
    flex: 1,
    padding: 8,
    borderRadius: 9,
    border: "none",
    cursor: "pointer",
    fontFamily: "inherit",
    fontWeight: 700,
    fontSize: 12.5,
    background: active ? color.primary : color.roomInput,
    color: active ? "#fff" : "#8a90b8",
  } as const;
}

const rowBtn = {
  width: 30,
  height: 30,
  borderRadius: 8,
  border: "none",
  background: color.roomBorder,
  color: "#cfd3f0",
  cursor: "pointer",
  fontSize: 12,
} as const;
