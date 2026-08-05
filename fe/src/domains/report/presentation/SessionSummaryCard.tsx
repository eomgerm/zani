"use client";

import type { ReactNode } from "react";

import { PictoClockMuted, PictoLock, PictoWarn } from "@/shared/ui";
import type { SessionSummaryRequester } from "../infrastructure/sessionSummaryApi";
import { useSessionSummary } from "./useSessionSummary";

export interface SessionSummaryCardProps {
  readonly sessionId: string;
  /** 테스트에서 갈아끼우기 위한 선택 인자. 기본값이 실제 어댑터다. */
  readonly request?: SessionSummaryRequester;
}

const Notice = ({ icon, title, detail }: { icon: ReactNode; title: string; detail?: string }) => (
  <div className="py-8 text-center text-ink-fainter">
    <div className="mb-3 flex justify-center">{icon}</div>
    <div className="mb-1 font-bold text-ink-muted">{title}</div>
    {detail !== undefined && <div className="text-[13.5px]">{detail}</div>}
  </div>
);

/**
 * 수업 요약 카드 — 강사·학생 공통.
 *
 * <p>역할 인자를 받지 않는 것이 이 컴포넌트의 요점이다. 요약은 두 사람이 같은 문장을 보는 공통
 * 산출물이라(FRD §21) 탭이 갈려도 이 카드는 하나다 — 강사가 "요약 두 번째 문장" 이라고 말하면
 * 학생 화면의 같은 자리를 가리켜야 한다.
 *
 * <p>카드 껍데기와 제목은 어떤 상태에서도 남긴다. 상태에 따라 카드가 사라지면 아래 내용이 위로
 * 밀려 올라가 화면이 흔들린다.
 *
 * <p>서버가 주는 것은 문단 하나다. 프로토타입의 5절 구조는 fixture 였고, 없는 절 구분을 화면이
 * 지어내지 않는다 — 절이 필요해지면 그것을 만드는 쪽은 사후 분석이다.
 */
export function SessionSummaryCard({ sessionId, request }: SessionSummaryCardProps) {
  const { status, summary, retry } = useSessionSummary({ sessionId, request });

  return (
    <div className="z-card px-7 py-6">
      <div className="z-section-title mb-4">수업 요약 레포트</div>
      {status === "loading" && (
        <Notice icon={<PictoClockMuted size={36} />} title="수업 요약을 불러오는 중이에요" />
      )}
      {status === "notReady" && (
        <Notice
          icon={<PictoClockMuted size={36} />}
          title="아직 분석이 끝나지 않았어요"
          detail="분석이 완료되면 수업 요약을 볼 수 있어요."
        />
      )}
      {status === "forbidden" && (
        <Notice
          icon={<PictoLock size={36} />}
          title="이 수업의 요약을 볼 수 없어요"
          detail="내가 참여한 수업이 맞는지 확인해 주세요."
        />
      )}
      {(status === "failed" || (status === "ready" && summary === null)) && (
        <div className="py-8 text-center text-ink-fainter">
          <div className="mb-3 flex justify-center">
            <PictoWarn size={36} />
          </div>
          <div className="mb-1 font-bold text-ink-muted">수업 요약을 불러오지 못했어요</div>
          <button type="button" onClick={retry} className="z-btn z-btn-outline z-btn-md mt-3">
            다시 시도
          </button>
        </div>
      )}
      {status === "ready" && summary !== null && (
        <p className="whitespace-pre-line text-[13.5px] leading-[1.75] text-ink-sub">{summary}</p>
      )}
    </div>
  );
}
