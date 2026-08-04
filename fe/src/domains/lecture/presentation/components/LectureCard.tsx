import Link from "next/link";
import { PictoCalendarMuted, PictoClockMuted } from "@/shared/ui";
import { type MyLecture } from "../myLectures";
import { isLectureOpenable, statusInfo } from "../status";

/** 상태에 따라 카드 클릭 시 이동할 경로 (LIVE→강의실, 그 외→리포트) */
function hrefFor(l: MyLecture) {
  return l.status === "LIVE" ? `/room/${l.id}` : `/my-lectures/${l.id}/report`;
}

/**
 * 썸네일. 실제 화면 캡처가 없어 시안처럼 수업 자료를 닮은 판을 얹는다.
 * 상태 필은 우상단, LIVE 표시는 좌하단이다.
 */
function Thumb({ lecture }: { lecture: MyLecture }) {
  const si = statusInfo(lecture.status);

  return (
    <div className="relative aspect-video overflow-hidden rounded-[18px] bg-[#1f2433]">
      <div
        aria-hidden="true"
        className="absolute left-[8%] top-[10%] h-[80%] w-[84%] overflow-hidden rounded-lg bg-surface px-3.5 py-3"
      >
        <div className="mb-2.5 h-2.5 w-[52%] rounded-[5px] bg-[#22261f]" />
        <div className="mb-1.5 h-1.5 w-[94%] rounded bg-[#e4e8e5]" />
        <div className="mb-1.5 h-1.5 w-[86%] rounded bg-[#e4e8e5]" />
        <div className="mb-2.5 h-1.5 w-[64%] rounded bg-[#e4e8e5]" />
        <div className="flex gap-2">
          <div className="h-[34px] w-[38%] rounded-md bg-[#dff0e7]" />
          <div className="h-[34px] w-[30%] rounded-md bg-[#f0f2f0]" />
        </div>
      </div>
      <div className="absolute right-3.5 top-3.5">
        <span className="z-pill bg-surface px-3 py-[5px] text-xs text-[#3a3f3c] shadow-[0_2px_8px_rgba(20,40,30,.12)]">
          <span className="size-[7px] rounded-full" style={{ background: si.dot }} />
          {si.label}
        </span>
      </div>
      {lecture.status === "LIVE" && (
        <div className="absolute bottom-3.5 left-4">
          <span className="inline-flex items-center gap-[5px] rounded-full bg-[#22261f] px-[11px] py-[5px] text-xs font-extrabold text-white">
            <span className="size-1.5 rounded-full bg-danger" />
            LIVE
          </span>
        </div>
      )}
    </div>
  );
}

function Body({ lecture }: { lecture: MyLecture }) {
  return (
    <>
      <div className="line-clamp-2 px-1.5 pt-4 text-lg font-extrabold leading-[1.45]">
        {lecture.title}
      </div>
      {/*
        내가 들은 수업이면 "누구 수업인지", 내가 연 수업이면 "몇 명이 들었는지" 를 보여준다.
        같은 자리에 서로 다른 값을 두는 이유는 반대쪽이 자명하기 때문이다 — 참여강의의 학생 수나
        진행강의의 강사 이름은 카드를 보는 사람이 이미 안다.
      */}
      <div className="truncate px-1.5 pt-1 text-[13px] text-ink-muted">
        {lecture.role === "student"
          ? lecture.instructor
          : `인원 ${lecture.students}명`}
      </div>
      <div className="mx-1.5 my-3.5 h-px bg-line-light" />
      <div className="flex items-center gap-3.5 px-1.5 text-[13px] text-ink-fainter">
        <span className="flex items-center gap-1.5">
          <PictoClockMuted size={14} />
          {lecture.dur}
        </span>
        <span className="h-3 w-px bg-line-muted" />
        <span className="flex items-center gap-1.5">
          <PictoCalendarMuted size={14} />
          {lecture.date.replace(/-/g, ".")}
        </span>
      </div>
    </>
  );
}

/**
 * 진행 중인 수업으로 돌아가는 버튼.
 *
 * <p>카드 전체가 이미 강의실로 가는 링크지만, 나갔다가 돌아오는 것이 이 화면의 주된 쓰임이라 눈에 띄는 자리가 따로 있어야 한다. 프리조인을 다시 거치지 않고 바로 들어간다 — 이미 참가자이므로
 * 장치를 다시 고를 이유가 없다.
 */
function RejoinButton({ lecture }: { lecture: MyLecture }) {
  return (
    <Link
      href={`/room/${lecture.id}`}
      aria-label={`${lecture.title} 강의실 입장`}
      className="mt-3 flex w-full items-center justify-center rounded-xl bg-primary px-3 py-2 text-[13px] font-extrabold text-white no-underline transition-[filter] hover:brightness-110"
    >
      입장
    </Link>
  );
}

/**
 * 내 강의실 강의 카드.
 * 분석이 끝나지 않은 강의는 열 수 있는 화면이 없어 링크 없이 렌더한다(프로토타입 onThumb).
 */
export function LectureCard({ lecture }: { lecture: MyLecture }) {
  const cardCls = "z-card block p-3 pb-3.5 text-ink no-underline";
  const rejoinable = lecture.status === "LIVE" && lecture.rejoinable;

  if (!isLectureOpenable(lecture.status)) {
    return (
      <div className={cardCls}>
        <Thumb lecture={lecture} />
        <Body lecture={lecture} />
      </div>
    );
  }

  // 입장 버튼은 링크 안에 링크를 넣을 수 없어 카드를 감싸지 않는다. 대신 썸네일·본문만 링크로 둔다.
  if (rejoinable) {
    return (
      <div className={cardCls}>
        <Link href={hrefFor(lecture)} className="block cursor-pointer text-ink no-underline">
          <Thumb lecture={lecture} />
          <Body lecture={lecture} />
        </Link>
        <RejoinButton lecture={lecture} />
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
