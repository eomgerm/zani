import { Card, EvalDonuts, PICTOGRAMS, StatCard } from "@/shared/ui";
import { GroupAttentionTimeline } from "@/domains/report";
import {
  evalDonutData,
  improveTips,
  insights,
  instructorGlance,
  instructorSummary,
  learnSegments,
} from "../../fixtures";
import { TimelineSegments } from "./TimelineSegments";

interface Props {
  /** 참여도 타임라인을 조회할 실제 세션 id. 나머지 카드는 아직 fixture 다(110 범위). */
  sessionId: string;
  activeSeg: number;
  onSelect: (i: number) => void;
}

/** 리포트 탭 2 (강사) — 한눈에 보기 · 집중 흐름 · 타임라인 · 인사이트 · AI 수업 피드백. */
export function InstructorReport({ sessionId, activeSeg, onSelect }: Props) {
  return (
    <>
      <Card className="mb-5 px-6 py-[22px]">
        <div className="mb-[18px] text-[17px] font-extrabold">한눈에 보기</div>
        <div className="grid grid-cols-5 gap-3.5">
          {instructorGlance.map((g) => {
            const Icon = PICTOGRAMS[g.icon];
            return (
              <StatCard
                key={g.label}
                label={
                  <>
                    <Icon size={18} />
                    {g.label}
                  </>
                }
                value={g.value}
                suffix={
                  g.badge ? (
                    <span className="rounded-md bg-warn-soft px-[7px] py-0.5 text-[10.5px] font-extrabold text-warn-text">
                      {g.badge}
                    </span>
                  ) : undefined
                }
              />
            );
          })}
        </div>
      </Card>

      <div className="mb-5">
        <GroupAttentionTimeline sessionId={sessionId} />
      </div>

      <Card className="mb-5 px-6 py-[22px]">
        <div className="mb-1 flex flex-wrap items-center gap-2">
          <div className="z-section-title">타임라인</div>
          <span className="text-[11.5px] text-ink-fainter">
            구간을 누르면 집중도 평가와 설명, 복습 클립 바로가기가 열려요.
          </span>
        </div>
        <div className="mb-3 mt-1.5 text-xs font-bold text-ink-faint">
          수업 내용 기반 구간 · 전체 집중도 점수 (0–4)
        </div>
        <TimelineSegments
          segments={learnSegments}
          role="instructor"
          activeSeg={activeSeg}
          onSelect={onSelect}
        />
      </Card>

      <Card className="mb-5 px-6 py-[22px]">
        <div className="z-section-title mb-[18px]">인사이트</div>
        <div className="grid grid-cols-4 gap-3.5">
          {insights.map((it, i) => (
            <div key={i} className="z-box bg-faint p-4">
              <div
                className="mb-[11px] flex size-[34px] items-center justify-center rounded-[10px]"
                style={{ background: it.bg }}
              >
                {(() => {
                  const Icon = PICTOGRAMS[it.icon];
                  return <Icon size={18} />;
                })()}
              </div>
              <div className="text-[12.5px] font-semibold leading-[1.55] text-ink-label">
                {it.text}
              </div>
            </div>
          ))}
        </div>
      </Card>

      <Card className="mb-5 px-6 py-[22px]">
        <div className="mb-3.5 text-base font-extrabold">AI 수업 피드백</div>
        <div className="mb-[22px] rounded-xl bg-canvas px-5 py-4">
          <div className="mb-2 text-[13.5px] font-extrabold">종합 포인트</div>
          <p className="text-[13px] leading-[1.75] text-ink-sub">{instructorSummary}</p>
        </div>

        <div className="grid grid-cols-2 items-start gap-[26px]">
          <div>
            <div className="mb-3.5 flex items-center gap-2">
              <span className="text-sm font-extrabold">분야별 평가</span>
              <span className="text-[11.5px] text-ink-fainter">AI가 4개 항목으로 평가했어요.</span>
            </div>
            <div className="rounded-[14px] border border-[#f1f2f8] bg-faint px-4 py-[18px]">
              <EvalDonuts data={evalDonutData} />
            </div>
          </div>

          <div>
            <div className="mb-3.5 text-sm font-extrabold">수업 개선 TIP</div>
            <div className="flex flex-col gap-3">
              {improveTips.map((t) => (
                <div key={t.title} className="rounded-[13px] border border-line-light px-4 py-3.5">
                  <div className="mb-1.5 flex items-center gap-[7px] text-[12.5px] font-extrabold">
                    {(() => {
                      const Icon = PICTOGRAMS[t.icon];
                      return <Icon size={15} />;
                    })()}
                    {t.title}
                  </div>
                  <p className="mb-1.5 text-[11.5px] leading-[1.5] text-ink-faint">{t.obs}</p>
                  <div className="text-[11px] leading-[1.5] text-ink-sub">{t.tip}</div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </Card>
    </>
  );
}
