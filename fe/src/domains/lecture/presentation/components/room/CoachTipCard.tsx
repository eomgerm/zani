"use client";

import type { CoachTip } from "../../../infrastructure/coachPollApi";

export interface CoachTipCardProps {
  readonly tip: CoachTip;
  onDismiss(): void;
}

/**
 * 강사에게 한 번에 하나씩 보여주는 수업 팁 카드(티켓 86).
 *
 * 문구는 서버가 §8 고정 템플릿으로 완성해 내려주므로 **그대로 표시하고 유형별로 화면을 나누지
 * 않는다.** `tipType` 은 검증·로깅용이고 `targetConcept` 은 이미 본문 문장에 녹아 있어 따로
 * 보여주지 않는다 — 무응답·자리비움 팁에는 아예 없어서 자리를 두면 유형마다 카드가 달라 보인다.
 *
 * 학생 이름·개별 응답·개별 판정은 담기지 않는다. 서버가 익명 집계만으로 만든 문구다.
 *
 * 스테이지 우측 상단에 놓아 강의 영상과 하단 제어를 가리지 않는다.
 */
export function CoachTipCard({ tip, onDismiss }: CoachTipCardProps) {
  return (
    <div
      role="status"
      data-testid="coach-tip-card"
      className="absolute right-2.5 top-2 z-[5] w-[290px] animate-[zPop_.2s] rounded-[18px] bg-surface p-[18px] text-ink shadow-[0_16px_44px_#0006]"
    >
      <div className="mb-2.5 flex items-start justify-between gap-2">
        <span className="text-[14.5px] font-extrabold leading-[1.4]">{tip.title}</span>
        <button
          type="button"
          onClick={onDismiss}
          aria-label="팁 닫기"
          className="shrink-0 cursor-pointer border-0 bg-transparent text-base text-ink-quiet"
        >
          ✕
        </button>
      </div>
      <p className="mb-3.5 text-[13px] leading-[1.6] text-ink-muted">{tip.message}</p>
      <button
        type="button"
        onClick={onDismiss}
        className="z-btn z-btn-primary w-full rounded-[12px] py-2.5 text-[13.5px]"
      >
        확인
      </button>
    </div>
  );
}
