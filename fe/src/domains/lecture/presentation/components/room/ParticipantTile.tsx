import { HandIcon, MicOffIcon } from "@/shared/ui";

export type ParticipantTileData = {
  id: string;
  name: string;
  color: string;
  role: "instructor" | "student";
  cameraEnabled: boolean;
  microphoneEnabled: boolean;
  handRaised: boolean;
  /** 지금 말하고 있는지(LiveKit ActiveSpeaker). 타일 테두리 하이라이트에만 쓴다. */
  speaking: boolean;
};

/** 발화 중 테두리. 상단 패널 토글의 활성 초록과 같은 값이라 방 UI 팔레트 안에 머문다. */
const SPEAKING_BORDER = "border-[1.5px] border-[#2fbf88]";

type ParticipantTileProps = {
  participant: ParticipantTileData;
  canControl?: boolean;
  /**
   * 이 참가자의 카메라 트랙을 붙일 video ref. 로컬·원격 구분 없이 그리드가 내려준다.
   *
   * <p>훅을 여기서 부르지 않는 이유: 타일은 RoomProvider 없이도 렌더되는 순수 표현 컴포넌트이고, 같은 참가자의 훅 인스턴스가 둘이 되면 서로 트랙을 떼어낸다.
   */
  videoRef?: React.Ref<HTMLVideoElement>;
  /** 내 화면일 때만 true. 거울처럼 좌우를 뒤집는다 — 남의 화면을 뒤집으면 글씨가 거꾸로 보인다. */
  mirrored?: boolean;
  /** 칸 안에서 비율을 지키며 차지할 크기. 그리드가 정해 내려준다. */
  fit?: React.CSSProperties;
  /** 강사가 이 참가자를 음소거한다. 없으면 버튼이 아무 일도 하지 않는다(스토리북·테스트). */
  onMute?: () => void;
  /** 이 참가자에 대한 음소거 요청이 진행 중인지. 중복 클릭을 막는다. */
  muting?: boolean;
  /**
   * 다른 참가자에 대한 요청이 진행 중인지.
   *
   * 훅은 요청을 한 번에 하나만 보내므로, 그동안 다른 버튼을 눌러도 아무 일도 일어나지 않는다. 눌리는 것처럼
   * 보이면 강사는 껐다고 믿는데 소리는 계속 나간다. 눌러 봐야 소용없다는 것을 버튼이 직접 말해야 한다.
   */
  busy?: boolean;
};

const statusLabel = ({
  name,
  role,
  cameraEnabled,
  microphoneEnabled,
  handRaised,
}: ParticipantTileData) =>
  `${role === "instructor" ? "강사" : "학생"} ${name}, 카메라 ${cameraEnabled ? "켜짐" : "꺼짐"}, 마이크 ${
    microphoneEnabled ? "켜짐" : "꺼짐"
  }, ${handRaised ? "손 들음" : "손 들지 않음"}`;

/**
 * 강의실 갤러리에 표시하는 강사·학생 참가자 타일이다.
 * 타일 배경은 어두운 고정색이고 참가자 색은 가운데 원형 아바타에만 쓴다(프로토타입 tileL/circleAvatar).
 */
