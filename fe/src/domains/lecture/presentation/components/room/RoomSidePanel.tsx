import { useState } from "react";
import { Avatar, HandIcon, KickIcon } from "@/shared/ui";
import type { ChatMessageView } from "@/domains/interaction";
import type { Participant } from "../../fixtures";

interface RoomSidePanelProps {
  panel: "people" | "chat";
  participants: Participant[];
  messages: readonly ChatMessageView[];
  meId: string;
  isInstructor: boolean;
  /** 채널이 붙어 있는지. 끊긴 동안에는 입력을 막는다. */
  canSendChat: boolean;
  onSendChat: (content: string) => void;
  /** 실패한 전송을 같은 clientEventId 로 다시 보낸다(서버가 멱등 처리한다). */
  onRetryChat: (clientEventId: string) => void;
}

const rowBtnCls =
  "flex size-[30px] cursor-pointer items-center justify-center rounded-lg border-0 bg-room-line text-xs text-panel-soft";

/** 서버 `chat_messages.content` 상한과 같은 값. 넘겨 보내도 거절되므로 입력에서 막는다. */
const CHAT_MAX_LENGTH = 1000;

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
  canSendChat,
  onSendChat,
  onRetryChat,
}: RoomSidePanelProps) {
  const handQueue = participants.filter((p) => p.hand);
  const [draft, setDraft] = useState("");

  const submitChat = () => {
    if (!canSendChat || draft.trim().length === 0) return;
    onSendChat(draft);
    setDraft("");
  };

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
                        m.isInstructor ? "bg-primary" : m.mine ? "bg-[#65cba4]" : "bg-panel-muted"
                      }`}
                    />
                    <span className="text-xs font-bold text-panel-soft">{m.authorName}</span>
                    {m.isInstructor && (
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
                    } ${m.status === "sending" ? "opacity-60" : ""}`}
                  >
                    {m.content}
                  </div>
                  {/* 보내는 중·실패는 내 메시지에만 나타난다. 실패는 같은 전송을 다시 보낼 수 있게 한다. */}
                  {m.status === "sending" && (
                    <span className="mt-[3px] text-[10.5px] text-panel-muted">보내는 중…</span>
                  )}
                  {m.status === "failed" && m.clientEventId !== null && (
                    <button
                      type="button"
                      onClick={() => onRetryChat(m.clientEventId as string)}
                      className="mt-[3px] cursor-pointer rounded-md border-0 bg-transparent p-0 text-[10.5px] text-danger-light underline"
                    >
                      전송 실패 · 다시 시도
                    </button>
                  )}
                </div>
              ))
            )}
          </div>

          <form
            className="flex gap-2 border-t border-room-line p-3"
            onSubmit={(event) => {
              event.preventDefault();
              submitChat();
            }}
          >
            {/* 입력은 채널이 끊긴 동안에도 열어 둔다. 재연결이 깜빡일 때 작성 중인 문장을 이어 쓸 수
                있어야 하고, 보내기만 막으면 초안은 그대로 남는다. */}
            <input
              aria-label="전체에게 메시지 보내기"
              placeholder="전체에게 메시지 보내기"
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              maxLength={CHAT_MAX_LENGTH}
              className="flex-1 rounded-xl border border-room-edge bg-room-input px-3.5 py-[11px] text-sm text-panel-text outline-none"
            />
            <button
              type="submit"
              aria-label="메시지 보내기"
              disabled={!canSendChat || draft.trim().length === 0}
              title={canSendChat ? undefined : "채팅 연결 중이에요"}
              className="w-11 cursor-pointer rounded-xl border-0 bg-primary text-base text-white disabled:cursor-not-allowed disabled:opacity-50"
            >
              ↑
            </button>
          </form>
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
