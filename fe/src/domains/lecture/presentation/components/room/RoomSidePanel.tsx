import { Avatar, HandIcon, KickIcon } from "@/shared/ui";
import type { ChatMessage, Participant } from "../../fixtures";

interface RoomSidePanelProps {
  panel: "people" | "chat";
  participants: Participant[];
  messages: ChatMessage[];
  meId: string;
  isInstructor: boolean;
}

const rowBtnCls =
  "flex size-[30px] cursor-pointer items-center justify-center rounded-lg border-0 bg-room-line text-xs text-panel-soft";

/** 참가자 아바타 배경(프로토타입 rowAvatar) */
const avatarBg = (color: string) => `linear-gradient(145deg,${color},${color}b8)`;

/**
 * 강의실 사이드 패널(어두운 테마).
 * 어느 탭을 보여줄지는 상단 바의 참여자/채팅 토글이 결정하므로 패널 자체에는 탭 헤더가 없다.
 */
export function RoomSidePanel({
  panel,
  participants,
  messages,
  meId,
  isInstructor,
}: RoomSidePanelProps) {
  const handQueue = participants.filter((p) => p.hand);

  return (
    <div className="flex w-[340px] shrink-0 flex-col overflow-hidden rounded-[18px] border border-white/5 bg-panel text-panel-text">
      {panel === "chat" ? (
        <>
          <div className="flex flex-1 flex-col gap-3 overflow-y-auto px-3.5 pb-2 pt-3.5">
            {messages.length === 0 ? (
              <div className="mt-[30px] text-center text-[13px] text-[#6b7096]">
                아직 메시지가 없어요.
                <br />
                첫 메시지를 보내보세요 💬
              </div>
            ) : (
              messages.map((m) => (
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
              ))
            )}
          </div>

          <div className="flex gap-2 border-t border-room-line p-3">
            <input
              aria-label="전체에게 메시지 보내기"
              placeholder="전체에게 메시지 보내기"
              className="flex-1 rounded-xl border border-room-edge bg-room-input px-3.5 py-[11px] text-sm text-panel-text outline-none"
            />
            <button
              type="button"
              aria-label="메시지 보내기"
              className="w-11 cursor-pointer rounded-xl border-0 bg-primary text-base text-white"
            >
              ↑
            </button>
          </div>
        </>
      ) : (
        <div className="flex-1 overflow-y-auto px-3 py-2.5">
          {handQueue.length > 0 && (
            <>
              <div className="px-1.5 pb-2 pt-1.5 text-[11px] font-extrabold tracking-[.3px] text-panel-muted">
                ✋ 손든 참가자
              </div>
              {handQueue.map((p) => (
                <div
                  key={p.id}
                  className="mb-1.5 flex items-center gap-2.5 rounded-[10px] bg-[#f5a52415] p-2"
                >
                  <Avatar initial={p.name.charAt(0)} size={34} bg={avatarBg(p.color)} />
                  <span className="flex-1 text-[13.5px] font-bold text-panel-text">{p.name}</span>
                  <HandIcon size={16} className="text-warn" />
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
                <Avatar initial={p.name.charAt(0)} size={34} bg={avatarBg(p.color)} />
                <div className="min-w-0 flex-1">
                  <div className="truncate text-[13.5px] font-bold text-panel-text">{nameTag}</div>
                  <div className="text-[11px] text-room-status">
                    {p.cam ? "카메라 켜짐" : "카메라 꺼짐"}
                    {p.mic ? "" : " · 음소거"}
                  </div>
                </div>
                {canControl && (
                  <>
                    <button
                      type="button"
                      title="음소거"
                      aria-label={`${p.name} 음소거`}
                      className={rowBtnCls}
                    >
                      🔇
                    </button>
                    <button
                      type="button"
                      title="퇴장"
                      aria-label={`${p.name} 퇴장`}
                      className={rowBtnCls}
                    >
                      <KickIcon size={14} />
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
