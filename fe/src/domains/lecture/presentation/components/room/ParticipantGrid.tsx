import { ParticipantTile, type ParticipantTileData } from "./ParticipantTile";

type ParticipantGridProps = {
  participants: ParticipantTileData[];
  currentParticipantId?: string;
  isInstructor?: boolean;
};

/**
 * 갤러리 보기에서 전달받은 참가자를 모두 균일한 타일로 표시한다.
 * 방 정원은 백엔드가 강제하므로 표현 계층에서 인원을 제한하지 않고,
 * 넘치는 타일은 컨테이너 세로 스크롤로 처리한다.
 */
export function ParticipantGrid({
  participants,
  currentParticipantId,
  isInstructor = false,
}: ParticipantGridProps) {
  return (
    <div
      role="group"
      aria-label={`참가자 ${participants.length}명`}
      className="grid h-full auto-rows-min grid-cols-6 gap-2.5 overflow-y-auto px-4 pb-4 pt-[60px]"
    >
      {participants.map((participant) => (
        <ParticipantTile
          key={participant.id}
          participant={participant}
          canControl={
            isInstructor &&
            participant.role === "student" &&
            participant.id !== currentParticipantId
          }
        />
      ))}
    </div>
  );
}
