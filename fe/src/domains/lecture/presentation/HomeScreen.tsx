"use client";

import Link from "next/link";
import { useAuth } from "@/domains/auth";

/**
 * SC-03 홈. 진행 중 수업 배너 + 강의실 만들기/참여하기 진입 화면.
 */
export function HomeScreen() {
  const { member } = useAuth();

  return (
    <>
      <h1 className="mb-2 text-3xl font-extrabold tracking-[-.7px]">
        안녕하세요, {member?.displayName ?? "사용자"}님!
      </h1>
      <p className="mb-[26px] text-[15px] text-ink-faint">
        ZANI에서 수업을 시작하고, 함께 배워보세요.
      </p>

      {/* 진행 중 수업 배너 */}
      <div className="mb-[34px] flex items-center gap-[18px] rounded-[18px] border border-line-mint bg-primary-softer px-6 py-[18px]">
        <span className="flex size-[52px] shrink-0 items-center justify-center rounded-full bg-surface text-2xl shadow-[0_4px_14px_rgba(18,184,134,.16)]">
          🎥
        </span>
        <div className="min-w-0 flex-1">
          <div className="mb-[5px] text-[19px] font-extrabold tracking-[-.3px]">
            JavaScript 비동기 마스터
          </div>
          <div className="text-[13.5px] font-semibold text-ink-faint">
            최민서 선생님 · 진행 중 · 지금 다시 입장할 수 있어요
          </div>
        </div>
        <Link href="/room/s6" className="z-btn z-btn-primary z-btn-md shrink-0 text-[14.5px]">
          수업으로 돌아가기
        </Link>
      </div>

      <h2 className="mb-1.5 text-[21px] font-extrabold tracking-[-.4px]">무엇을 할까요?</h2>
      <p className="mb-5 text-sm text-ink-faint">
        새로운 수업을 시작하거나, 참여할 수업에 입장해보세요.
      </p>

      <div className="grid grid-cols-2 gap-[22px]">
        {/* 강의실 만들기 */}
        <div className="relative flex flex-col overflow-hidden rounded-[20px] border border-line-mint bg-canvas px-7 py-[30px]">
          <CornerIcon>🎬</CornerIcon>
          <h3 className="mb-3 text-[22px] font-extrabold tracking-[-.4px]">강의실 만들기</h3>
          <p className="mb-7 max-w-[200px] text-sm leading-[1.55] text-ink-faint">
            지금 바로 강의실을 만들고
            <br />
            수업을 시작할 수 있어요.
          </p>
          <div className="flex-1" />
          <Link href="/create" className="z-btn z-btn-primary z-btn-block">
            수업 시작하기
          </Link>
        </div>

        {/* 강의실 참여하기 */}
        <div className="relative flex flex-col overflow-hidden rounded-[20px] border border-line-mint bg-canvas px-7 py-[30px]">
          <CornerIcon>🎟️</CornerIcon>
          <h3 className="mb-3 text-[22px] font-extrabold tracking-[-.4px]">강의실 참여하기</h3>
          <p className="mb-7 max-w-[200px] text-sm leading-[1.55] text-ink-faint">
            초대 코드 또는 링크로
            <br />
            수업에 참여할 수 있어요.
          </p>
          <div className="flex-1" />
          <div className="flex gap-3">
            <input
              placeholder="초대 코드 또는 초대 링크 입력"
              className="z-input min-w-0 flex-1 rounded-[13px] px-4 py-[15px] text-sm"
            />
            <Link
              href="/prejoin/ZANI-8KQ"
              className="z-btn z-btn-primary z-btn-lg whitespace-nowrap"
            >
              참여하기
            </Link>
          </div>
        </div>
      </div>
    </>
  );
}

/** 카드 우상단의 큰 아이콘 배지 */
function CornerIcon({ children }: { children: React.ReactNode }) {
  return (
    <div className="absolute right-[26px] top-[26px] flex size-24 items-center justify-center rounded-[22px] bg-[linear-gradient(135deg,#daf3ea,#c9ecdf)] text-[44px] shadow-[0_12px_30px_rgba(18,184,134,.22)]">
      {children}
    </div>
  );
}
