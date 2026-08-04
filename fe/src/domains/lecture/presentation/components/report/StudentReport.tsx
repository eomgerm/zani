import Link from "next/link";
import {
  Badge,
  Card,
  PICTOGRAMS,
  PictoClock,
  PictoDoc,
  PictoPen,
  PictoStar,
} from "@/shared/ui";
import { StudentAttentionTimeline } from "@/domains/report";
import { recommendations, studentGlance, studentSummary } from "../../fixtures";

interface Props {
  lectureId: string;
  /** 집중 흐름을 조회할 실제 세션 id. 나머지 카드는 아직 fixture 다(110 범위). */
  sessionId: string;
  /** 구간 상세에서 클립 탭으로 옮긴다. */
  onJumpToClip: (offsetSeconds: number) => void;
}

/**
 * 리포트 탭 2 (학생) — 한눈에 보기 · 참여도 요약 · 집중 흐름 · 타임라인 · 복습 추천 + 퀴즈.
 *
 * <p>제목은 박스 밖에 두고 내용만 박스에 담는다. 카드를 다시 카드로 감싸면 같은 정보에 테두리가
 * 두 겹 생겨 정보 밀도만 떨어진다(디자인 문서 §6).
 *
 * <p>집중 흐름과 타임라인은 한 응답에서 나오므로 report 도메인 컴포넌트가 두 블록을 함께 그린다.
 */
export function StudentReport({ lectureId, sessionId, onJumpToClip }: Props) {
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

      <StudentAttentionTimeline sessionId={sessionId} onJumpToClip={onJumpToClip} />

      {/*
        복습 추천과 퀴즈는 둘 다 "이제 무엇을 할까"라 나란히 둔다. 추천은 개수가 늘 수 있어
        퀴즈 카드 높이만큼만 자리를 쓰고 그 안에서 스크롤한다.
      */}
      <div className="mt-[26px] grid grid-cols-2 items-start gap-5">
        <Card className="flex flex-col px-6 py-[22px]">
          <div className="mb-4 flex flex-wrap items-center gap-2">
            <div className="z-section-title">나의 복습 추천</div>
            <span className="text-[11.5px] text-ink-fainter">
              자기보고 · 질문 · 반복된 확인 필요가 결합된 구간만 골라요 (최대 5개)
            </span>
          </div>
          <ul className="flex max-h-[400px] list-none flex-col gap-3 overflow-y-auto p-0">
            {recommendations.map((r) => (
              <li key={r.t}>
                <button
                  type="button"
                  onClick={() => onJumpToClip(offsetSecondsOf(r.t))}
                  className="flex w-full cursor-pointer gap-3.5 rounded-[13px] border border-line-mint p-3 text-left hover:border-line-primary hover:bg-faint"
                >
                  <span className="flex h-[50px] w-[74px] shrink-0 items-center justify-center rounded-[9px] bg-[#20233a]">
                    <span className="flex size-[26px] items-center justify-center rounded-full bg-white/80 text-[11px] text-primary">
                      ▶
                    </span>
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="mb-1 flex flex-wrap items-center gap-2">
                      <span className="font-mono text-[11.5px] font-extrabold text-primary">
                        {r.t}
                      </span>
                      <Badge bg={`${r.color}22`} fg={r.color}>
                        {r.tag}
                      </Badge>
                      <span className="text-[13.5px] font-extrabold">{r.title}</span>
                    </span>
                    <span className="block text-xs leading-[1.5] text-ink-faint">{r.reason}</span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </Card>

        <Card className="px-6 py-[22px]">
          <div className="mb-4 flex items-center gap-2">
            <div className="z-section-title">AI 이해도 퀴즈</div>
            <span className="text-[11.5px] text-ink-fainter">
              강의 내용과 어려워했던 구간을 바탕으로 AI가 맞춤 퀴즈를 만들었어요.
            </span>
          </div>
          <div className="mb-[18px] flex items-center gap-[18px]">
            <div className="flex h-[120px] flex-1 items-center justify-center rounded-[14px] bg-[linear-gradient(135deg,#e7f7f1,#f2fbf8)]">
              <PictoPen size={44} />
            </div>
            <div className="flex flex-1 flex-col gap-2.5">
              {[
                { icon: <PictoDoc size={16} />, text: "총 5문제" },
                { icon: <PictoClock size={16} />, text: "약 3분" },
                { icon: <PictoStar size={16} />, text: "주요 개념 3개" },
              ].map((t) => (
                <div
                  key={t.text}
                  className="flex items-center gap-[9px] rounded-[11px] bg-canvas px-3.5 py-3 text-[13px] font-bold text-ink-label"
                >
                  {t.icon}
                  {t.text}
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
    </>
  );
}

/** `24:10` · `1:12:05` 같은 fixture 시각을 초로 읽는다. 110 이 실데이터를 주면 사라진다. */
function offsetSecondsOf(label: string): number {
  const parts = label.split(":").map(Number);
  if (parts.some(Number.isNaN)) return 0;
  return parts.length === 3
    ? parts[0] * 3600 + parts[1] * 60 + parts[2]
    : parts[0] * 60 + (parts[1] ?? 0);
}
