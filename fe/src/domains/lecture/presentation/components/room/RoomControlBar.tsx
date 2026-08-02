import type { ReactNode } from "react";

import { REACTION_EMOJI, REACTION_KINDS, type ReactionKind } from "@/domains/interaction";
import {
  CameraIcon,
  CameraOffIcon,
  ChevronDownIcon,
  CloseIcon,
  HandIcon,
  MicIcon,
  MicOffIcon,
  ReactionIcon,
  ScreenShareIcon,
  Select,
  type SelectOption,
} from "@/shared/ui";

interface MeState {
  mic: boolean;
  cam: boolean;
  hand: boolean;
}

interface RoomControlBarProps {
  me: MeState;
  /** room 에 연결되기 전이라 마이크·카메라를 조작할 수 없는 상태. */
  mediaDisabled: boolean;
  /** 강사 제한 모드: 서버가 해당 source 의 publish 권한을 회수한 상태. */
  microphoneBlocked: boolean;
  cameraBlocked: boolean;
  microphones: readonly SelectOption[];
  cameras: readonly SelectOption[];
  activeMicrophoneId: string | null;
  activeCameraId: string | null;
  onSelectMicrophone: (deviceId: string) => void;
  onSelectCamera: (deviceId: string) => void;
  sharing: boolean;
  /** 다른 참가자가 화면을 공유 중이라 내가 시작할 수 없는 상태(세션당 활성 공유 1명). */
  shareBlocked: boolean;
  reactMenuOpen: boolean;
  /**
   * 손들기를 보낼 수 없는 상태.
   *
   * 반응과 조건이 다르다 — 손들기는 내 현재 상태를 알아야 무엇을 보낼지(올릴지 내릴지) 정할 수 있어
   * 내 participant identity 가 필요하지만, 반응은 보낸 사람을 서버가 STOMP 주체에서 가져오므로 필요 없다.
   */
  handDisabled: boolean;
  /** 반응을 보낼 수 없는 상태. 채널이 붙어 있으면 보낼 수 있다. */
  reactionDisabled: boolean;
  onToggleMic: () => void;
  onToggleCam: () => void;
  onToggleShare: () => void;
  onToggleHand: () => void;
  onToggleReactMenu: () => void;
  onReact: (kind: ReactionKind) => void;
  onLeave: () => void;
}

/** 52px 원형 버튼. 기본은 room-control, 활성 상태에서만 강조색으로 바뀐다. */
const circle = "flex size-[52px] cursor-pointer items-center justify-center rounded-full border border-white/10 text-white transition-[filter] hover:brightness-125";

/** 끔(마이크·카메라)은 danger, 그 외 활성은 각자 강조색 */
function toneCls(active: boolean, activeCls: string) {
  return `${circle} ${active ? activeCls : "bg-room-control"}`;
}

/**
 * [원형 토글 + 장치 선택 화살표] 묶음. 입장 전 점검(DevicePreview)과 같은 구성이며 강의실 어두운 테마를 따른다.
 * 목록이 비어 있으면 화살표만 비활성화되고 토글은 그대로 쓸 수 있다.
 */
function MediaControl({
  toggleTestId,
  selectTestId,
  selectAriaLabel,
  enabled,
  disabled,
  onToggle,
  title,
  icon,
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
  title: string;
  icon: ReactNode;
  options: readonly SelectOption[];
  value: string | null;
  onChange: (deviceId: string) => void;
}) {
  return (
    <div className="flex items-center gap-1">
      <button
        type="button"
        data-testid={toggleTestId}
        onClick={onToggle}
        disabled={disabled}
        title={title}
        aria-label={title}
        aria-pressed={enabled}
        className={`${toneCls(!enabled, "bg-danger")} disabled:cursor-not-allowed disabled:opacity-50`}
      >
        {icon}
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
            className={`size-[15px] text-white transition-transform ${open ? "rotate-180" : ""}`}
          />
        )}
        triggerClassName="flex cursor-pointer items-center rounded-full border-0 bg-transparent p-1 transition-colors hover:bg-room-control disabled:cursor-not-allowed disabled:opacity-40"
        listClassName="left-auto w-56"
      />
    </div>
  );
}

/** 제한된 장치만 안내 문구에 넣는다(마이크만·카메라만 제한될 수 있다). */
function restrictedLabel(microphoneBlocked: boolean, cameraBlocked: boolean): string {
  if (microphoneBlocked && cameraBlocked) {
    return "마이크·카메라";
  }
  return microphoneBlocked ? "마이크" : "카메라";
}

