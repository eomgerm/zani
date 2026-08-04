import Link from "next/link";
import { Badge, BookmarkIcon, Card } from "@/shared/ui";
import { StudentAttentionTimeline } from "@/domains/report";
import { learnSegments, recommendations, studentGlance, studentSummary } from "../../fixtures";
import { TimelineSegments } from "./TimelineSegments";

interface Props {
  lectureId: string;
  /** 집중 흐름을 조회할 실제 세션 id. 나머지 카드는 아직 fixture 다(110 범위). */
  sessionId: string;
  activeSeg: number;
  onSelect: (i: number) => void;
}

/** 리포트 탭 2 (학생) — 한눈에 보기 · 참여도 요약 · 집중 흐름 · 타임라인 · 복습 추천 + 퀴즈. */
export function StudentReport({ lectureId, sessionId, activeSeg, onSelect }: Props) {
  return (
    <div className="flex flex-col gap-5">
      <Card className="px-6 py-[22px]">
        <div className="z-section-title mb-4">
          <span className="text-primary">📊</span>한눈에 보기
        </div>
        <div className="grid grid-cols-4 gap-3">
          {studentGlance.map((g) => (
            <div key={g.label} className="z-box p-4">
              <div className="mb-[9px] text-xs text-ink-faint">{g.label}</div>
              <div className="text-2xl font-black tracking-[-.5px]">{g.value}</div>
            </div>
          ))}
        </div>
      </Card>

      <Card className="px-6 py-[22px]">
        <div className="mb-3.5 flex items-center gap-2">
          <div className="z-section-title">
            <span className="text-primary">✨</span>수업 참여도 요약
          </div>
          <span className="text-[11.5px] text-ink-fainter">
            AI가 분석한 전반적인 수업 참여도예요.
          </span>
        </div>
        <div className="rounded-xl bg-canvas px-[18px] py-4">
          <p className="text-[13px] leading-[1.75] text-ink-sub">{studentSummary}</p>
        </div>
      </Card>

      <StudentAttentionTimeline sessionId={sessionId} />

      <Card className="px-6 py-[22px]">
        <div className="mb-1 flex flex-wrap items-center gap-2">
          <div className="z-section-title">
            <span className="text-primary">🎬</span>타임라인
          </div>
          <span className="text-[11.5px] text-ink-fainter">
            구간을 누르면 집중도 평가와 설명, 복습 클립 바로가기가 열려요.
          </span>
        </div>
        <div className="mb-3 mt-1.5 text-xs font-bold text-ink-faint">
          수업 내용 기반 구간 · 내 집중도 점수 (0–4)
        </div>
        <TimelineSegments
          segments={learnSegments}
          role="student"
          activeSeg={activeSeg}
          onSelect={onSelect}
        />
      </Card>

      <div className="grid grid-cols-2 items-start gap-5">
        <Card className="px-6 py-[22px]">
          <div className="mb-4 flex flex-wrap items-center gap-2">
            <div className="z-section-title">
              <span className="text-danger">🎯</span>나의 복습 추천
            </div>
            <span className="text-[11.5px] text-ink-fainter">
              자기보고 · 질문 · 반복된 확인 필요가 결합된 구간만 골라요 (최대 5개)
            </span>
          </div>
          <div className="flex flex-col gap-3">
            {recommendations.map((r) => (
              <div
                key={r.t}
                className="flex cursor-pointer gap-3.5 rounded-[13px] border border-line-mint p-3 hover:border-line-primary hover:bg-faint"
              >
                <div className="flex h-[50px] w-[74px] shrink-0 items-center justify-center rounded-[9px] bg-[#20233a]">
                  <span className="flex size-[26px] items-center justify-center rounded-full bg-white/80 text-[11px] text-primary">
                    ▶
                  </span>
                </div>
                <div className="min-w-0 flex-1">
                  <div className="mb-1 flex flex-wrap items-center gap-2">
                    <span className="font-mono text-[11.5px] font-extrabold text-primary">
                      {r.t}
                    </span>
                    <Badge bg={`${r.color}22`} fg={r.color}>
                      {r.tag}
                    </Badge>
                    <span className="text-[13.5px] font-extrabold">{r.title}</span>
                  </div>
                  <div className="text-xs leading-[1.5] text-ink-faint">{r.reason}</div>
                </div>
                <BookmarkIcon className="shrink-0 text-[#c2c7dc]" />
              </div>
            ))}
          </div>
        </Card>

        <Card className="px-6 py-[22px]">
          <div className="mb-4 flex items-center gap-2">
            <div className="z-section-title">
              <span className="text-primary">📋</span>AI 이해도 퀴즈
            </div>
            <span className="text-[11.5px] text-ink-fainter">
              강의 내용과 어려워했던 구간을 바탕으로 AI가 맞춤 퀴즈를 만들었어요.
            </span>
          </div>
          <div className="mb-[18px] flex items-center gap-[18px]">
            <div className="flex h-[120px] flex-1 items-center justify-center rounded-[14px] bg-[linear-gradient(135deg,#e7f7f1,#f2fbf8)]">
              <span className="text-[44px]">📝</span>
            </div>
            <div className="flex flex-1 flex-col gap-2.5">
              {["📄 총 5문제", "🕐 약 3분", "⭐ 주요 개념 3개"].map((t) => (
                <div
                  key={t}
                  className="flex items-center gap-[9px] rounded-[11px] bg-canvas px-3.5 py-3 text-[13px] font-bold text-ink-label"
                >
                  {t}
                </div>
              ))}
            </div>
          </div>
          <Link
            href={`/my-lectures/${lectureId}/quiz`}
            className="z-btn z-btn-primary z-btn-block"
          >
            퀴즈 풀어보기 ›
          </Link>
        </Card>
      </div>
    </div>
  );
}
