"use client";

import { GridContainer, GridItem } from "@thangdevalone/meeting-grid-layout-react";
import { ParticipantTile, type ParticipantTileData } from "./ParticipantTile";
import {
  ROSTER_GAP,
  SNAP_SIZE,
  TILE_ASPECT_RATIO,
  TILE_FILL,
} from "./roomGridLayout";

type RoomRosterProps = {
  participants: ParticipantTileData[];
  /** 참가자별 카메라 video ref 를 만들어 주는 함수. 인앱·PiP 어느 쪽이든 트랙을 붙여 얼굴이 나오게 한다. */
  videoRefFor: (identity: string) => React.Ref<HTMLVideoElement>;
  /** 내 타일은 거울처럼 뒤집는다. */
  localParticipantId: string | null;
  /**
   * 한 번에 보여줄 최대 인원. 넘치면 마지막 타일에 남은 인원 수를 얹는다.
   *
   * <p>좁은 스트립이라 인원이 늘어날수록 타일이 계속 작아진다. 어느 선부터는 얼굴이 안 보이므로
   * 다 그리는 대신 몇 명이 더 있는지만 알린다.
   */
  maxVisible?: number;
  className?: string;
  testId?: string;
};

/**
 * 참가자 카메라 타일 목록. 화면 공유 중 강의방 미니 레이아웃을 그린다.
 *
 * <p>갤러리와 같은 배치기를 쓴다 — 세로 1열로 고정해 두면 스트립 폭이 바뀌어도 반응하지 않고, 같은 참가자가 화면에 따라 다른 비율로 보인다. 배치 설정은 `roomGridLayout` 이 소유한다.
 *
 * <p>같은 참가자 타일을 두 곳에 동시에 그리지 않는다 — 카메라 트랙 부착 훅은 identity 당 요소 하나에만 붙이므로, 인앱과 PiP 는 배타적으로 렌더한다(둘 중 하나만 마운트).
 */
export function RoomRoster({
  participants,
  videoRefFor,
  localParticipantId,
  maxVisible = 0,
  className,
  testId,
}: RoomRosterProps) {
  return (
    <div className={className}>
      <GridContainer
        role="group"
        aria-label="강의방 참가자"
        data-testid={testId}
        count={participants.length}
        aspectRatio={TILE_ASPECT_RATIO}
        gap={ROSTER_GAP}
        forceAspectRatio
        // 2명일 때 기본값인 떠다니는 PiP 를 끈다. 공유 중 미니 레이아웃 안에 또 떠다니는 창을 두지 않는다.
        disableFloat
        maxVisible={maxVisible}
        springPreset="smooth"
      >
        {participants.map((participant, index) => (
          <GridItem key={participant.id} index={index} transition={SNAP_SIZE}>
            {({ isLastVisibleOther, hiddenCount }) => (
              <div className="relative size-full">
                <ParticipantTile
                  fit={TILE_FILL}
                  participant={participant}
                  videoRef={videoRefFor(participant.id)}
                  mirrored={participant.id === localParticipantId}
                />
                {/* 가려진 인원은 수만 알린다. 이름을 늘어놓을 자리가 없고, 참여자 패널이 전체 목록을 갖고 있다. */}
                {isLastVisibleOther && hiddenCount > 0 && (
                  <div
                    data-testid="roster-hidden-count"
                    className="pointer-events-none absolute inset-0 flex items-center justify-center rounded-2xl bg-black/60 text-sm font-extrabold text-white backdrop-blur-[2px]"
                  >
                    +{hiddenCount}
                  </div>
                )}
              </div>
            )}
          </GridItem>
        ))}
      </GridContainer>
    </div>
  );
}
