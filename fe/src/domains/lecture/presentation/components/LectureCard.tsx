import Link from "next/link";
import { thumbPalette, type Lecture } from "../fixtures";
import { statusInfo } from "../status";

/** 상태에 따라 카드 클릭 시 이동할 경로 (LIVE→강의실, 그 외→리포트) */
function hrefFor(l: Lecture) {
  return l.status === "LIVE" ? `/room/${l.id}` : `/my-lectures/${l.id}/report`;
}

export function LectureCard({ lecture, index }: { lecture: Lecture; index: number }) {
  const si = statusInfo(lecture.status);
  const palette = thumbPalette[index % thumbPalette.length];
  const dateDot = lecture.date.replace(/-/g, ".").slice(2);

  return (
    <Link href={hrefFor(lecture)} className="z-card block p-3.5 text-ink no-underline">
      <div
        className="relative flex aspect-video items-center justify-center overflow-hidden rounded-xl"
        style={{ background: palette.bg }}
      >
        <span
          className="px-4 text-center font-mono text-[15px] font-bold opacity-85"
          style={{ color: palette.fg }}
        >
          {lecture.title}
        </span>
        <div className="absolute left-3.5 top-3.5">
          <span
            className="z-pill px-[9px] py-1 text-[11px]"
            style={{ background: si.bg, color: si.fg }}
          >
            <span className="size-1.5 rounded-full" style={{ background: si.dot }} />
            {si.label}
          </span>
        </div>
        {lecture.status === "LIVE" && (
          <div className="absolute right-3.5 top-3.5">
            <span className="z-pill bg-danger px-[9px] py-1 text-[11px] text-white">
              <span className="size-1.5 rounded-full bg-white" />
              LIVE
            </span>
          </div>
        )}
      </div>

      <div className="truncate px-1.5 pt-4 text-[17px] font-extrabold leading-[1.4]">
        {lecture.title}
      </div>
      <div className="mx-1.5 my-3.5 h-px bg-line-light" />
      <div className="flex items-center gap-3.5 px-1.5 text-[13px] text-ink-fainter">
        <span className="flex items-center gap-1.5">🕐 {lecture.dur}</span>
        <span className="h-3 w-px bg-line-muted" />
        <span className="flex items-center gap-1.5">📅 {dateDot}</span>
      </div>
    </Link>
  );
}
