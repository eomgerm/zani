import { EvalDonuts, PICTOGRAMS } from "@/shared/ui";
import { GroupAttentionTimeline } from "@/domains/report";
import {
  evalDonutData,
  improveTips,
  insights,
  instructorGlance,
  instructorSummary,
} from "../../fixtures";

interface Props {
  /** 참여도 타임라인을 조회할 실제 세션 id. 나머지 카드는 아직 fixture 다(110 범위). */
  sessionId: string;
}

/**
 * 리포트 탭 2 (강사) — 한눈에 보기 · 집중 흐름 · 타임라인 · 인사이트 · AI 수업 피드백.
 *
 * <p>제목은 박스 밖에 두고 내용만 박스에 담는다(디자인 문서 §6). 집중 흐름과 타임라인은 한
 * 응답에서 나오므로 report 도메인 컴포넌트가 두 블록을 함께 그린다.
 */
export function InstructorReport({ sessionId }: Props) {
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

      <GroupAttentionTimeline sessionId={sessionId} />

      <div className="z-report-head">
        <div className="z-section-title">인사이트</div>
      </div>
      <div className="grid grid-cols-4 gap-3.5">
        {insights.map((it, i) => {
          const Icon = PICTOGRAMS[it.icon];
          return (
            <div key={i} className="z-report-box p-4">
              <div
                className="mb-[11px] flex size-[34px] items-center justify-center rounded-[10px]"
                style={{ background: it.bg }}
              >
                <Icon size={18} />
              </div>
              <div className="text-[12.5px] font-semibold leading-[1.55] text-ink-label">
                {it.text}
              </div>
            </div>
          );
        })}
      </div>

      <div className="z-report-head">
        <div className="z-section-title">AI 수업 피드백</div>
        <div className="z-report-sub">AI가 수업 전체를 읽고 정리한 내용이에요.</div>
      </div>
      <div className="z-report-box mb-[22px] px-5 py-4">
        <div className="mb-2 text-[13.5px] font-extrabold">종합 포인트</div>
        <p className="text-[13px] leading-[1.75] text-ink-sub">{instructorSummary}</p>
      </div>

      <div className="grid grid-cols-2 items-start gap-[26px]">
        <div>
          <div className="mb-3.5 flex items-center gap-2">
            <span className="text-sm font-extrabold">분야별 평가</span>
            <span className="text-[11.5px] text-ink-fainter">AI가 4개 항목으로 평가했어요.</span>
          </div>
          <div className="z-report-box px-4 py-[18px]">
            <EvalDonuts data={evalDonutData} />
          </div>
        </div>

        <div>
          <div className="mb-3.5 text-sm font-extrabold">수업 개선 TIP</div>
          <div className="flex flex-col gap-3">
            {improveTips.map((t) => {
              const Icon = PICTOGRAMS[t.icon];
              return (
                <div key={t.title} className="z-report-box px-4 py-3.5">
                  <div className="mb-1.5 flex items-center gap-[7px] text-[12.5px] font-extrabold">
                    <Icon size={15} />
                    {t.title}
                  </div>
                  <p className="mb-1.5 text-[11.5px] leading-[1.5] text-ink-faint">{t.obs}</p>
                  <div className="text-[11px] leading-[1.5] text-ink-sub">{t.tip}</div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </>
  );
}
