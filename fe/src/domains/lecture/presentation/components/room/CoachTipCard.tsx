"use client";

import type { CoachTip } from "../../../infrastructure/coachPollApi";

export interface CoachTipCardProps {
  readonly tip: CoachTip;
  /**
   * 이 카드를 그릴 표면의 배치(자리·너비·쌓임 순서). 아래 상수 중 하나를 넘긴다.
   *
   * 배치를 호출부가 아니라 이 모듈이 들고 있는 이유: 자리와 z 값이 서로를 전제하기 때문이다.
   * 공유 중 z 만 올리면 미니 로스터를 덮고, 자리만 옮기면 오버레이 뒤에 그대로 깔린다.
   */
  readonly className?: string;
  onDismiss(): void;
}

/** 스테이지 우측 상단. 화면 공유가 없을 때의 기본 자리다. */
export const COACH_TIP_STAGE_PLACEMENT = "absolute right-2.5 top-2 z-[5] w-[290px]";

/**
 * 화면 공유 중 메인 창.
 *
 * z 는 공유 오버레이(`z-[6]`)보다 커야 한다 — 오버레이가 쌓임 맥락을 만들어 그 안의 미니
 * 로스터(`z-[8]`)까지 통째로 6층에 얹히므로, 7 이상이면 전부 위로 올라온다. 여유를 두어 9 로 둔다.
 *
 * 자리를 좌측 상단으로 옮기는 것은 같은 문제의 나머지 절반이다. 우측 상단을 그대로 쓰면 카드가
 * 미니 로스터를 덮는데, 그 로스터가 공유 중 강사가 학생 얼굴을 보는 유일한 창이다. "자리를 비운
 * 것 같아요" 를 띄우면서 확인할 화면을 가릴 수는 없다. 좌측 상단의 공유자 이름 칩을 잠시 덮지만
 * 그쪽은 카드를 닫으면 되는 고정 라벨이다.
 */
export const COACH_TIP_SHARE_PLACEMENT = "absolute left-2.5 top-2 z-[9] w-[290px]";

/** PiP 창(기본 260px 폭). 고정 너비를 쓰면 잘려서 좌우를 채운다. */
export const COACH_TIP_PIP_PLACEMENT = "absolute inset-x-2 top-2 z-20";

/**
 * 강사에게 한 번에 하나씩 보여주는 수업 팁 카드(티켓 86).
 *
 * 문구는 서버가 §8 고정 템플릿으로 완성해 내려주므로 **그대로 표시하고 유형별로 화면을 나누지
 * 않는다.** `tipType` 은 검증·로깅용이고 `targetConcept` 은 이미 본문 문장에 녹아 있어 따로
 * 보여주지 않는다 — 무응답·자리비움 팁에는 아예 없어서 자리를 두면 유형마다 카드가 달라 보인다.
 *
 * 학생 이름·개별 응답·개별 판정은 담기지 않는다. 서버가 익명 집계만으로 만든 문구다.
 *
 * 기본 자리는 스테이지 우측 상단이라 강의 영상과 하단 제어를 가리지 않는다. 화면 공유·PiP 창에서는
 * 가려지거나 잘리므로 호출부가 배치를 갈아 끼운다(S15P11A105-299).
 */
export function CoachTipCard({
  tip,
  className = COACH_TIP_STAGE_PLACEMENT,
  onDismiss,
}: CoachTipCardProps) {
  return (
    <div
      role="status"
      data-testid="coach-tip-card"
      className={`${className} animate-[zPop_.2s] rounded-[18px] bg-surface p-[18px] text-ink shadow-[0_16px_44px_#0006]`}
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
