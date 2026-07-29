"use client";

import type { CoachingAvailability } from "../../../domain/coachingAvailability";

/**
 * 강사에게 보여줄 코칭 가용 상태 배지(티켓 76).
 *
 * 보여주는 것은 **코칭이 지금 되고 있는지**뿐이다. 학생 이름·개별 응답·개별 판정은 물론
 * 집계 수치도 담지 않는다. 팁 자체는 별도 카드(86)가 표시한다.
 *
 * 정상일 때 아무것도 띄우지 않는 것은 학생 배지와 같은 이유다 — 팁은 10분에 한 번 수준이라
 * 그동안 계속 "정상"을 띄우면 화면만 시끄러워진다.
 *
 * 상단 바 안에 흐름대로 놓이는 칩이다. 띄워 얹으면 수업 조작을 가린다.
 */
const COPY: Record<CoachingAvailability, string | null> = {
  ACTIVE: null,
  TRANSCRIPTION_FAILED: "수업 음성을 인식하지 못하고 있어요",
  TIP_FAILED: "수업 팁을 만들지 못하고 있어요",
  POLL_FAILED: "수업 팁을 받아오지 못하고 있어요",
};

export interface CoachingStatusNoticeProps {
  readonly availability: CoachingAvailability;
}

export function CoachingStatusNotice({ availability }: CoachingStatusNoticeProps) {
  const label = COPY[availability];
  if (label === null) {
    return null;
  }

  return (
    <div
      role="status"
      data-testid="coaching-status-notice"
      className="whitespace-nowrap rounded-[11px] border border-[#f3dc90] bg-warn-soft px-3 py-[9px] font-sans text-[12px] font-bold text-[#836607]"
    >
      {label}
    </div>
  );
}
