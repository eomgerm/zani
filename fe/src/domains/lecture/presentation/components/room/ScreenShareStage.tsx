"use client";

import { useState } from "react";
import { GridContainer, GridItem } from "@thangdevalone/meeting-grid-layout-react";
import { ChevronLeftIcon, ChevronRightIcon } from "@/shared/ui";
import { ParticipantTile, type ParticipantTileData } from "./ParticipantTile";
import {
  ITEMS_PER_PAGE,
  SNAP_SIZE,
  TILE_ASPECT_RATIO,
  TILE_FILL,
  TILE_GAP,
} from "./roomGridLayout";

/** 공유 화면은 배치기의 0번 칸이다. 참가자는 그 뒤로 이어진다. */
const SHARED_SCREEN_INDEX = 0;

const pagerBtn = "flex size-[30px] items-center justify-center rounded-full border-0";

type ScreenShareStageProps = {
  participants: ParticipantTileData[];
  /** 공유 화면 트랙을 붙일 video ref. */
  attachScreen: React.Ref<HTMLVideoElement>;
  /** 공유 화면 왼쪽 위 칩에 넣을 문구. 내 공유면 "내 화면". */
  sharerLabel: string;
  /** 참가자별 카메라 video ref 를 만들어 주는 함수. 없으면 아바타만 보여준다. */
  videoRefFor?: (identity: string) => React.Ref<HTMLVideoElement>;
  /** 내 타일은 거울처럼 뒤집는다. */
  localParticipantId: string | null;
};

/**
 * 화면 공유 스테이지. 공유 화면을 크게 고정(pin)하고 참가자를 왼쪽 줄에 세운다.
 *
 * <p>공유 화면과 참가자를 각각 다른 방식으로 배치하지 않는다 — 예전에는 공유 영상이 스테이지를 꽉 채우고 참가자 줄이 그 위에 절대 위치로 얹혀 있어, 줄이 공유 화면의 어느 부분을 가릴지 알 수 없었다. 배치기에 둘을 함께 넘기면 핀과 나머지가 서로 자리를 비켜 준다.
 *
 * <p>참가자가 한 페이지를 넘으면 페이지로 나눈다. 좁은 줄에 다 밀어 넣으면 타일이 얼굴을 알아볼 수 없는 크기까지 작아진다.
 */
export function ScreenShareStage({
  participants,
  attachScreen,
  sharerLabel,
  videoRefFor,
  localParticipantId,
}: ScreenShareStageProps) {
  const [page, setPage] = useState(0);

  const pageCount = Math.max(1, Math.ceil(participants.length / ITEMS_PER_PAGE));
  // 참가자가 빠져 페이지 수가 줄어들면 마지막 페이지로 당긴다.
  const current = Math.min(page, pageCount - 1);
  const hasPages = pageCount > 1;

  return (
    <div className="absolute inset-0 z-[6] bg-stage p-3.5">
      <GridContainer
        role="group"
        aria-label={`공유 화면과 참가자 ${participants.length}명`}
        count={participants.length + 1}
        aspectRatio={TILE_ASPECT_RATIO}
        gap={TILE_GAP}
        // 비율이 맞지 않는 칸에서는 좌우에 여백을 남긴다 — 잘라내지 않는다(티켓 246).
        forceAspectRatio
        // 공유 화면을 왼쪽에 크게 고정하고 참가자를 오른쪽 줄로 보낸다.
        pinnedIndex={SHARED_SCREEN_INDEX}
        othersPosition="right"
        // 2명일 때 기본값인 떠다니는 PiP 를 끈다. 진짜 Document PiP 를 따로 쓴다.
        disableFloat
        maxVisible={ITEMS_PER_PAGE}
        currentVisiblePage={current}
        springPreset="smooth"
      >
        <GridItem index={SHARED_SCREEN_INDEX} transition={SNAP_SIZE}>
          <div className="relative size-full overflow-hidden rounded-[14px] border border-[#1e2740] bg-[#0f1626]">
            <video
              ref={attachScreen}
              autoPlay
              muted
              playsInline
              data-testid="screen-share-video"
              className="size-full object-contain"
            />
            <div className="z-stage-chip absolute left-4 top-4 flex items-center gap-1.5 font-bold">
              <span className="size-2 rounded-full bg-primary" />
              {sharerLabel}
            </div>
          </div>
        </GridItem>

        {participants.map((participant, index) => (
          <GridItem key={participant.id} index={index + 1} transition={SNAP_SIZE}>
            <ParticipantTile
              fit={TILE_FILL}
              participant={participant}
              videoRef={videoRefFor?.(participant.id)}
              mirrored={participant.id === localParticipantId}
            />
          </GridItem>
        ))}
      </GridContainer>

      {hasPages && (
        /* 페이저는 참가자 줄 아래에 둔다 — 넘기는 대상이 그쪽이다. */
        <div className="absolute bottom-5 right-5 z-[8] flex items-center gap-2 rounded-full border border-room-line bg-[#0e1020cc] p-1 pl-1.5 backdrop-blur-[6px]">
          <button
            type="button"
            onClick={() => setPage(Math.max(0, current - 1))}
            disabled={current === 0}
            aria-label="이전 참가자 페이지"
            className={`${pagerBtn} ${
              current === 0
                ? "cursor-default bg-transparent text-[#565b78]"
                : "cursor-pointer bg-room-control text-white"
            }`}
          >
            <ChevronLeftIcon size={16} />
          </button>
          <span className="min-w-8 text-center font-mono text-[12px] font-extrabold text-[#e7e9fb]">
            {current + 1}/{pageCount}
          </span>
          <button
            type="button"
            onClick={() => setPage(Math.min(pageCount - 1, current + 1))}
            disabled={current === pageCount - 1}
            aria-label="다음 참가자 페이지"
            className={`${pagerBtn} ${
              current === pageCount - 1
                ? "cursor-default bg-transparent text-[#565b78]"
                : "cursor-pointer bg-room-control text-white"
            }`}
          >
            <ChevronRightIcon size={16} />
          </button>
        </div>
      )}
    </div>
  );
}
