"use client";

import { useState } from "react";
import { ChevronLeftIcon, ChevronRightIcon } from "@/shared/ui";
import { ParticipantTile, type ParticipantTileData } from "./ParticipantTile";

/** 한 페이지에 항상 채워 넣는 타일 수 */
const PER_PAGE = 12;

type ParticipantGridProps = {
  participants: ParticipantTileData[];
  currentParticipantId?: string;
  isInstructor?: boolean;
  /** 사이드 패널이 열려 폭이 좁을 때 3열로 줄인다 */
  narrow?: boolean;
};

const pagerBtn = "flex size-[34px] items-center justify-center rounded-full border-0";

/**
 * 갤러리 보기. 한 페이지에 12명씩 보여주고 넘치면 페이지로 나눈다.
 *
 * 남은 인원이 12명보다 적으면 앞에서부터 다시 채워 그리드를 항상 가득 채운다(기획 확정 사항).
 * 그래서 같은 참가자가 한 페이지에 두 번 나올 수 있고, key도 참가자 id만으로는 유일하지 않다.
 */
export function ParticipantGrid({
  participants,
  currentParticipantId,
  isInstructor = false,
  narrow = false,
}: ParticipantGridProps) {
  const [page, setPage] = useState(0);

  const total = participants.length;
  const pageCount = Math.max(1, Math.ceil(total / PER_PAGE));
  // 참가자가 빠져 페이지 수가 줄어들면 마지막 페이지로 당긴다.
  const current = Math.min(page, pageCount - 1);
  const hasPages = pageCount > 1;

  const slots = total
    ? Array.from({ length: PER_PAGE }, (_, i) => participants[(current * PER_PAGE + i) % total])
    : [];

  return (
    <>
      <div
        role="group"
        aria-label={`참가자 ${total}명`}
        className={`grid h-full gap-3 overflow-hidden p-4 ${
          narrow ? "grid-cols-3 grid-rows-4" : "grid-cols-4 grid-rows-3"
        } ${hasPages ? "pb-[58px]" : ""}`}
      >
        {slots.map((participant, index) => (
          <ParticipantTile
            key={`${participant.id}-${index}`}
            participant={participant}
            canControl={
              isInstructor &&
              participant.role === "student" &&
              participant.id !== currentParticipantId
            }
          />
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
