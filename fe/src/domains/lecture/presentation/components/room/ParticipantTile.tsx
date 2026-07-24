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

/** 강의실 갤러리에 표시하는 강사·학생 참가자 타일이다. */
export function ParticipantTile({ participant, canControl = false }: ParticipantTileProps) {
  const { name, color, role, microphoneEnabled, handRaised } = participant;

  return (
    <div
      role="group"
      aria-label={statusLabel(participant)}
      className="relative aspect-[4/3] overflow-hidden rounded-xl"
      style={{ background: `linear-gradient(135deg,${color}cc,${color}88)` }}
    >
      <div aria-hidden="true" className="absolute inset-0 flex items-center justify-center text-[26px] font-extrabold text-white">
        {name.charAt(0)}
      </div>

      {role === "instructor" && (
        <span className="absolute right-2 top-2 rounded-[7px] bg-primary px-2 py-[3px] text-[10px] font-extrabold text-white">
          강사
        </span>
      )}
      {handRaised && (
        <span aria-hidden="true" className="absolute left-2 top-2 rounded-[7px] bg-warn px-[7px] py-px text-xs font-extrabold text-[#372b03]">
          ✋
        </span>
      )}

      <div className="absolute inset-x-2 bottom-2 flex items-center gap-1.5 rounded-lg bg-black/60 px-2 py-1 backdrop-blur-[4px]">
        {!microphoneEnabled && <span aria-hidden="true" className="text-[10px]">🔇</span>}
        <span className="truncate text-[11px] font-bold text-white">{name}</span>
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
            className="size-[26px] cursor-pointer rounded-lg border-0 bg-black/70 text-[11px] text-white backdrop-blur-[4px]"
          >
            ⏏
          </button>
        </div>
      )}
    </div>
  );
}
