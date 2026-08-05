"use client";

import Link from "next/link";
import { Badge, PICTOGRAMS, PictoPen } from "@/shared/ui";
import {
  focusedIntervalRatio,
  formatOffset,
  StudentAttentionTimelineView,
  useStudentAttentionTimeline,
  useStudentQuiz,
  useStudentReport,
  type StudentQuizRequester,
  type StudentReportRequester,
  type StudentTimelineRequester,
} from "@/domains/report";

interface Props {
  lectureId: string;
  /** 리포트를 조회할 실제 세션 id. */
  sessionId: string;
  /** 구간 상세·복습 추천에서 클립 탭으로 옮긴다. */
  onJumpToClip: (offsetSeconds: number) => void;
  /** 테스트에서 조회를 갈아끼우기 위한 선택 인자. 기본값이 실제 어댑터다. */
  reportRequest?: StudentReportRequester;
  attentionRequest?: StudentTimelineRequester;
  quizRequest?: StudentQuizRequester;
}

/**
 * 리포트 탭 2 (학생) — 한눈에 보기 · 참여도 요약 · 집중 흐름 · 타임라인 · 복습 추천 + 퀴즈.
 *
 * <p>제목은 박스 밖에 두고 내용만 박스에 담는다. 카드를 다시 카드로 감싸면 같은 정보에 테두리가
 * 두 겹 생겨 정보 밀도만 떨어진다(디자인 문서 §6).
 *
 * <p>세 응답을 따로 조회한다 — 학습 리포트(112) · 집중 흐름(110) · 퀴즈(249). 한쪽이 없어도 다른
 * 쪽은 보여준다. 하나로 묶으면 퀴즈가 아직 없는 수업에서 참여 요약까지 함께 사라진다.
 *
 * <p>집중 흐름 응답은 여기서 한 번만 조회해 "집중 구간 비율" 타일과 아래 차트·타임라인이 함께
 * 쓴다. 카드마다 스스로 조회하면 같은 URL 을 두 번 읽는다.
 */
