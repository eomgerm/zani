import Link from "next/link";
import { Badge, PICTOGRAMS } from "@/shared/ui";
import { StudentAttentionTimeline } from "@/domains/report";
import { recommendations, studentGlance, studentSummary } from "../../fixtures";

interface Props {
  lectureId: string;
  /** 집중 흐름을 조회할 실제 세션 id. 나머지 카드는 아직 fixture 다(110 범위). */
  sessionId: string;
}

/**
 * 리포트 탭 2 (학생) — 한눈에 보기 · 참여도 요약 · 집중 흐름 · 타임라인 · 복습 추천 + 퀴즈.
 *
 * <p>제목은 박스 밖에 두고 내용만 박스에 담는다. 카드를 다시 카드로 감싸면 같은 정보에 테두리가
 * 두 겹 생겨 정보 밀도만 떨어진다(디자인 문서 §6).
 *
 * <p>집중 흐름과 타임라인은 한 응답에서 나오므로 report 도메인 컴포넌트가 두 블록을 함께 그린다.
 */
export function StudentReport({ lectureId, sessionId }: Props) {
  return (
    <>
      <div className="z-report-head">
        <div className="z-section-title">한눈에 보기</div>
      </div>
      <div className="grid grid-cols-4 gap-3">
        {studentGlance.map((g) => {
          const Icon = PICTOGRAMS[g.icon];
          return (
            <div key={g.label} className="z-report-box px-[18px] py-[18px]">
              <div className="mb-[11px] flex items-center gap-2 text-[13px] font-medium text-ink-faint">
                <Icon size={18} />
                {g.label}
              </div>
              <div className="text-[26px] font-extrabold tracking-[-.5px]">{g.value}</div>
            </div>
          );
        })}
      </div>

      <div className="z-report-head">
        <div className="z-section-title">수업 참여도 요약</div>
        <div className="z-report-sub">AI가 분석한 전반적인 수업 참여도예요.</div>
      </div>
      <div className="z-report-box px-[18px] py-4">
        <p className="text-[13px] leading-[1.75] text-ink-sub">{studentSummary}</p>
      </div>

      <StudentAttentionTimeline sessionId={sessionId} />

      <div className="z-report-head">
        <div className="z-section-title">나의 복습 추천</div>
        <div className="z-report-sub">복습이 필요한 구간을 확인하고 다시 학습해 보세요.</div>
      </div>
      <div className="flex flex-col gap-3">
        {recommendations.map((r) => (
          <div
            key={r.t}
            className="z-report-box flex cursor-pointer gap-3.5 px-3.5 py-[13px] hover:border-line-primary hover:bg-[#fbfdfc]"
          >
            <div className="flex h-[50px] w-[74px] shrink-0 items-center justify-center rounded-[9px] bg-[#20233a]">
              <span className="flex size-[26px] items-center justify-center rounded-full bg-white/80 text-[11px] text-primary">
                ▶
              </span>
            </div>
            <div className="min-w-0 flex-1">
              <div className="mb-[5px] text-sm font-extrabold text-primary">{r.t}</div>
              <div className="mb-[5px] flex flex-wrap items-center gap-[7px]">
                <span className="text-sm font-extrabold">{r.title}</span>
                <Badge bg={`${r.color}22`} fg={r.color}>
                  {r.tag}
                </Badge>
              </div>
              <div className="text-xs leading-[1.5] text-ink-faint">{r.reason}</div>
            </div>
          </div>
        ))}
      </div>

      {/* AI 퀴즈는 카드가 아니라 화면 폭을 쓰는 띠다 — 다음 할 일이라 눈에 걸려야 한다. */}
      <div className="relative mt-[26px] flex flex-wrap items-center gap-5 overflow-hidden rounded-[18px] bg-primary px-7 py-8">
        <span
          aria-hidden="true"
          className="pointer-events-none absolute -left-[14%] -top-[58%] w-[52%] rounded-full bg-white/[.13] pb-[52%]"
        />
        <span
          aria-hidden="true"
          className="pointer-events-none absolute -bottom-[72%] -right-[6%] w-[60%] rounded-full bg-white/10 pb-[60%]"
        />
        <div className="relative z-10 min-w-[240px] flex-1">
          <h3 className="mb-1.5 text-lg font-extrabold tracking-[-.6px] text-white">
            AI 이해도 퀴즈
          </h3>
          <p className="text-[13px] font-bold leading-[1.55] text-[#e6f7ef]">
            수업 중 어려웠던 구간을 바탕으로 AI가 맞춤 퀴즈를 만들었어요.
          </p>
        </div>
        <div className="relative z-10 flex shrink-0 flex-wrap items-center gap-2.5">
          <Link
            href={`/my-lectures/${lectureId}/quiz`}
            className="z-btn rounded-[11px] bg-surface px-[26px] py-[11px] text-sm text-[#0e7f5b]"
          >
            퀴즈 풀어보기
          </Link>
          <span className="rounded-[11px] bg-white/20 px-5 py-[11px] text-[13px] font-extrabold text-white">
            총 5문제 · 약 3분
          </span>
        </div>
      </div>
    </>
  );
}
