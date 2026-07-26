import { ChevronDownIcon, Select, type SelectOption } from "@/shared/ui";
import { reactionEmojis } from "../../fixtures";

interface MeState {
  mic: boolean;
  cam: boolean;
  hand: boolean;
}

interface RoomControlBarProps {
  isInstructor: boolean;
  me: MeState;
  /** room에 연결되기 전이거나 publish가 막혀 마이크·카메라를 조작할 수 없는 상태. */
  mediaDisabled: boolean;
  /** 강사 제한 모드(서버가 publish 권한을 회수)인지. 안내 배지를 함께 노출한다. */
  publishBlocked: boolean;
  microphones: readonly SelectOption[];
  cameras: readonly SelectOption[];
  activeMicrophoneId: string | null;
  activeCameraId: string | null;
  onSelectMicrophone: (deviceId: string) => void;
  onSelectCamera: (deviceId: string) => void;
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
  return `flex cursor-pointer flex-col items-center gap-[3px] rounded-xl border-0 px-3.5 py-2 font-sans text-[11.5px] font-bold disabled:cursor-not-allowed disabled:opacity-50 ${
    active ? activeCls : "bg-canvas text-ink-sub"
  }`;
}

/**
 * [아이콘+라벨 토글 | 장치 선택 화살표] 분할 버튼. 입장 전 점검(DevicePreview)과 같은 구성이지만
 * 강의실의 밝은 테마를 따른다. 목록이 비어 있으면 화살표만 비활성화되고 토글은 그대로 쓸 수 있다.
 */
function MediaControl({
  toggleTestId,
  selectTestId,
  selectAriaLabel,
  enabled,
  disabled,
  onToggle,
  icon,
  label,
  options,
  value,
  onChange,
}: {
  toggleTestId: string;
  selectTestId: string;
  selectAriaLabel: string;
  enabled: boolean;
  disabled: boolean;
  onToggle: () => void;
  icon: string;
  label: string;
  options: readonly SelectOption[];
  value: string | null;
  onChange: (deviceId: string) => void;
}) {
  const tone = enabled ? "bg-canvas text-ink-sub" : "bg-danger-softer text-danger";
  return (
    <div className={`flex items-center rounded-xl ${tone}`}>
      <button
        type="button"
        data-testid={toggleTestId}
        aria-pressed={enabled}
        disabled={disabled}
        onClick={onToggle}
        className="flex cursor-pointer flex-col items-center gap-[3px] rounded-l-xl border-0 bg-transparent px-3.5 py-2 font-sans text-[11.5px] font-bold text-inherit disabled:cursor-not-allowed disabled:opacity-50"
      >
        <span className="text-[19px]">{icon}</span>
        {label}
      </button>
      <Select
        data-testid={selectTestId}
        aria-label={selectAriaLabel}
        options={options}
        value={value}
        onChange={onChange}
        disabled={disabled || options.length === 0}
        placement="top"
        trigger={({ open }) => (
          <ChevronDownIcon
            className={`size-[14px] transition-transform ${open ? "rotate-180" : ""}`}
          />
        )}
        triggerClassName="flex cursor-pointer items-center rounded-r-xl border-0 bg-transparent py-[19px] pl-0.5 pr-2.5 text-inherit disabled:cursor-not-allowed disabled:opacity-40"
        listClassName="left-auto w-56"
      />
    </div>
  );
}

/** 강의실 하단 컨트롤 바(밝은 테마). */
export function RoomControlBar({
  isInstructor,
  me,
  mediaDisabled,
  publishBlocked,
  microphones,
  cameras,
  activeMicrophoneId,
  activeCameraId,
  onSelectMicrophone,
  onSelectCamera,
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
      <MediaControl
        toggleTestId="room-microphone-toggle"
        selectTestId="room-microphone-select"
        selectAriaLabel="마이크 선택"
        enabled={me.mic}
        disabled={mediaDisabled}
        onToggle={onToggleMic}
        icon={me.mic ? "🎤" : "🔇"}
        label={me.mic ? "마이크" : "음소거"}
        options={microphones}
        value={activeMicrophoneId}
        onChange={onSelectMicrophone}
      />
      <MediaControl
        toggleTestId="room-camera-toggle"
        selectTestId="room-camera-select"
        selectAriaLabel="카메라 선택"
        enabled={me.cam}
        disabled={mediaDisabled}
        onToggle={onToggleCam}
        icon={me.cam ? "🎥" : "📷"}
        label={me.cam ? "카메라" : "끔"}
        options={cameras}
        value={activeCameraId}
        onChange={onSelectCamera}
      />
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

      {publishBlocked && (
        <span
          role="status"
          data-testid="room-publish-blocked"
          className="rounded-full bg-warn-soft px-3 py-1.5 text-[12px] font-extrabold text-warn-text"
        >
          강사가 마이크·카메라 사용을 제한했습니다
        </span>
      )}
    </div>
  );
}
