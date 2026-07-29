import { HandIcon, KickIcon, MicOffIcon } from "@/shared/ui";

export type ParticipantTileData = {
  id: string;
  name: string;
  color: string;
  role: "instructor" | "student";
  cameraEnabled: boolean;
  microphoneEnabled: boolean;
  handRaised: boolean;
};

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
  videoRef,
  mirrored = false,
  fit,
}: ParticipantTileProps) {
  const { name, color, role, cameraEnabled, microphoneEnabled, handRaised } = participant;

  return (
    <div
      role="group"
      aria-label={statusLabel(participant)}
      style={fit}
      className={`relative min-h-0 w-full max-w-full overflow-hidden rounded-2xl bg-panel shadow-[0_8px_24px_#00000040] ${
        role === "instructor"
          ? "border-[1.5px] border-primary"
          : "border-[1.5px] border-white/[.06]"
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

      <div className="pointer-events-none absolute inset-0">
        {role === "instructor" && (
          <span className="absolute right-2 top-2 rounded-[7px] bg-primary px-2 py-[3px] text-[10px] font-extrabold text-white">
            강사
          </span>
        )}
        {handRaised && (
          <div className="absolute left-2 top-2 flex size-7 items-center justify-center rounded-[9px] bg-warn text-[#3a2d05] shadow-[0_4px_12px_#f4c32550]">
            <HandIcon size={16} />
          </div>
        )}
        <div className="absolute bottom-[9px] left-[9px] inline-flex max-w-[calc(100%-18px)] items-center gap-1.5 rounded-[9px] bg-black/70 px-2.5 py-[5px] backdrop-blur-[4px]">
          {!microphoneEnabled && <MicOffIcon className="shrink-0 text-[#ff5a6e]" />}
          <span className="truncate text-[11.5px] font-bold text-white">{name}</span>
        </div>
      </div>

      {canControl && (
        <div className="absolute right-1.5 top-1.5 flex gap-1">
          <button
            type="button"
            aria-label={`${name} 음소거`}
            title="음소거"
            className="size-[26px] cursor-pointer rounded-lg border-0 bg-black/70 text-[11px] text-white backdrop-blur-[4px]"
          >
            🔇
          </button>
          <button
            type="button"
            aria-label={`${name} 퇴장`}
            title="퇴장"
            className="flex size-[26px] cursor-pointer items-center justify-center rounded-lg border-0 bg-black/70 text-white backdrop-blur-[4px]"
          >
            <KickIcon />
          </button>
        </div>
      )}
    </div>
  );
}