export function ParticipantTile({
  participant,
  canControl = false,
  onMute,
  muting = false,
  busy = false,
  videoRef,
  mirrored = false,
  fit,
}: ParticipantTileProps) {
  const { name, color, cameraEnabled, microphoneEnabled, handRaised, speaking } = participant;

  return (
    <div
      role="group"
      aria-label={statusLabel(participant)}
      style={fit}
      className={`relative min-h-0 w-full max-w-full overflow-hidden rounded-2xl bg-panel shadow-[0_8px_24px_#00000040] ${
        // 테두리는 발화 표시 전용이다. 강사 상시 테두리(primary=초록 계열)를 두면 발화 초록과
        // 구분되지 않아 강사가 항상 말하는 것처럼 보인다(피드백 반영). 역할은 statusLabel이 알린다.
        speaking ? SPEAKING_BORDER : "border-[1.5px] border-white/[.06]"
      }`}
    >
      <div className="absolute inset-0 flex items-center justify-center [background:radial-gradient(ellipse_at_50%_32%,#191d33,#101322_78%)]">
        <div
          aria-hidden="true"
          className="flex aspect-square h-[58%] max-h-[110px] items-center justify-center rounded-full font-extrabold text-white [font-size:clamp(20px,3.4vw,34px)]"
          style={{
            background: `linear-gradient(145deg,${color},${color}b0)`,
            boxShadow: `0 0 0 6px ${color}10, 0 16px 36px ${color}2e`,
          }}
        >
          {name.charAt(0)}
        </div>
        {/*
          video 는 항상 마운트해 둔다. 카메라를 켤 때만 렌더하면, 트랙 부착을 담당하는 훅이 요소가 생기기 전에
          동기화를 지나쳐 화면이 영영 붙지 않는다(마운트 경합). 아바타 뒤에 둬야 영상이 위에 그려진다.
          내 화면은 muted 여야 한다 — 남의 화면은 오디오가 따로 재생되므로 여기서 소리를 내지 않는다.
        */}
        {videoRef !== undefined && (
          <video
            ref={videoRef}
            autoPlay
            muted
            playsInline
            data-testid={`participant-video-${participant.id}`}
            className={`absolute inset-0 size-full object-cover ${mirrored ? "scale-x-[-1]" : ""} ${
              cameraEnabled ? "" : "invisible"
            }`}
          />
        )}
      </div>

      {/* 강사 배지는 제거했다(티켓 246). 역할 구분은 statusLabel(스크린리더)과 비발화 시 테두리 색이 담당한다. */}
      <div className="pointer-events-none absolute inset-0">
        {handRaised && (
          <div className="absolute left-2 top-2 flex size-7 items-center justify-center rounded-[9px] bg-warn text-[#3a2d05] shadow-[0_4px_12px_#f4c32550]">
            <HandIcon size={16} />
          </div>
        )}
        <div className="absolute bottom-[9px] left-[9px] inline-flex max-w-[calc(100%-18px)] items-center gap-1.5 rounded-[9px] bg-black/70 px-2.5 py-[5px] backdrop-blur-[4px]">
          {/* 이름칩에는 음소거만 알린다 — 카메라 꺼짐은 아바타가 보이는 것으로 이미 드러난다(피드백 반영). */}
          {!microphoneEnabled && (
            <span data-testid="tile-mic-off" className="inline-flex shrink-0 text-danger">
              <MicOffIcon />
            </span>
          )}
          <span className="truncate text-[11.5px] font-bold text-white">{name}</span>
        </div>
      </div>

      {/*
        퇴장 버튼은 제거했다(티켓 246 — 강제 퇴장 기능 자체가 범위 밖).

        아이콘이 아니라 글자를 쓴다. 이름칩의 MicOffIcon 이 "지금 음소거 상태"를 뜻하는데, 같은 그림을
        버튼에 쓰면 상태와 동작이 한 그림에 겹쳐 무엇을 하는 버튼인지 읽히지 않는다. 강제 해제가 없어
        되돌릴 수 없는 동작이라 더 분명해야 한다.

        이미 음소거면 누를 이유가 없어 비활성화한다. 숨기지 않는 이유는 자리가 들쭉날쭉해지지 않게
        하려는 것이고, title 로 왜 못 누르는지 알린다.
      */}
      {canControl && (
        <div className="absolute right-1.5 top-1.5 flex gap-1">
          <button
            type="button"
            onClick={onMute}
            disabled={!microphoneEnabled || muting || busy}
            aria-label={`${name} 음소거`}
            title={
              !microphoneEnabled
                ? "이미 음소거됨"
                : muting
                  ? "음소거하는 중"
                  : busy
                    ? "처리 중"
                    : "음소거"
            }
            className="cursor-pointer rounded-lg border-0 bg-black/70 px-2 py-1 text-[11px] font-bold text-white backdrop-blur-[4px] transition-[filter] hover:brightness-125 disabled:cursor-not-allowed disabled:opacity-45"
          >
            음소거
          </button>
        </div>
      )}
    </div>
  );
}
