import { useEffect, useRef, useState } from "react";
import { Avatar, HandIcon } from "@/shared/ui";
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
  /** 강사가 학생을 음소거한다. 대상은 LiveKit identity(`p-{참가자ID}`)로 넘어온다. */
  onMute?: (identity: string) => void;
  /** 지금 음소거 요청이 진행 중인 대상. 요청은 한 번에 하나뿐이라 그동안 모든 행의 버튼이 잠긴다. */
  mutingIdentity?: string | null;
}

/** 목록 행 끝의 제어 버튼. 아이콘이 아니라 글자를 담으므로 정사각형이 아니라 가로로 늘어난다. */
const rowBtnCls =
  "shrink-0 cursor-pointer rounded-lg border-0 bg-room-line px-2 py-1 text-[11px] font-bold text-panel-soft transition-[filter] hover:brightness-125 disabled:cursor-not-allowed disabled:opacity-45";

/** 서버 `chat_messages.content` 상한과 같은 값. 넘겨 보내도 거절되므로 입력에서 막는다. */
const CHAT_MAX_LENGTH = 1000;

/**
 * 이 정도 아래에 있으면 "맨 아래를 보고 있다"고 본다.
 *
 * 0 으로 두면 스크롤이 1px 만 밀려도 따라가기를 멈추고, 크게 두면 이력을 읽는 중에 끌려 내려간다.
 */
const CHAT_BOTTOM_THRESHOLD_PX = 40;

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
  onMute,
  mutingIdentity = null,
}: RoomSidePanelProps) {
  const handQueue = participants.filter((p) => p.hand);
  const [draft, setDraft] = useState("");

  const chatListRef = useRef<HTMLDivElement>(null);
  /** 맨 아래를 보고 있는지. 위로 올려 이력을 읽는 중이면 새 메시지가 와도 끌어내리지 않는다. */
  const atBottomRef = useRef(true);

  const rememberScrollPosition = () => {
    const list = chatListRef.current;
    if (list === null) return;
    atBottomRef.current =
      list.scrollHeight - list.scrollTop - list.clientHeight <= CHAT_BOTTOM_THRESHOLD_PX;
  };

  // 새 메시지가 오면 아래로 붙인다. 없으면 목록이 패널을 넘긴 뒤부터 새 메시지가 화면 밖에 쌓인다.
  // 패널을 처음 열 때도 이 effect 가 돌아 마지막 대화가 보이는 위치에서 시작한다.
  useEffect(() => {
    const list = chatListRef.current;
    if (list === null || !atBottomRef.current) return;
    list.scrollTop = list.scrollHeight;
  }, [messages, panel]);

  const submitChat = () => {
    if (!canSendChat || draft.trim().length === 0) return;
    onSendChat(draft);
    setDraft("");
  };

  return (
    <div className="flex w-[340px] shrink-0 flex-col overflow-hidden rounded-[18px] border border-white/5 bg-panel text-panel-text">
      {panel === "chat" ? (
        <>
          <div
            ref={chatListRef}
            onScroll={rememberScrollPosition}
            data-testid="chat-message-list"
            className="flex flex-1 flex-col gap-3 overflow-y-auto px-3.5 pb-2 pt-3.5"
          >
            {messages.length === 0 ? (
              <div className="mt-[30px] text-center text-[13px] text-[#6b7096]">
                아직 메시지가 없어요.
                <br />
                첫 메시지를 보내보세요
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
              <div className="flex items-center gap-1 px-1.5 pb-2 pt-1.5 text-[11px] font-extrabold tracking-[.3px] text-panel-muted">
                <HandIcon size={12} />
                손든 참가자
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
                {/* 마이크·카메라 상태는 타일이 이미 보여준다 — 목록에는 이름만 남긴다(피드백 반영). */}
                <div className="min-w-0 flex-1">
                  <div className="truncate text-[13.5px] font-bold text-panel-text">{nameTag}</div>
                </div>
                {/*
                  퇴장 버튼은 제거했다(티켓 246 — 강제 퇴장 기능 자체가 범위 밖).

                  타일과 같은 이유로 아이콘이 아니라 글자를 쓴다. 마이크 그림 하나로는 "지금 꺼져 있다"와
                  "꺼라"가 구분되지 않는데, 강제 해제가 없어 되돌릴 수 없는 동작이라 더 분명해야 한다.
                */}
                {canControl && (
                  <button
                    type="button"
                    onClick={onMute === undefined ? undefined : () => onMute(p.id)}
                    disabled={!p.mic || mutingIdentity !== null}
                    title={
                      !p.mic
                        ? "이미 음소거됨"
                        : mutingIdentity === p.id
                          ? "음소거하는 중"
                          : mutingIdentity !== null
                            ? "처리 중"
                            : "음소거"
                    }
                    aria-label={`${p.name} 음소거`}
                    className={rowBtnCls}
                  >
                    음소거
                  </button>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
