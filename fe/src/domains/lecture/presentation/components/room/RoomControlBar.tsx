import { reactionEmojis } from "../../fixtures";

interface MeState {
  mic: boolean;
  cam: boolean;
  hand: boolean;
}

interface RoomControlBarProps {
  isInstructor: boolean;
  me: MeState;
  sharing: boolean;
  reactMenuOpen: boolean;
  onToggleMic: () => void;
  onToggleCam: () => void;
  onToggleShare: () => void;
  onToggleHand: () => void;
  onToggleReactMenu: () => void;
  onPreview: () => void;
}

/** 컨트롤 버튼 스타일. 활성(끔/공유 등)이면 강조색, 아니면 옅은 배경. */
function ctlCls(active: boolean, activeCls: string) {
  return `flex cursor-pointer flex-col items-center gap-[3px] rounded-xl border-0 px-3.5 py-2 font-sans text-[11.5px] font-bold ${
    active ? activeCls : "bg-canvas text-ink-sub"
  }`;
}

/** 강의실 하단 컨트롤 바(밝은 테마). */
export function RoomControlBar({
  isInstructor,
  me,
  sharing,
  reactMenuOpen,
  onToggleMic,
  onToggleCam,
  onToggleShare,
  onToggleHand,
  onToggleReactMenu,
  onPreview,
}: RoomControlBarProps) {
  return (
    <div className="relative flex shrink-0 items-center gap-1.5 rounded-2xl border border-line bg-surface px-[18px] py-[11px] shadow-[0_4px_18px_rgba(24,74,62,.05)]">
      <button onClick={onToggleMic} className={ctlCls(!me.mic, "bg-danger-softer text-danger")}>
        <span className="text-[19px]">{me.mic ? "🎤" : "🔇"}</span>
        {me.mic ? "마이크" : "음소거"}
      </button>
      <button onClick={onToggleCam} className={ctlCls(!me.cam, "bg-danger-softer text-danger")}>
        <span className="text-[19px]">{me.cam ? "🎥" : "📷"}</span>
        {me.cam ? "카메라" : "끔"}
      </button>
      <button onClick={onToggleShare} className={ctlCls(sharing, "bg-primary-soft text-primary-deep")}>
        <span className="text-[19px]">🖥️</span>
        {sharing ? "공유 중" : "화면 공유"}
      </button>
      <button onClick={onToggleHand} className={ctlCls(me.hand, "bg-warn-soft text-warn-text")}>
        <span className="text-[19px]">✋</span>
        손들기
      </button>

      <div className="relative">
        <button
          onClick={onToggleReactMenu}
          className={ctlCls(reactMenuOpen, "bg-primary-soft text-primary-deep")}
        >
          <span className="text-[19px]">😊</span>
          반응
        </button>
        {reactMenuOpen && (
          <div className="absolute bottom-16 left-1/2 z-10 flex -translate-x-1/2 animate-[zPop_.15s] gap-1 rounded-2xl border border-line-mint bg-surface px-2.5 py-2 shadow-[0_12px_32px_rgba(24,74,62,.18)]">
            {reactionEmojis.map((e) => (
              <button
                key={e}
                className="size-[42px] cursor-pointer rounded-xl border-0 bg-transparent text-[22px] hover:bg-primary-soft"
              >
                {e}
              </button>
            ))}
          </div>
        )}
      </div>

      <button
        onClick={onPreview}
        className="z-btn ml-1.5 rounded-[11px] border border-line-muted bg-surface px-3.5 py-[9px] text-xs text-ink-faint"
      >
        {isInstructor ? "집단 알림 미리보기" : "확인 프롬프트 미리보기"}
      </button>

      <div className="flex-1" />
    </div>
  );
}
