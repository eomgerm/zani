"use client";

import { useState } from "react";
import { ChevronLeftIcon, ChevronRightIcon } from "@/shared/ui";
import { ParticipantTile, type ParticipantTileData } from "./ParticipantTile";

/** 한 페이지에 보여주는 최대 타일 수 */
const PER_PAGE = 12;

/** 타일 가로:세로 비율. 웹캠이 가로로 잡으니 가로가 긴 형태가 화면을 덜 남긴다. */
const TILE_WIDTH = 4;
const TILE_HEIGHT = 3;

/**
 * 칸 안에 들어가는 최대 4:3 상자.
 *
 * <p>`aspectRatio` 만 주면 폭을 꽉 채운 뒤 높이가 칸을 넘쳐 잘린다. `max-height` 로는 막을 수 없다 — 높이를 깎아도 폭이 되돌아오지 않아 비율이 깨진다. 그래서 높이에서
 * 폭을 거꾸로 계산해 상한을 둔다. `cqh` 는 칸 높이의 1% 라 `100cqh` 가 칸 높이이고, 그 높이를 꽉 채우는 폭은 여기에 비율을 곱한 값이다(칸이 `container-type: size` 여야 한다).
 */
export const TILE_FIT: React.CSSProperties = {
  aspectRatio: `${TILE_WIDTH} / ${TILE_HEIGHT}`,
  width: `min(100%, calc(100cqh * ${TILE_WIDTH} / ${TILE_HEIGHT}))`,
};

/**
 * 인원수에 맞는 열 수. Meet·Webex 처럼 인원이 늘 때만 열을 늘려, 적은 인원에서 타일이 크게 보이도록 한다.
 *
 * <p>1명이면 1열(화면을 꽉 채운다), 2~4명이면 2열, 5~9명이면 3열, 그 이상은 4열이다. 사이드 패널이 열려 폭이 좁으면 3열까지만 쓴다.
 */
export function columnsFor(count: number, narrow: boolean): number {
  const columns = count <= 1 ? 1 : count <= 4 ? 2 : count <= 9 ? 3 : 4;
  return narrow ? Math.min(columns, 3) : columns;
}

type ParticipantGridProps = {
  participants: ParticipantTileData[];
  currentParticipantId?: string;
  isInstructor?: boolean;
  /** 사이드 패널이 열려 폭이 좁을 때 3열로 줄인다 */
  narrow?: boolean;
  /** 참가자별 카메라 video ref 를 만들어 주는 함수. 없으면 화면 없이 아바타만 보여준다. */
  videoRefFor?: (identity: string) => React.Ref<HTMLVideoElement>;
};

const pagerBtn = "flex size-[34px] items-center justify-center rounded-full border-0";

/**
 * 갤러리 보기. 한 페이지에 최대 12명을 보여주고 넘치면 페이지로 나눈다.
 *
 * 한 사람당 타일 하나다. 예전에는 12칸을 채우려고 남는 자리에 앞 참가자를 다시 넣었는데, 실제 참가자로 바뀐 뒤에는 같은 사람이 여러 번 보여 인원을 오해하게 만든다.
 *
 * 행 수는 인원에 맞춰 늘어난다. 고정 3행이면 혼자 있을 때 타일이 좌상단에 작게 남는다.
 */
export function ParticipantGrid({
  participants,
  currentParticipantId,
  isInstructor = false,
  narrow = false,
  videoRefFor,
}: ParticipantGridProps) {
  const [page, setPage] = useState(0);

  const total = participants.length;
  const pageCount = Math.max(1, Math.ceil(total / PER_PAGE));
  // 참가자가 빠져 페이지 수가 줄어들면 마지막 페이지로 당긴다.
  const current = Math.min(page, pageCount - 1);
  const hasPages = pageCount > 1;

  const slots = participants.slice(current * PER_PAGE, (current + 1) * PER_PAGE);
  const columns = columnsFor(slots.length, narrow);
  const rows = Math.max(1, Math.ceil(slots.length / columns));

  return (
    <>
      <div
        role="group"
        aria-label={`참가자 ${total}명`}
        className={`grid h-full min-h-0 gap-3 overflow-hidden p-4 ${hasPages ? "pb-[58px]" : ""}`}
        style={{
          gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`,
          gridTemplateRows: `repeat(${rows}, minmax(0, 1fr))`,
        }}
      >
        {slots.map((participant) => (
          // 칸 안에서 비율을 지키며 가운데 정렬한다. 칸을 그대로 채우면 인원수에 따라 타일이 찌그러진다.
          <div
            key={participant.id}
            className="flex min-h-0 items-center justify-center [container-type:size]"
          >
            <ParticipantTile
              fit={TILE_FIT}
              participant={participant}
              canControl={
                isInstructor &&
                participant.role === "student" &&
                participant.id !== currentParticipantId
              }
              videoRef={videoRefFor?.(participant.id)}
              mirrored={participant.id === currentParticipantId}
            />
          </div>
        ))}
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
