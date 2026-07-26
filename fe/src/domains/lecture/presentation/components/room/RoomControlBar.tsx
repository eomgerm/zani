import { CameraIcon, CloseIcon, MicIcon, ReactionIcon, ScreenShareIcon } from "@/shared/ui";
import { reactionEmojis } from "../../fixtures";

interface MeState {
  mic: boolean;
  cam: boolean;
  hand: boolean;
}

interface RoomControlBarProps {
  me: MeState;
  sharing: boolean;
  reactMenuOpen: boolean;
  onToggleMic: () => void;
  onToggleCam: () => void;
  onToggleShare: () => void;
  onToggleHand: () => void;
  onToggleReactMenu: () => void;
  onReact: (emoji: string) => void;
  onLeave: () => void;
}

/** 52px 원형 버튼. 기본은 room-control, 활성 상태에서만 강조색으로 바뀐다. */
const circle = "flex size-[52px] cursor-pointer items-center justify-center rounded-full border border-white/10 text-white transition-[filter] hover:brightness-125";

/** 끔(마이크·카메라)은 danger, 그 외 활성은 각자 강조색 */
function toneCls(active: boolean, activeCls: string) {
  return `${circle} ${active ? activeCls : "bg-room-control"}`;
}

/** 강의실 하단 컨트롤 바(어두운 테마). 라벨 없이 아이콘만 두고 title로 설명한다. */
export function RoomControlBar({
  me,
  sharing,
  reactMenuOpen,
  onToggleMic,
  onToggleCam,
  onToggleShare,
  onToggleHand,
  onToggleReactMenu,
  onReact,
  onLeave,
}: RoomControlBarProps) {
  return (
    <div className="flex shrink-0 items-center justify-center gap-3.5 pb-0.5 pt-2">
      <button
        type="button"
        onClick={onToggleMic}
        title={me.mic ? "마이크 끄기" : "마이크 켜기"}
        aria-label={me.mic ? "마이크 끄기" : "마이크 켜기"}
        aria-pressed={!me.mic}
        className={toneCls(!me.mic, "bg-danger")}
      >
        <MicIcon />
      </button>

      <button
        type="button"
        onClick={onToggleCam}
        title={me.cam ? "카메라 끄기" : "카메라 켜기"}
        aria-label={me.cam ? "카메라 끄기" : "카메라 켜기"}
        aria-pressed={!me.cam}
        className={toneCls(!me.cam, "bg-danger")}
      >
        <CameraIcon />
      </button>

      {/* 공유를 멈추는 주 동작은 스테이지 오버레이의 "화면 공유 중지" 버튼이다.
          여기서는 프로토타입대로 상태만 알리고, 켜짐 여부는 aria-pressed로 전달한다. */}
      <button
        type="button"
        onClick={onToggleShare}
        title={sharing ? "공유 중" : "화면 공유"}
        aria-label={sharing ? "공유 중" : "화면 공유"}
        aria-pressed={sharing}
        className={toneCls(sharing, "bg-primary")}
      >
        <ScreenShareIcon />
      </button>

      <button
        type="button"
        onClick={onToggleHand}
        title="손들기"
        aria-label="손들기"
        aria-pressed={me.hand}
        className={`${circle} ${me.hand ? "bg-warn" : "bg-room-control"} text-[19px]`}
      >
        ✋
      </button>

      <div className="relative">
        <button
          type="button"
          onClick={onToggleReactMenu}
          title="반응"
          aria-label="반응"
          aria-expanded={reactMenuOpen}
          className={toneCls(reactMenuOpen, "bg-primary")}
        >
          <ReactionIcon />
        </button>
        {reactMenuOpen && (
          <div className="absolute bottom-16 left-1/2 z-10 flex -translate-x-1/2 animate-[zPop_.15s] gap-1 rounded-2xl border border-room-line bg-panel px-2.5 py-2 shadow-[0_12px_32px_rgba(0,0,0,.4)]">
            {reactionEmojis.map((e) => (
              <button
                type="button"
                key={e}
                onClick={() => onReact(e)}
                aria-label={`${e} 반응 보내기`}
                className="size-[42px] cursor-pointer rounded-xl border-0 bg-transparent text-[22px] hover:bg-[#1e2138]"
              >
                {e}
              </button>
            ))}
          </div>
        )}
      </div>

      <button
        type="button"
        onClick={onLeave}
        title="나가기"
        aria-label="나가기"
        className="flex h-[52px] w-[68px] cursor-pointer items-center justify-center rounded-full border border-white/10 bg-danger text-white transition-[filter] hover:brightness-115"
      >
        <CloseIcon />
      </button>
    </div>
  );
}
