import Link from "next/link";
import { CalendarIcon, ClockIcon } from "@/shared/ui";
import { type Lecture } from "../fixtures";
import { isLectureOpenable, statusInfo } from "../status";

/** 상태에 따라 카드 클릭 시 이동할 경로 (LIVE→강의실, 그 외→리포트) */
function hrefFor(l: Lecture) {
  return l.status === "LIVE" ? `/room/${l.id}` : `/my-lectures/${l.id}/report`;
}

/** 썸네일 우하단에 크게 흘려 넣는 제목 앞 두 글자 */
function Thumb({ lecture }: { lecture: Lecture }) {
  const si = statusInfo(lecture.status);

  return (
    <div className="relative flex aspect-[16/10] items-center justify-center overflow-hidden rounded-[13px] bg-[#20233a]">
      <span
        aria-hidden="true"
        className="absolute -bottom-2.5 -right-1.5 text-[64px] font-black tracking-[-2px] text-white/[.08]"
      >
        {(lecture.title || "ZANI").slice(0, 2)}
      </span>
      <div className="absolute left-3.5 top-3.5">
        <span className="z-pill bg-surface px-[11px] py-[5px] text-xs text-[#3a3f5c] shadow-[0_2px_8px_rgba(24,74,62,.14)]">
          <span className="size-[7px] rounded-full" style={{ background: si.dot }} />
          {si.label}
        </span>
      </div>
      {lecture.status === "LIVE" && (
        <div className="absolute right-3.5 top-3.5">
          <span className="inline-flex items-center gap-[5px] rounded-[7px] bg-danger px-[9px] py-1 text-[11px] font-extrabold text-white">
            <span className="size-1.5 rounded-full bg-white" />
            LIVE
          </span>
        </div>
      )}
    </div>
  );
}

function Body({ lecture }: { lecture: Lecture }) {
  return (
    <>
      <div className="truncate px-1.5 pt-4 text-[17px] font-extrabold leading-[1.4]">
        {lecture.title}
      </div>
      <div className="mx-1.5 my-3.5 h-px bg-line-light" />
      <div className="flex items-center gap-3.5 px-1.5 text-[13px] text-ink-fainter">
        <span className="flex items-center gap-1.5">
          <ClockIcon />
          {lecture.dur}
        </span>
        <span className="h-3 w-px bg-line-muted" />
        <span className="flex items-center gap-1.5">
          <CalendarIcon size={15} />
          {lecture.date.replace(/-/g, ".")}
        </span>
      </div>
    </>
  );
}

/**
 * 내 강의실 강의 카드.
 * 분석이 끝나지 않은 강의는 열 수 있는 화면이 없어 링크 없이 렌더한다(프로토타입 onThumb).
 */
export function LectureCard({ lecture }: { lecture: Lecture }) {
  const cardCls = "z-card block p-3 pb-3.5 text-ink no-underline";

  if (!isLectureOpenable(lecture.status)) {
    return (
      <div className={cardCls}>
        <Thumb lecture={lecture} />
        <Body lecture={lecture} />
      </div>
    );
  }

  return (
    <Link href={hrefFor(lecture)} className={`${cardCls} cursor-pointer`}>
      <Thumb lecture={lecture} />
      <Body lecture={lecture} />
    </Link>
  );
}
