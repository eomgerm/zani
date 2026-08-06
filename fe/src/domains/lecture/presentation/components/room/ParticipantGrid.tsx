"use client";

import { useState } from "react";
import { GridContainer, GridItem } from "@thangdevalone/meeting-grid-layout-react";
import { GridPager } from "./GridPager";
import { ParticipantTile, type ParticipantTileData } from "./ParticipantTile";
import {
  ITEMS_PER_PAGE as PER_PAGE,
  SNAP_SIZE,
  TILE_ASPECT_RATIO,
  TILE_FILL,
  TILE_GAP,
} from "./roomGridLayout";

type ParticipantGridProps = {
  participants: ParticipantTileData[];
  currentParticipantId?: string;
  isInstructor?: boolean;
  /** 참가자별 카메라 video ref 를 만들어 주는 함수. 없으면 화면 없이 아바타만 보여준다. */
  videoRefFor?: (identity: string) => React.Ref<HTMLVideoElement>;
  /** 강사가 학생을 음소거한다. 대상은 LiveKit identity(`p-{참가자ID}`)로 넘어온다. */
  onMute?: (identity: string) => void;
  /** 지금 음소거 요청이 진행 중인 대상. 요청은 한 번에 하나뿐이라 그동안 모든 버튼이 잠긴다. */
  mutingIdentity?: string | null;
};

/**
 * 갤러리 보기. 한 페이지에 `ITEMS_PER_PAGE` 명을 보여주고 넘치면 페이지로 나눈다.
 *
 * 한 사람당 타일 하나다. 예전에는 남는 칸을 앞 참가자로 다시 채웠는데, 실제 참가자로 바뀐 뒤에는 같은 사람이 여러 번 보여 인원을 오해하게 만든다.
 *
 * <p>열·행은 인원수만으로 정하지 않는다. 같은 6명이라도 넓고 낮은 창에서는 3×2, 좁고 높은 창에서는 2×3 이 타일이 훨씬 크다. 배치기(`GridContainer`)가 컨테이너 실측 크기와 인원수를 함께 보고 남는 자리가 가장 적은 배치를 고르며, 마지막 행은 가운데로 모아 오른쪽 아래에 구멍이 남지 않게 한다.
 */
export function ParticipantGrid({
  participants,
  currentParticipantId,
  isInstructor = false,
  videoRefFor,
  onMute,
  mutingIdentity = null,
}: ParticipantGridProps) {
  const [page, setPage] = useState(0);

  const total = participants.length;
  const pageCount = Math.max(1, Math.ceil(total / PER_PAGE));
  // 참가자가 빠져 페이지 수가 줄어들면 마지막 페이지로 당긴다.
  const current = Math.min(page, pageCount - 1);
  const hasPages = pageCount > 1;

  const slots = participants.slice(current * PER_PAGE, (current + 1) * PER_PAGE);

  return (
    <>
      {/*
        여백은 이 바깥 상자가 가진다. 배치기는 자식을 절대 배치하는데, 절대 배치는 padding 을 건너뛰고
        padding box 기준으로 자리를 잡아 안쪽에 padding 을 주면 타일이 그만큼 밀린다.
      */}
      {/* 페이저 자리를 비워 두지 않는다 — 떠 있고 반투명이라 타일 위에 겹쳐도 영상을 가리지 않는다. */}
      <div className="size-full p-4">
        <GridContainer
          role="group"
          aria-label={`참가자 ${total}명`}
          count={slots.length}
          aspectRatio={TILE_ASPECT_RATIO}
          gap={TILE_GAP}
          forceAspectRatio
          // 2명일 때 기본값은 한 명 전체화면 + 한 명 떠다니는 PiP 다. 우리는 브라우저 밖까지 나가는
          // 진짜 Document PiP 를 따로 두므로(useDocumentPictureInPicture), 여기서는 나란히 놓는다.
          disableFloat
          springPreset="smooth"
        >
          {slots.map((participant, index) => (
            <GridItem key={participant.id} index={index} transition={SNAP_SIZE}>
              <ParticipantTile
                fit={TILE_FILL}
                participant={participant}
                canControl={
                  isInstructor &&
                  participant.role === "student" &&
                  participant.id !== currentParticipantId
                }
                videoRef={videoRefFor?.(participant.id)}
                mirrored={participant.id === currentParticipantId}
                onMute={onMute === undefined ? undefined : () => onMute(participant.id)}
                muting={mutingIdentity === participant.id}
                busy={mutingIdentity !== null && mutingIdentity !== participant.id}
              />
            </GridItem>
          ))}
        </GridContainer>
      </div>

      {hasPages && <GridPager current={current} pageCount={pageCount} onChange={setPage} />}
    </>
  );
}
