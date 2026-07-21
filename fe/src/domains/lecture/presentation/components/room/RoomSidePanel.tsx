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

const tabCls = (active: boolean) =>
  `flex-1 cursor-pointer rounded-[10px] border-0 py-[11px] font-sans text-[13px] font-extrabold ${
    active ? "bg-room-line text-white" : "bg-transparent text-panel-muted"
  }`;

const chatTabCls = (active: boolean) =>
  `flex-1 cursor-pointer rounded-[9px] border-0 py-2 font-sans text-[12.5px] font-bold ${
    active ? "bg-primary text-white" : "bg-room-input text-panel-muted"
  }`;

const rowBtnCls =
  "size-[30px] cursor-pointer rounded-lg border-0 bg-room-line text-xs text-panel-soft";

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
  const handQueue = participants.filter((p) => p.hand);

  return (
    <div className="flex w-[340px] shrink-0 flex-col overflow-hidden rounded-[18px] bg-panel text-panel-text">
      <div className="flex gap-1 border-b border-room-line p-1.5">
        <button onClick={() => onPanel("people")} className={tabCls(panel === "people")}>
          참여자 {participants.length}
        </button>
        <button onClick={() => onPanel("chat")} className={tabCls(panel === "chat")}>
          채팅
        </button>
      </div>

      {panel === "chat" ? (
        <>
          <div className="flex gap-1.5 px-3 pb-1.5 pt-2.5">
            <button onClick={() => onChatTab("public")} className={chatTabCls(chatTab === "public")}>
              전체
            </button>
            <button onClick={() => onChatTab("dm")} className={chatTabCls(chatTab === "dm")}>
              1:1 채팅
            </button>
          </div>

          <div className="flex flex-1 flex-col gap-3 overflow-y-auto px-3.5 py-2">
            {messages.map((m) => (
              <div key={m.id} className={`flex flex-col ${m.mine ? "items-end" : "items-start"}`}>
                <div className="mb-[3px] flex items-center gap-[7px]">
                  <span
                    className={`size-1.5 rounded-full ${
                      m.host ? "bg-primary" : m.mine ? "bg-[#65cba4]" : "bg-panel-muted"
                    }`}
                  />
                  <span className="text-xs font-bold text-panel-soft">{m.author}</span>
                  {m.host && (
                    <span className="rounded-md bg-primary px-1.5 py-px text-[10px] text-white">
                      강사
                    </span>
                  )}
                </div>
                <div
                  className={`max-w-[230px] rounded-[14px] px-[13px] py-[9px] text-[13.5px] leading-[1.45] ${
                    m.mine
                      ? "rounded-br-[4px] bg-primary text-white"
                      : "rounded-bl-[4px] bg-room-line text-[#dfe2f4]"
                  }`}
                >
                  {m.text}
                </div>
              </div>
            ))}
          </div>

          <div className="flex gap-2 border-t border-room-line p-3">
            <input
              placeholder={chatTab === "public" ? "전체에게 메시지 보내기" : "강사에게만 보내기"}
              className="flex-1 rounded-xl border border-room-edge bg-room-input px-3.5 py-[11px] text-sm text-panel-text outline-none"
            />
            <button className="w-11 cursor-pointer rounded-xl border-0 bg-primary text-base text-white">
              ↑
            </button>
          </div>
        </>
      ) : (
        <div className="flex-1 overflow-y-auto px-3 py-2.5">
          {handQueue.length > 0 && (
            <>
              <div className="px-1.5 pb-2 pt-1.5 text-[11px] font-extrabold text-panel-muted">
                ✋ 손든 참가자
              </div>
              {handQueue.map((p) => (
                <div
                  key={p.id}
                  className="mb-1.5 flex items-center gap-2.5 rounded-[10px] bg-warn/10 p-2"
                >
                  <Avatar initial={p.name.charAt(0)} size={34} bg={p.color} />
                  <span className="flex-1 text-[13.5px] font-bold text-panel-text">{p.name}</span>
                  <span className="text-sm">✋</span>
                </div>
              ))}
              <div className="mx-0.5 mb-3 mt-2 h-px bg-room-line" />
            </>
          )}

          {participants.map((p) => {
            const isMe = p.id === meId;
            const nameTag = p.name + (p.host ? " · 강사" : isMe ? " · 나" : "");
            const canControl = isInstructor && !p.host && !isMe;
            return (
              <div key={p.id} className="flex items-center gap-2.5 px-1.5 py-2">
                <Avatar initial={p.name.charAt(0)} size={34} bg={p.color} />
                <div className="min-w-0 flex-1">
                  <div className="truncate text-[13.5px] font-bold text-panel-text">{nameTag}</div>
                  <div className="text-[11px] text-[#7b81a8]">
                    {p.cam ? "카메라 켜짐" : "카메라 꺼짐"}
                    {p.mic ? "" : " · 음소거"}
                  </div>
                </div>
                {canControl && (
                  <>
                    <button title="음소거" className={rowBtnCls}>
                      🔇
                    </button>
                    <button title="퇴장" className={rowBtnCls}>
                      ⏏
                    </button>
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
