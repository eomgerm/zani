"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/domains/auth";
import { PictoLink, PictoPlus } from "@/shared/ui";
import { inviteCodeFrom } from "@/domains/lecture/domain/inviteCode";
import type { SessionListRequester } from "@/domains/lecture/infrastructure/sessionListApi";
import { EndSessionButton } from "./components/room/EndSessionButton";
import { useActiveInstructorSession } from "./useActiveInstructorSession";

/** 초대 코드를 눈으로 읽기 쉽게 네 글자씩 끊는다. */
const formatInviteCode = (code: string) =>
  code.length === 8 ? `${code.slice(0, 4)}-${code.slice(4)}` : code;

/**
 * SC-03 홈. 내가 열어 둔 수업 배너 + 강의실 만들기/참여하기 진입 화면.
 */
export function HomeScreen({
  requestSessionList,
}: {
  /** 테스트에서 API 경계를 대체하기 위한 주입점. */
  requestSessionList?: SessionListRequester;
} = {}) {
  const { member } = useAuth();
  const router = useRouter();
  const [inviteInput, setInviteInput] = useState("");
  const [inviteError, setInviteError] = useState<string | null>(null);

  // 코드 형식은 여기서 판정한다. 서버까지 보내 400 을 받은 뒤 입장 전 점검 화면에서 알려주면, 사용자는 이미 화면을 옮긴 뒤라 어디를 고쳐야 하는지 알기 어렵다.
  const enterPrejoin = () => {
    const code = inviteCodeFrom(inviteInput);
    if (code === null) {
      setInviteError("초대 코드 또는 초대 링크를 확인해 주세요. 코드는 영문·숫자 8자예요.");
      return;
    }
    router.push(`/prejoin/${code}`);
  };
  // 조회 실패는 화면에 띄우지 않는다. 이 배너의 용도는 "돌아가기·종료"뿐이라, 상태를 알 수 없을 때
  // 경고를 내밀면 진행 중인 수업이 없는 사용자에게도 고장처럼 보인다. 실패 원인은 콘솔에만 남긴다.
  const { session: activeSession, refresh } = useActiveInstructorSession(requestSessionList);

  return (
    <>
      <h1 className="mb-2 text-3xl font-extrabold tracking-[-.7px]">
        안녕하세요, {member?.displayName ?? "사용자"}님!
      </h1>
      <p className="mb-[26px] text-base text-ink-faint">
        ZANI에서 수업을 시작하고, 함께 배워보세요.
      </p>

      {/*
        내가 열어 둔 수업 배너. 실제로 활성 수업이 있을 때만 나타난다.
        한 강사는 활성 수업을 하나만 가질 수 있어, 이 수업을 끝내지 않으면 새로 만들 수 없다.
        그래서 돌아가기와 함께 종료도 여기서 할 수 있어야 한다 — 아니면 새 수업을 만들 길이 막힌다.
        제목은 목록 API 가 주지 않아 표시하지 않는다.
      */}
      {activeSession !== null && (
        <div
          data-testid="active-session-banner"
          className="mb-[34px] flex items-center gap-[18px] rounded-2xl border border-shell-line bg-shell px-6 py-[18px]"
        >
          <span className="flex size-[52px] shrink-0 items-center justify-center rounded-full bg-surface">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <rect x="2.5" y="6.5" width="12" height="11" rx="2.5" fill="#10b981" />
              <path d="M15 10l6-3.5v11L15 14z" fill="#10b981" />
            </svg>
          </span>
          <div className="min-w-0 flex-1">
            <div className="mb-[5px] text-[19px] font-extrabold tracking-[-.3px]">
              {activeSession.status === "LIVE" ? "진행 중인 수업이 있어요" : "시작을 기다리는 수업이 있어요"}
            </div>
            <div className="text-[13.5px] font-semibold text-ink-faint">
              초대 코드 {formatInviteCode(activeSession.inviteCode)} · 새 수업을 만들려면 이 수업을
              먼저 종료해 주세요
            </div>
          </div>
          <Link
            href={`/room/${activeSession.sessionId}`}
            className="z-btn z-btn-primary z-btn-md shrink-0 text-[14.5px]"
          >
            수업으로 돌아가기
          </Link>
          <div className="shrink-0">
            <EndSessionButton sessionId={activeSession.sessionId} onEnded={refresh} />
          </div>
        </div>
      )}


      <h2 className="mb-1.5 text-[22px] font-extrabold tracking-[-.4px]">무엇을 할까요?</h2>
      <p className="mb-5 text-sm text-ink-faint">
        새로운 수업을 시작하거나, 참여할 수업에 입장해보세요.
      </p>

      <div className="grid grid-cols-2 gap-[22px]">
        {/* 강의실 만들기 */}
        <div className="relative flex flex-col overflow-hidden rounded-2xl border border-shell-line bg-shell px-7 py-[30px]">
          <CornerIcon>
            <PictoPlus size={36} />
          </CornerIcon>
          <h3 className="mb-3 whitespace-nowrap pr-[92px] text-[22px] font-extrabold tracking-[-.4px]">
            강의실 만들기
          </h3>
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
        <div className="relative flex flex-col overflow-hidden rounded-2xl border border-shell-line bg-shell px-7 py-[30px]">
          <CornerIcon>
            <PictoLink size={36} />
          </CornerIcon>
          <h3 className="mb-3 whitespace-nowrap pr-[92px] text-[22px] font-extrabold tracking-[-.4px]">
            강의실 참여하기
          </h3>
          <p className="mb-7 max-w-[200px] text-sm leading-[1.55] text-ink-faint">
            초대 코드 또는 링크로
            <br />
            수업에 참여할 수 있어요.
          </p>
          <div className="flex-1" />
          <div className="flex gap-3">
            <input
              aria-label="초대 코드 또는 초대 링크"
              value={inviteInput}
              onChange={(event) => {
                setInviteInput(event.target.value);
                setInviteError(null);
              }}
              onKeyDown={(event) => {
                if (event.key === "Enter") enterPrejoin();
              }}
              placeholder="초대 코드 또는 초대 링크 입력"
              className="z-input min-w-0 flex-1 rounded-[13px] px-4 py-[15px] text-sm"
            />
            <button
              type="button"
              onClick={enterPrejoin}
              className="z-btn z-btn-primary z-btn-lg whitespace-nowrap"
            >
              참여하기
            </button>
          </div>
          {inviteError !== null && (
            <div role="alert" className="mt-2.5 text-[13px] text-danger">
              {inviteError}
            </div>
          )}
        </div>
      </div>
    </>
  );
}

/** 카드 우상단의 큰 아이콘 배지. 시안은 그라디언트·글로우 없이 플랫 민트 한 겹이다. */
function CornerIcon({ children }: { children: React.ReactNode }) {
  return (
    <div className="absolute right-6 top-6 flex size-[76px] items-center justify-center rounded-2xl bg-shell-icon">
      {children}
    </div>
  );
}
