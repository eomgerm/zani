import type { Participant } from "../../fixtures";

/** 갤러리 보기의 참가자 타일. 카메라 대신 이니셜 배경으로 표현한다. */
export function RoomTile({
  participant,
  canControl,
}: {
  participant: Participant;
  canControl: boolean;
}) {
  const { name, color: c, host, hand, mic } = participant;

  return (
    <div
      className="relative aspect-[4/3] overflow-hidden rounded-xl"
      style={{ background: `linear-gradient(135deg,${c}cc,${c}88)` }}
    >
      <div className="absolute inset-0 flex items-center justify-center text-[26px] font-extrabold text-white">
        {name.charAt(0)}
      </div>

      {host && (
        <span className="absolute right-2 top-2 rounded-[7px] bg-primary px-2 py-[3px] text-[10px] font-extrabold text-white">
          강사
        </span>
      )}
      {hand && (
        <div className="absolute left-2 top-2 rounded-[7px] bg-warn px-[7px] py-px text-xs font-extrabold text-[#372b03]">
          ✋
        </div>
      )}

      <div className="absolute inset-x-2 bottom-2 flex items-center gap-1.5 rounded-lg bg-black/60 px-2 py-1 backdrop-blur-[4px]">
        {!mic && <span className="text-[10px]">🔇</span>}
        <span className="truncate text-[11px] font-bold text-white">{name}</span>
      </div>

      {canControl && (
        <div className="absolute right-1.5 top-1.5 flex gap-1">
          <button title="음소거" className="size-[26px] cursor-pointer rounded-lg border-0 bg-black/70 text-[11px] text-white backdrop-blur-[4px]">
            🔇
          </button>
          <button title="퇴장" className="size-[26px] cursor-pointer rounded-lg border-0 bg-black/70 text-[11px] text-white backdrop-blur-[4px]">
            ⏏
          </button>
        </div>
      )}
    </div>
  );
}
