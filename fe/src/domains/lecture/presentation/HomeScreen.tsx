"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/domains/auth";
import { inviteCodeFrom } from "@/domains/lecture/domain/inviteCode";
import { joinFailureMessage } from "@/domains/lecture/domain/joinFailure";
import {
  checkJoinable as checkJoinableApi,
  joinFailureReasonOf,
  type JoinableChecker,
} from "@/domains/lecture/infrastructure/joinSessionApi";
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
  checkJoinable = checkJoinableApi,
}: {
  /** 테스트에서 API 경계를 대체하기 위한 주입점. */
  requestSessionList?: SessionListRequester;
  /** 입장 가능 확인 어댑터. 테스트에서 대체한다. */
  checkJoinable?: JoinableChecker;
} = {}) {
  const { member, accessToken } = useAuth();
  const router = useRouter();
  const [inviteInput, setInviteInput] = useState("");
  const [inviteError, setInviteError] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);

  /**
   * 참여하기. 코드 형식과 **수업 상태**를 여기서 확인하고 통과할 때만 입장 전 점검으로 넘긴다.
   *
   * <p>상태 확인을 여기서 하는 게 중요하다. 서버 검증을 점검 화면의 입장 버튼까지 미루면, 학생이 카메라·마이크를 다 맞춘 뒤에야 "아직 시작하지 않은 수업"이라는 걸 알게 된다. 코드를 넣는
   * 자리에서 바로 알려주는 편이 되돌리기도 쉽다.
   *
   * <p>확인은 <b>읽기 전용</b>이다. 참가자를 만드는 입장은 장치 점검을 통과한 뒤 한 번만 부른다. 여기서 입장까지 해 버리면 점검에서 이탈한 학생이 정원을 물고 있어, 그런 학생이 29명이면 실제
   * 입장자 없이 방이 찬다.
   */
  const enterPrejoin = async () => {
    if (checking) {
      return;
    }
    const code = inviteCodeFrom(inviteInput);
    if (code === null) {
      setInviteError("초대 코드 또는 초대 링크를 확인해 주세요. 코드는 영문·숫자 8자예요.");
      return;
    }
    if (accessToken === null) {
      setInviteError("로그인이 필요해요. 다시 로그인한 뒤 시도해 주세요.");
      return;
    }

    setChecking(true);
    setInviteError(null);
    try {
      await checkJoinable(code, accessToken);
      router.push(`/prejoin/${code}`);
    } catch (caught) {
      setInviteError(joinFailureMessage(joinFailureReasonOf(caught), code));
      setChecking(false);
    }
  };
  // 조회 실패는 화면에 띄우지 않는다. 이 배너의 용도는 "돌아가기·종료"뿐이라, 상태를 알 수 없을 때
  // 경고를 내밀면 진행 중인 수업이 없는 사용자에게도 고장처럼 보인다. 실패 원인은 콘솔에만 남긴다.
  const { session: activeSession, refresh } = useActiveInstructorSession(requestSessionList);

  return (
    <>
      <h1 className="mb-2 text-3xl font-extrabold tracking-[-.7px]">
        안녕하세요, {member?.displayName ?? "사용자"}님!
      </h1>
      <p className="mb-[26px] text-[15px] text-ink-faint">
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
          className="mb-[34px] flex items-center gap-[18px] rounded-[18px] border border-line-mint bg-primary-softer px-6 py-[18px]"
        >
          <span className="flex size-[52px] shrink-0 items-center justify-center rounded-full bg-surface shadow-[0_4px_14px_rgba(18,184,134,.16)]">
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


      <h2 className="mb-1.5 text-[21px] font-extrabold tracking-[-.4px]">무엇을 할까요?</h2>
      <p className="mb-5 text-sm text-ink-faint">
        새로운 수업을 시작하거나, 참여할 수업에 입장해보세요.
      </p>

      <div className="grid grid-cols-2 gap-[22px]">
        {/* 강의실 만들기 */}
        <div className="relative flex flex-col overflow-hidden rounded-[20px] border border-line-mint bg-canvas px-7 py-[30px]">
          <CornerIcon>
            <svg width="38" height="38" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <rect x="3" y="5" width="18" height="16" rx="3" fill="#fff" />
              <rect x="3" y="5" width="18" height="5" rx="3" fill="#10b981" />
              <path d="M8 3v4M16 3v4" stroke="#10b981" strokeWidth="2.2" strokeLinecap="round" />
              <rect x="6.5" y="12.5" width="2.6" height="2.6" rx=".6" fill="#c3c8f7" />
              <rect x="10.7" y="12.5" width="2.6" height="2.6" rx=".6" fill="#c3c8f7" />
              <rect x="14.9" y="12.5" width="2.6" height="2.6" rx=".6" fill="#c3c8f7" />
              <rect x="6.5" y="16.4" width="2.6" height="2.6" rx=".6" fill="#c3c8f7" />
              <rect x="10.7" y="16.4" width="2.6" height="2.6" rx=".6" fill="#c3c8f7" />
            </svg>
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
        <div className="relative flex flex-col overflow-hidden rounded-[20px] border border-line-mint bg-canvas px-7 py-[30px]">
          <CornerIcon>
            <svg
              width="36"
              height="36"
              viewBox="0 0 24 24"
              fill="none"
              stroke="#1ece8a"
              strokeWidth="2.2"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <path d="M9.5 14.5l5-5" />
              <path d="M13 7l1.5-1.5a3.5 3.5 0 0 1 5 5L18 12" />
              <path d="M11 17l-1.5 1.5a3.5 3.5 0 0 1-5-5L6 12" />
            </svg>
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
                if (event.key === "Enter") void enterPrejoin();
              }}
              placeholder="초대 코드 또는 초대 링크 입력"
              className="z-input min-w-0 flex-1 rounded-[13px] px-4 py-[15px] text-sm"
            />
            <button
              type="button"
              data-testid="home-join-button"
              disabled={checking}
              onClick={() => void enterPrejoin()}
              className="z-btn z-btn-primary z-btn-lg whitespace-nowrap disabled:cursor-not-allowed disabled:opacity-40"
            >
              {checking ? "확인 중…" : "참여하기"}
            </button>
          </div>
          {inviteError !== null && (
            <div role="alert" data-testid="home-join-error" className="mt-2.5 text-[13px] text-danger">
              {inviteError}
            </div>
          )}
        </div>
      </div>
    </>
  );
}

/** 카드 우상단의 큰 아이콘 배지 */
function CornerIcon({ children }: { children: React.ReactNode }) {
  return (
    <div className="absolute right-6 top-6 flex size-[76px] items-center justify-center rounded-[18px] bg-[linear-gradient(135deg,#daf3ea,#c9ecdf)] shadow-[0_12px_30px_rgba(18,184,134,.22)]">
      {children}
    </div>
  );
}