export function StudentReport({
  lectureId,
  sessionId,
  onJumpToClip,
  reportRequest,
  attentionRequest,
  quizRequest,
}: Props) {
  const report = useStudentReport({ sessionId, request: reportRequest });
  const attention = useStudentAttentionTimeline(sessionId, attentionRequest);
  const quiz = useStudentQuiz({ sessionId, request: quizRequest });
  // 카드는 "몇 문제, 몇 분"만 쓴다. 문항이 하나도 없으면 풀 것이 없으므로 준비 전과 같이 다룬다.
  const quizQuestionCount = quiz.quiz?.questions.length ?? 0;

  const activity = report.report?.activity;
  const ratio = focusedIntervalRatio(attention.timeline?.focusFlow.points ?? []);

  const glance = [
    { icon: "target", label: "집중 구간 비율", value: ratioText(attention.status, ratio) },
    // 채팅 행 수가 아니라 AI 가 질문으로 판단한 발화 수다. 판정이 없으면 "0개" 가 아니라 빈 자리다.
    { icon: "chat", label: "질문 수", value: countText(activity?.questionCount, "개") },
    { icon: "question", label: "헷갈림 표시", value: countText(activity?.confusedCount) },
    { icon: "pin", label: "놓침 표시", value: countText(activity?.missedCount) },
  ] as const;

  const recommendations = report.report?.recommendations ?? [];
  const summary = report.report?.participationSummary ?? "";

  return (
    <>
      <div className="z-report-head">
        <div className="z-section-title">한눈에 보기</div>
      </div>
      <div className="grid grid-cols-4 gap-3">
        {glance.map((g) => {
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
        {report.status !== "ready" ? (
          <ReportNotice status={report.status} onRetry={report.retry} />
        ) : summary.length > 0 ? (
          <p className="text-[13px] leading-[1.75] text-ink-sub">{summary}</p>
        ) : (
          <p className="text-[13px] text-ink-faint">참여도 요약이 아직 없어요.</p>
        )}
      </div>

      <StudentAttentionTimelineView {...attention} onJumpToClip={onJumpToClip} />

      {/*
        복습 추천과 퀴즈는 둘 다 "이제 무엇을 할까"라 나란히 둔다. 제목과 설명은 다른 블록처럼
        박스 밖에 두고, 추천은 퀴즈 카드 높이만큼만 자리를 쓰고 그 안에서 스크롤한다.
      */}
      <div className="mt-[26px] grid grid-cols-2 items-stretch gap-5">
        <div className="flex min-w-0 flex-col">
          <div className="z-report-head">
            <div className="z-section-title">나의 복습 추천</div>
            <div className="z-report-sub">복습이 필요한 구간을 확인하고 다시 학습해 보세요.</div>
          </div>
          <div className="z-report-box flex max-h-[360px] min-h-0 flex-1 flex-col px-5 py-[18px]">
            {report.status !== "ready" ? (
              <ReportNotice status={report.status} onRetry={report.retry} />
            ) : recommendations.length === 0 ? (
              /* 추천 없음은 오류가 아니다(REPORT-S-005). 근거가 없어 만들지 않은 것이다. */
              <p className="text-[13px] text-ink-faint">
                복습 추천이 없어요. 다시 볼 구간으로 꼽을 근거가 남지 않았어요.
              </p>
            ) : (
              <ul className="flex min-h-0 flex-1 list-none flex-col gap-3 overflow-y-auto p-0">
                {recommendations.map((r) => {
                  const badge = RECOMMENDATION_BADGE[r.recommendationType] ?? UNKNOWN_RECOMMENDATION_BADGE;
                  return (
                  <li key={`${r.startSeconds}-${r.title}`}>
                    <button
                      type="button"
                      onClick={() => onJumpToClip(r.startSeconds)}
                      className="flex w-full cursor-pointer gap-3.5 rounded-[13px] border border-line-mint p-3 text-left hover:border-line-primary hover:bg-[#f6faf8]"
                    >
                      <span className="flex h-[50px] w-[74px] shrink-0 items-center justify-center rounded-[9px] bg-[#20233a]">
                        <span className="flex size-[26px] items-center justify-center rounded-full bg-white/80 text-[11px] text-primary">
                          ▶
                        </span>
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="mb-1 flex flex-wrap items-center gap-2">
                          <span className="font-mono text-[11.5px] font-extrabold text-primary">
                            {formatOffset(r.startSeconds)}
                          </span>
                          <Badge bg={badge.bg} fg={badge.fg}>
                            {badge.label}
                          </Badge>
                          <span className="text-[13.5px] font-extrabold">{r.title}</span>
                        </span>
                        <span className="block text-xs leading-[1.5] text-ink-faint">
                          {r.description}
                        </span>
                      </span>
                    </button>
                  </li>
                  );
                })}
              </ul>
            )}
          </div>
        </div>

        <div className="flex min-w-0 flex-col">
          <div className="z-report-head">
            <div className="z-section-title">AI 이해도 퀴즈</div>
            <div className="z-report-sub">
              강의 내용과 어려워했던 구간을 바탕으로 AI가 맞춤 퀴즈를 만들었어요.
            </div>
          </div>
          {/*
            띠를 반 폭에 맞춰 세로로 세운 것이다. 초록을 카드 전체에 쓰면 이 블록이 "다음에 할
            것"으로 먼저 읽힌다 — 옆 칸의 추천 목록과 역할이 갈린다.
          */}
          <div className="relative flex flex-1 flex-col items-center justify-center gap-5 overflow-hidden rounded-2xl bg-primary px-6 py-7">
            <span
              aria-hidden="true"
              className="pointer-events-none absolute -left-[18%] -top-[46%] w-[62%] rounded-full bg-white/[.13] pb-[62%]"
            />
            <span
              aria-hidden="true"
              className="pointer-events-none absolute -bottom-[54%] -right-[10%] w-[68%] rounded-full bg-white/10 pb-[68%]"
            />
            <span className="relative z-10 flex size-[86px] items-center justify-center rounded-full bg-surface/90">
              <PictoPen size={40} />
            </span>
            <div className="relative z-10 w-full">
              {quizQuestionCount === 0 ? (
                /* 풀 퀴즈가 없는데 버튼을 두면 눌러서 빈 화면을 만난다. 안내만 남긴다. */
                <div className="rounded-[11px] bg-white/20 px-3 py-3.5 text-center text-[13px] font-extrabold text-white">
                  {QUIZ_NOTICE[quiz.status]}
                  {quiz.status === "failed" && (
                    <button
                      type="button"
                      onClick={quiz.retry}
                      className="ml-2.5 cursor-pointer border-0 bg-transparent font-sans text-[13px] font-extrabold text-white underline"
                    >
                      다시 시도
                    </button>
                  )}
                </div>
              ) : (
                <>
                  <Link
                    href={`/my-lectures/${lectureId}/quiz`}
                    className="z-btn w-full rounded-[13px] bg-surface py-3.5 text-[15px] text-[#0e7f5b]"
                  >
                    퀴즈 풀어보기
                  </Link>
                  <div className="mt-2.5 rounded-[11px] bg-white/20 py-2.5 text-center text-[13px] font-extrabold text-white">
                    {quizMeta(quizQuestionCount, quiz.quiz?.estimatedDurationMinutes ?? null)}
                  </div>
                </>
              )}
            </div>
          </div>
        </div>
      </div>
    </>
  );
}

/**
 * 249 가 확정한 다섯 가지 근거와 그 배지 색.
 *
 * <p>색은 집중 흐름 구간과 같은 값을 쓴다(`sectionFlow` 의 단계 색과 연한 배경) — 한 화면에서
 * 노랑이 한쪽은 "보통 단계", 다른 쪽은 아무 뜻도 아니면 색을 읽을 수 없게 된다.
 *
 * <p>그래서 색은 심각도 순위가 아니라 **그 근거가 집중 흐름에서 어떤 자리였는지**를 따른다. 질문은
 * 스스로 참여한 신호라 초록이고, 무응답은 값이 없던 시간이라 회색이다. 색만으로 구분하지 않도록
 * 문구를 항상 함께 둔다(FRD §19.2).
 *
 * <p>모르는 값도 버리지 않는다. 서버가 유형을 늘렸을 뿐일 수 있어 중립 문구와 회색으로 그린다.
 */
const RECOMMENDATION_BADGE: Readonly<Record<string, { label: string; bg: string; fg: string }>> = {
  CONFUSED: { label: "헷갈림", bg: "#fdf6df", fg: "#8a6a10" },
  MISSED: { label: "놓침", bg: "#fdefe8", fg: "#a1541c" },
  NO_RESPONSE: { label: "무응답", bg: "#f4f5fa", fg: "#5f658a" },
  LOW_ENGAGEMENT: { label: "집중 저하", bg: "#fdeeee", fg: "#b3243a" },
  QUESTION: { label: "내 질문", bg: "#eaf7f2", fg: "#16865e" },
};

const UNKNOWN_RECOMMENDATION_BADGE = { label: "복습 추천", bg: "#f4f5fa", fg: "#5f658a" };

/**
 * 상태마다 할 말이 다르다. 넷을 "불러오지 못했어요" 하나로 뭉치면 수업이 진행 중인 학생에게
 * 오류를 보여주고, 권한이 없는 사람에게 다시 시도를 권한다.
 */
const REPORT_NOTICE = {
  loading: "학습 리포트를 불러오는 중이에요",
  notReady: "아직 학습 리포트가 준비되지 않았어요",
  forbidden: "이 수업의 학습 리포트를 볼 수 없어요",
  live: "수업이 끝나면 학습 리포트를 볼 수 있어요",
  failed: "학습 리포트를 불러오지 못했어요",
} as const;

const QUIZ_NOTICE = {
  loading: "퀴즈를 불러오는 중이에요",
  notReady: "아직 퀴즈가 준비되지 않았어요",
  forbidden: "이 수업의 퀴즈를 볼 수 없어요",
  failed: "퀴즈를 불러오지 못했어요",
  // ready 인데 문항이 없으면 풀 것이 없다. 빈 카드보다 "준비 전"이 사실에 가깝다.
  ready: "아직 퀴즈가 준비되지 않았어요",
} as const;

function ReportNotice({
  status,
  onRetry,
}: {
  status: keyof typeof REPORT_NOTICE;
  onRetry: () => void;
}) {
  return (
    <div className="text-[13px] text-ink-faint">
      {REPORT_NOTICE[status]}
      {status === "failed" && (
        <button
          type="button"
          onClick={onRetry}
          className="ml-2.5 cursor-pointer border-0 bg-transparent font-sans text-[13px] font-extrabold text-primary"
        >
          다시 시도
        </button>
      )}
    </div>
  );
}

/**
 * 값이 오지 않았으면 0 이 아니라 빈 자리다 — "0회" 는 안 했다는 뜻이라 거짓이 된다. 질문 수는
 * 리포트가 있어도 판정이 없을 수 있어(`null`) 같은 규칙을 쓴다.
 */
const countText = (count: number | null | undefined, unit = "회"): string =>
  count === undefined || count === null ? "—" : `${count}${unit}`;

/**
 * 비율 타일 문구. 측정 가능한 칸이 없으면 "0%" 가 아니라 측정 불가다(REPORT-S-007) — 카메라를
 * 끈 시간을 집중하지 않은 것으로 셀 수 없다. 조회가 끝나지 않았으면 숫자 자리에 상태를 적는다.
 */
const ratioText = (
  status: "loading" | "ready" | "forbidden" | "live" | "failed",
  ratio: number | null,
): string => {
  if (status === "loading") return "계산 중";
  if (status === "live") return "수업 중";
  if (status === "forbidden") return "접근 불가";
  if (status === "failed") return "불러오기 실패";
  return ratio === null ? "측정 불가" : `${ratio}%`;
};

/** 예상 시간을 서버가 주지 않으면 문항 수만 적는다. "약 0분" 은 거짓이다. */
const quizMeta = (questionCount: number, minutes: number | null): string =>
  minutes === null ? `총 ${questionCount}문제` : `총 ${questionCount}문제 · 약 ${minutes}분`;
