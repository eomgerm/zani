"use client";

import { GridContainer, GridItem } from "@thangdevalone/meeting-grid-layout-react";
import { ParticipantTile, type ParticipantTileData } from "./ParticipantTile";
import { PIP_MAX_TILES, SNAP_SIZE, TILE_ASPECT_RATIO, TILE_FILL, TILE_GAP } from "./roomGridLayout";

/** 공유 화면은 배치기의 0번 칸이다. 참가자는 그 뒤로 이어진다. */
const SHARED_SCREEN_INDEX = 0;

type PipStageProps = {
  participants: ParticipantTileData[];
  /**
   * 공유 화면 트랙을 붙일 video ref. 공유 중이 아니면 주지 않는다.
   *
   * <p>주면 그 화면을 위에 고정하고 참가자를 아래로 보낸다. 트랙은 요소 하나에만 붙으므로 이 ref 를
   * 받은 쪽이 공유 화면을 그리는 유일한 자리다.
   */
  attachScreen?: React.Ref<HTMLVideoElement>;
  /** 참가자별 카메라 video ref 를 만들어 주는 함수. 없으면 아바타만 보여준다. */
  videoRefFor?: (identity: string) => React.Ref<HTMLVideoElement>;
  /** 내 타일은 거울처럼 뒤집는다. */
  localParticipantId: string | null;
};

/**
 * PiP 창(Document PiP) 안의 강의방.
 *
 * <p>본 화면과 설정이 다르다. 창이 작아 비율을 지키려 여백을 남기면 정작 얼굴이 안 보이므로 칸을
 * 채우고(`forceAspectRatio` 끔), 페이지를 넘길 자리도 마땅치 않아 상한만 둔다. 공유 중이면 공유
 * 화면을 위에 고정하고 참가자를 아래에 세운다 — 곁눈질하는 창이라 공유 내용이 먼저다.
 */
export function PipStage({
  participants,
  attachScreen,
  videoRefFor,
  localParticipantId,
}: PipStageProps) {
  const sharing = attachScreen !== undefined;

  return (
    <GridContainer
      role="group"
      aria-label={`참가자 ${participants.length}명`}
      count={participants.length + (sharing ? 1 : 0)}
      aspectRatio={TILE_ASPECT_RATIO}
      gap={TILE_GAP}
      // 작은 창에서는 비율을 지키느라 남기는 여백이 타일보다 크다. 칸을 채운다.
      forceAspectRatio={false}
      // 2명일 때 기본값인 떠다니는 PiP 를 끈다 — PiP 창 안에 또 떠다니는 창을 두지 않는다.
      disableFloat
      {...(sharing
        ? { pinnedIndex: SHARED_SCREEN_INDEX, othersPosition: "bottom" as const }
        : {})}
      maxVisible={PIP_MAX_TILES}
      springPreset="smooth"
    >
      {sharing && (
        <GridItem index={SHARED_SCREEN_INDEX} transition={SNAP_SIZE}>
          <div className="size-full overflow-hidden rounded-lg border border-[#1e2740] bg-[#0f1626]">
            <video
              ref={attachScreen}
              autoPlay
              muted
              playsInline
              data-testid="pip-screen-share-video"
              className="size-full object-contain"
            />
          </div>
        </GridItem>
      )}

      {participants.map((participant, index) => (
        <GridItem
          key={participant.id}
          index={index + (sharing ? 1 : 0)}
          transition={SNAP_SIZE}
        >
          {({ isLastVisibleOther, hiddenCount }) => (
            <div className="relative size-full">
              <ParticipantTile
                fit={TILE_FILL}
                participant={participant}
                videoRef={videoRefFor?.(participant.id)}
                mirrored={participant.id === localParticipantId}
              />
              {/* 상한을 넘긴 인원은 수만 알린다. 전체 목록은 본 창의 참여자 패널이 갖고 있다. */}
              {isLastVisibleOther && hiddenCount > 0 && (
                <div
                  data-testid="pip-hidden-count"
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
  );
}