/** 강의실 하단 컨트롤 바(어두운 테마). 라벨 없이 아이콘만 두고 title로 설명한다. */
export function RoomControlBar({
  me,
  mediaDisabled,
  microphoneBlocked,
  cameraBlocked,
  microphones,
  cameras,
  activeMicrophoneId,
  activeCameraId,
  onSelectMicrophone,
  onSelectCamera,
  sharing,
  shareBlocked,
  reactMenuOpen,
  handDisabled,
  reactionDisabled,
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
      <MediaControl
        toggleTestId="room-microphone-toggle"
        selectTestId="room-microphone-select"
        selectAriaLabel="마이크 선택"
        enabled={me.mic}
        disabled={mediaDisabled || microphoneBlocked}
        onToggle={onToggleMic}
        title={me.mic ? "마이크 끄기" : "마이크 켜기"}
        // 끔 상태는 붉은 배경 + 흰 슬래시 아이콘. 배경색만으로는 켬/끔이 한눈에 안 갈린다(피드백 반영).
        icon={me.mic ? <MicIcon /> : <MicOffIcon size={22} />}
        options={microphones}
        value={activeMicrophoneId}
        onChange={onSelectMicrophone}
      />

      <MediaControl
        toggleTestId="room-camera-toggle"
        selectTestId="room-camera-select"
        selectAriaLabel="카메라 선택"
        enabled={me.cam}
        disabled={mediaDisabled || cameraBlocked}
        onToggle={onToggleCam}
        title={me.cam ? "카메라 끄기" : "카메라 켜기"}
        icon={me.cam ? <CameraIcon /> : <CameraOffIcon size={22} />}
        options={cameras}
        value={activeCameraId}
        onChange={onSelectCamera}
      />

      {/* 공유를 멈추는 주 동작은 스테이지 오버레이의 "화면 공유 중지" 버튼이다. 여기서는 켬/끔을 토글한다.
          다른 참가자가 공유 중이면(세션당 1명) 비활성화한다 — 내가 공유 중일 때는 중지해야 하므로 막지 않는다. */}
      <button
        type="button"
        onClick={onToggleShare}
        disabled={shareBlocked && !sharing}
        title={sharing ? "공유 중지" : shareBlocked ? "다른 참가자가 공유 중입니다" : "화면 공유"}
        aria-label={sharing ? "공유 중지" : shareBlocked ? "다른 참가자가 공유 중입니다" : "화면 공유"}
        aria-pressed={sharing}
        className={`${toneCls(sharing, "bg-primary")} disabled:cursor-not-allowed disabled:opacity-50`}
      >
        <ScreenShareIcon />
      </button>

      {/* 손든 상태는 서버가 확정한 값이다(낙관적으로 그리지 않는다). 채널이 끊기면 바꿀 수 없다. */}
      <button
        type="button"
        onClick={onToggleHand}
        disabled={handDisabled}
        title={handDisabled ? "연결 중입니다" : me.hand ? "손 내리기" : "손들기"}
        aria-label={me.hand ? "손 내리기" : "손들기"}
        aria-pressed={me.hand}
        className={`${circle} ${me.hand ? "bg-warn text-[#372b03]" : "bg-room-control"} disabled:cursor-not-allowed disabled:opacity-50`}
      >
        <HandIcon />
      </button>

      <div className="relative">
        <button
          type="button"
          onClick={onToggleReactMenu}
          disabled={reactionDisabled}
          title={reactionDisabled ? "연결 중입니다" : "반응"}
          aria-label="반응"
          aria-expanded={reactMenuOpen}
          className={`${toneCls(reactMenuOpen, "bg-primary")} disabled:cursor-not-allowed disabled:opacity-50`}
        >
          <ReactionIcon />
        </button>
        {reactMenuOpen && (
          <div className="absolute bottom-16 left-1/2 z-10 flex -translate-x-1/2 animate-[zPop_.15s] gap-1 rounded-2xl border border-room-line bg-panel px-2.5 py-2 shadow-[0_12px_32px_rgba(0,0,0,.4)]">
            {REACTION_KINDS.map((kind) => (
              <button
                type="button"
                key={kind}
                onClick={() => onReact(kind)}
                aria-label={`${REACTION_EMOJI[kind]} 반응 보내기`}
                className="size-[42px] cursor-pointer rounded-xl border-0 bg-transparent text-[22px] hover:bg-[#1e2138]"
              >
                {REACTION_EMOJI[kind]}
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

      {(microphoneBlocked || cameraBlocked) && (
        <span
          role="status"
          data-testid="room-publish-blocked"
          className="rounded-full bg-warn-soft px-3 py-1.5 text-[12px] font-extrabold text-warn-text"
        >
          강사가 {restrictedLabel(microphoneBlocked, cameraBlocked)} 사용을 제한했습니다
        </span>
      )}
    </div>
  );
}
