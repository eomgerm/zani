import { EvalDonuts, PICTOGRAMS } from "@/shared/ui";
import { GroupAttentionTimeline } from "@/domains/report";
import { evalDonutData, improveTips, instructorGlance, instructorSummary } from "../../fixtures";

interface Props {
  /** 참여도 타임라인을 조회할 실제 세션 id. 나머지 카드는 아직 fixture 다(110 범위). */
  sessionId: string;
  /** 구간 상세에서 클립 탭으로 옮긴다. */
  onJumpToClip: (offsetSeconds: number) => void;
}

/**
 * 리포트 탭 2 (강사) — 한눈에 보기 · 집중 흐름 · 타임라인 · AI 수업 피드백.
 *
 * <p>제목은 박스 밖에 두고 내용만 박스에 담는다(디자인 문서 §6). 집중 흐름과 타임라인은 한
 * 응답에서 나오므로 report 도메인 컴포넌트가 두 블록을 함께 그린다.
 */
export function InstructorReport({ sessionId, onJumpToClip }: Props) {
  return (
    <>
      <div className="z-report-head">
        <div className="z-section-title">한눈에 보기</div>
      </div>
      <div className="grid grid-cols-5 gap-3.5">
        {instructorGlance.map((g) => {
          const Icon = PICTOGRAMS[g.icon];
          return (
            <div key={g.label} className="z-report-box px-5 py-[18px]">
              <div className="mb-3 flex items-center gap-2 text-[13px] font-medium text-ink-faint">
                <Icon size={18} />
                {g.label}
              </div>
              <div className="flex items-baseline gap-[7px]">
                <span className="text-[26px] font-extrabold tracking-[-.5px]">{g.value}</span>
                {g.badge !== undefined && (
                  <span className="rounded-md bg-warn-soft px-[7px] py-0.5 text-xs font-extrabold text-warn-text">
                    {g.badge}
                  </span>
                )}
              </div>
            </div>
          );
        })}
      </div>

      <GroupAttentionTimeline sessionId={sessionId} onJumpToClip={onJumpToClip} />

      <div className="z-report-head">
        <div className="z-section-title">AI 수업 피드백</div>
      </div>
      <div className="z-report-box mb-[22px] px-5 py-4">
        <div className="mb-2 text-[13.5px] font-extrabold">종합 포인트</div>
        <p className="text-[13px] leading-[1.75] text-ink-sub">{instructorSummary}</p>
      </div>

      {/* 두 블록은 같은 평가의 두 면이라 높이를 맞춰 나란히 둔다. */}
      <div className="grid grid-cols-2 items-stretch gap-[26px]">
        <div className="flex flex-col">
          <div className="mb-3.5 text-sm font-extrabold">분야별 평가</div>
          <div className="z-report-box flex flex-1 items-center px-4 py-[18px]">
            <EvalDonuts data={evalDonutData} />
          </div>
        </div>

        <div className="flex flex-col">
          <div className="mb-3.5 text-sm font-extrabold">수업 인사이트</div>
          <div className="flex flex-1 flex-col gap-3">
            {improveTips.map((t) => (
              <div key={t.title} className="z-report-box flex-1 px-4 py-3.5">
                <div className="mb-1.5 flex items-start gap-1.5 text-[12.5px] font-extrabold">
                  <CheckMark />
                  <span>{t.title}</span>
                </div>
                <p className="mb-2 text-[11.5px] leading-[1.5] text-ink-faint">{t.obs}</p>
                {/* 해 볼 것은 관찰과 달리 행동이라 이름을 붙여 초록으로 짚어 준다. */}
                <div className="text-[11.5px] font-bold leading-[1.5] text-primary-dark">
                  <span className="mr-1 font-extrabold">TIP.</span>
                  {t.tip}
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </>
  );
}

/** 인사이트 제목 앞의 체크. 카탈로그에 체크가 없어 같은 굵기로 그려 둔다. */
function CheckMark() {
  return (
    <svg
      width="13"
      height="13"
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
      className="mt-[3px] shrink-0 text-primary"
    >
      <path
        d="M5 12.8l4.4 4.2L19 7.4"
        stroke="currentColor"
        strokeWidth="2.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
