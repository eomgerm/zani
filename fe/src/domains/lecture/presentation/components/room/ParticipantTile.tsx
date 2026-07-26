import { KickIcon, MicOffIcon } from "@/shared/ui";

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
export function ParticipantTile({ participant, canControl = false }: ParticipantTileProps) {
  const { name, color, role, microphoneEnabled, handRaised } = participant;

  return (
    <div
      role="group"
      aria-label={statusLabel(participant)}
      className={`relative min-h-0 overflow-hidden rounded-2xl bg-panel shadow-[0_8px_24px_#00000040] ${
        role === "instructor" ? "border-[1.5px] border-primary" : "border-[1.5px] border-white/[.06]"
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
      </div>

      <div className="pointer-events-none absolute inset-0">
        {role === "instructor" && (
          <span className="absolute right-2 top-2 rounded-[7px] bg-primary px-2 py-[3px] text-[10px] font-extrabold text-white">
            강사
          </span>
        )}
        {handRaised && (
          <div className="absolute left-2 top-2 flex size-7 items-center justify-center rounded-[9px] bg-warn text-sm shadow-[0_4px_12px_#f4c32550]">
            ✋
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
