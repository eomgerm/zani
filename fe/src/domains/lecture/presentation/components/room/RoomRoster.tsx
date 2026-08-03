import { ParticipantTile, type ParticipantTileData } from "./ParticipantTile";

type RoomRosterProps = {
  participants: ParticipantTileData[];
  /** 참가자별 카메라 video ref 를 만들어 주는 함수. 인앱·PiP 어느 쪽이든 트랙을 붙여 얼굴이 나오게 한다. */
  videoRefFor: (identity: string) => React.Ref<HTMLVideoElement>;
  /** 내 타일은 거울처럼 뒤집는다. */
  localParticipantId: string | null;
  className?: string;
  testId?: string;
};

/**
 * 참가자 카메라 타일 목록. 화면 공유 중 강의방 미니 레이아웃을 인앱 우측 상단과 PiP 창에서 같은 모양으로 그리기 위해 뽑아낸 표현 컴포넌트.
 *
 * <p>같은 참가자 타일을 두 곳에 동시에 그리지 않는다 — 카메라 트랙 부착 훅은 identity 당 요소 하나에만 붙이므로, 인앱과 PiP 는 배타적으로 렌더한다(둘 중 하나만 마운트).
 */
export function RoomRoster({
  participants,
  videoRefFor,
  localParticipantId,
  className,
  testId,
}: RoomRosterProps) {
  return (
    <div role="group" aria-label="강의방 참가자" data-testid={testId} className={className}>
      {participants.map((participant) => (
        <ParticipantTile
          key={participant.id}
          fit={{ aspectRatio: "4 / 3" }}
          participant={participant}
          videoRef={videoRefFor(participant.id)}
          mirrored={participant.id === localParticipantId}
        />
      ))}
    </div>
  );
}
