"use client";

import { useState } from "react";
import { GridContainer, GridItem } from "@thangdevalone/meeting-grid-layout-react";
import { ChevronLeftIcon, ChevronRightIcon } from "@/shared/ui";
import { ParticipantTile, type ParticipantTileData } from "./ParticipantTile";
import { SNAP_SIZE, TILE_ASPECT_RATIO, TILE_FILL, TILE_GAP } from "./roomGridLayout";

/** 한 페이지에 보여주는 최대 타일 수 */
const PER_PAGE = 12;

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

const pagerBtn = "flex size-[34px] items-center justify-center rounded-full border-0";

/**
 * 갤러리 보기. 한 페이지에 최대 12명을 보여주고 넘치면 페이지로 나눈다.
 *
 * 한 사람당 타일 하나다. 예전에는 12칸을 채우려고 남는 자리에 앞 참가자를 다시 넣었는데, 실제 참가자로 바뀐 뒤에는 같은 사람이 여러 번 보여 인원을 오해하게 만든다.
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
      <div className={`size-full p-4 ${hasPages ? "pb-[58px]" : ""}`}>
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

      {hasPages && (
        <div className="absolute bottom-3.5 left-1/2 z-[4] flex -translate-x-1/2 items-center gap-2.5 rounded-full border border-room-line bg-[#0e1020cc] p-1.5 pl-2 backdrop-blur-[6px]">
          <button
            type="button"
            onClick={() => setPage(Math.max(0, current - 1))}
            disabled={current === 0}
            aria-label="이전 페이지"
            className={`${pagerBtn} ${
              current === 0
                ? "cursor-default bg-transparent text-[#565b78]"
                : "cursor-pointer bg-room-control text-white"
            }`}
          >
            <ChevronLeftIcon size={18} />
          </button>
          <span className="min-w-10 text-center font-mono text-[13px] font-extrabold text-[#e7e9fb]">
            {current + 1}/{pageCount}
          </span>
          <button
            type="button"
            onClick={() => setPage(Math.min(pageCount - 1, current + 1))}
            disabled={current === pageCount - 1}
            aria-label="다음 페이지"
            className={`${pagerBtn} ${
              current === pageCount - 1
                ? "cursor-default bg-transparent text-[#565b78]"
                : "cursor-pointer bg-room-control text-white"
            }`}
          >
            <ChevronRightIcon size={18} />
          </button>
        </div>
      )}
    </>
  );
}
