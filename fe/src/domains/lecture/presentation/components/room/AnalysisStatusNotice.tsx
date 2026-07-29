"use client";

import type { AnalysisAvailability } from "@/domains/attention";

/**
 * 학생에게 보여줄 분석 가용 상태 배지(티켓 76).
 *
 * 보여주는 것은 **분석이 도는지 아닌지**뿐이다. 점수·등급·확률은 물론 개별 판정 상태도 담지
 * 않는다. 카메라가 꺼져서 멈춘 경우 이유를 여기서 설명하지 않는 것은 중복 때문이다 —
 * 카메라 안내 프롬프트(81)가 원인별로 안내한다.
 *
 * 검출기를 못 쓰는 경우만 여기서 사유를 밝힌다. 그 상황은 프롬프트가 다루지 않고, 카메라
 * 문제가 아니므로 카메라를 켜라고 안내해서도 안 된다.
 *
 * 상단 바 안에 흐름대로 놓이는 칩이다. 띄워 얹으면 수업 화면의 다른 조작을 가린다.
 */
const COPY: Record<AnalysisAvailability, { label: string; tone: string } | null> = {
  // 정상 동작은 알릴 것이 없다. 배지를 띄우면 수업 화면만 시끄러워진다.
  ACTIVE: null,
  PAUSED: {
    label: "학습 분석 일시 중지",
    tone: "border-[#262b42] bg-[#151830] text-panel-muted",
  },
  UNAVAILABLE: {
    label: "학습 분석을 사용할 수 없어요",
    tone: "border-[#f3dc90] bg-warn-soft text-[#836607]",
  },
};

export interface AnalysisStatusNoticeProps {
  readonly availability: AnalysisAvailability;
}

export function AnalysisStatusNotice({ availability }: AnalysisStatusNoticeProps) {
  const copy = COPY[availability];
  if (copy === null) {
    return null;
  }

  return (
    <div
      role="status"
      data-testid="analysis-status-notice"
      className={`whitespace-nowrap rounded-[11px] border px-3 py-[9px] font-sans text-[12px] font-bold ${copy.tone}`}
    >
      {copy.label}
    </div>
  );
}
