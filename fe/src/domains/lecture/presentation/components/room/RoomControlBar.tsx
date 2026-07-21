import { color } from "@/shared/lib/theme";
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

/** 밝은 배경의 컨트롤 버튼. 활성(끔/공유 등)이면 강조색, 아니면 옅은 회색. */
function ctlStyle(active: boolean, activeBg: string, activeFg: string) {
  return {
    display: "flex",
    flexDirection: "column" as const,
    alignItems: "center",
    gap: 3,
    padding: "8px 14px",
    borderRadius: 12,
    border: "none",
    cursor: "pointer",
    fontFamily: "inherit",
    fontSize: 11.5,
    fontWeight: 700,
    background: active ? activeBg : color.bg,
    color: active ? activeFg : color.textSub,
  };
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
    <div
      style={{
        flexShrink: 0,
        position: "relative",
        background: "#fff",
        border: `1px solid ${color.border}`,
        borderRadius: 16,
        padding: "11px 18px",
        display: "flex",
        alignItems: "center",
        gap: 6,
        boxShadow: "0 4px 18px rgba(24,74,62,.05)",
      }}
    >
      <button onClick={onToggleMic} style={ctlStyle(!me.mic, "#fdeeee", color.red)}>
        <span style={{ fontSize: 19 }}>{me.mic ? "🎤" : "🔇"}</span>
        {me.mic ? "마이크" : "음소거"}
      </button>
      <button onClick={onToggleCam} style={ctlStyle(!me.cam, "#fdeeee", color.red)}>
        <span style={{ fontSize: 19 }}>{me.cam ? "🎥" : "📷"}</span>
        {me.cam ? "카메라" : "끔"}
      </button>
      <button onClick={onToggleShare} style={ctlStyle(sharing, color.primarySoft, color.primaryDeep)}>
        <span style={{ fontSize: 19 }}>🖥️</span>
        {sharing ? "공유 중" : "화면 공유"}
      </button>
      <button onClick={onToggleHand} style={ctlStyle(me.hand, "#fdf6df", color.amberText)}>
        <span style={{ fontSize: 19 }}>✋</span>
        손들기
      </button>
      <div style={{ position: "relative" }}>
        <button onClick={onToggleReactMenu} style={ctlStyle(reactMenuOpen, color.primarySoft, color.primaryDeep)}>
          <span style={{ fontSize: 19 }}>😊</span>
          반응
        </button>
        {reactMenuOpen && (
          <div
            style={{
              position: "absolute",
              bottom: 64,
              left: "50%",
              transform: "translateX(-50%)",
              background: "#fff",
              border: `1px solid ${color.borderMint}`,
              borderRadius: 16,
              padding: "8px 10px",
              display: "flex",
              gap: 4,
              boxShadow: "0 12px 32px rgba(24,74,62,.18)",
              animation: "zPop .15s",
              zIndex: 10,
            }}
          >
            {reactionEmojis.map((e) => (
              <button key={e} style={{ width: 42, height: 42, border: "none", background: "none", borderRadius: 12, fontSize: 22, cursor: "pointer" }}>
                {e}
              </button>
            ))}
          </div>
        )}
      </div>

      <button
        onClick={onPreview}
        style={{
          marginLeft: 6,
          padding: "9px 14px",
          borderRadius: 11,
          border: `1px solid ${color.borderMuted}`,
          background: "#fff",
          color: color.textFaint,
          cursor: "pointer",
          fontSize: 12,
          fontWeight: 700,
          fontFamily: "inherit",
        }}
      >
        {isInstructor ? "집단 알림 미리보기" : "확인 프롬프트 미리보기"}
      </button>

      <div style={{ flex: 1 }} />
    </div>
  );
}
