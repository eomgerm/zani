"use client";

import { useState } from "react";
import Link from "next/link";
import { PictoClockMuted, PictoLink, PictoLock, PictoSpark, PictoWarn } from "@/shared/ui";
import {
  useStudentQuiz,
  type QuizAnswersSubmitter,
  type QuizGrading,
  type QuizQuestion,
  type StudentQuizRequester,
} from "@/domains/report";
import { useSessionRole } from "./useSessionRole";

/**
 * AI 이해도 퀴즈.
 *
 * <p><b>문항을 하나씩 보고 마지막에 한 번 제출한다.</b> 서버 계약이 일괄 제출이라 그렇다 —
 * `POST .../quiz/answers` 는 모든 문항을 정확히 한 번씩 담아야 하고 제출 후에는 바꿀 수 없다.
 * 그래서 문항별 즉시 채점은 만들 수 없고, 정답·해설은 제출 뒤 결과에서 한꺼번에 보여준다.
 * 대신 제출 전에는 문항 사이를 오가며 답을 고쳐 쓸 수 있다.
 *
 * <p>이미 제출한 퀴즈로 다시 들어오면 조회 응답의 `submitted` 와 문항별 `grading` 으로 결과를
 * 바로 그린다. 다시 풀 수 있는 길은 두지 않는다 — 서버가 재제출을 받지 않는다.
 */
export function QuizScreen({
  lectureId,
  request,
  submitRequest,
}: {
  lectureId: string;
  /** 테스트에서 조회·제출을 갈아끼우기 위한 선택 인자. 기본값이 실제 어댑터다. */
  request?: StudentQuizRequester;
  submitRequest?: QuizAnswersSubmitter;
}) {
  // 제목은 세션 목록에서 온다. fixture 를 폴백으로 두면 실제 세션 id 를 못 찾아 남의 강의 제목이 걸린다.
  const { lecture } = useSessionRole(lectureId);
  const quiz = useStudentQuiz({ sessionId: lectureId, request, submitRequest });

  const [idx, setIdx] = useState(0);
  const [picked, setPicked] = useState<Readonly<Record<string, string>>>({});

  const questions = quiz.quiz?.questions ?? [];
  const total = questions.length;
  const question = questions[Math.min(idx, Math.max(0, total - 1))];
  const answeredCount = questions.filter((q) => picked[q.questionId] !== undefined).length;

  const showResult =
    quiz.quiz?.submitted === true ||
    quiz.submitStatus === "submitted" ||
    quiz.submitStatus === "alreadySubmitted";

  /** 방금 제출한 채점이 있으면 그것을, 없으면 조회에 실려 온 채점을 쓴다. */
  const gradingOf = (q: QuizQuestion): QuizGrading | null =>
    quiz.grading?.gradingByQuestionId[q.questionId] ?? q.grading;

  const correctCount =
    quiz.grading?.correctCount ?? questions.filter((q) => gradingOf(q)?.correct === true).length;
  const reviewCount = total - correctCount;
  const progressPct = showResult ? 100 : total === 0 ? 0 : Math.round((answeredCount / total) * 100);

  const header = (
    <div className="mb-5 flex items-center gap-3.5">
      <Link
        href={`/my-lectures/${lectureId}/report?tab=report`}
        className="z-btn size-[38px] shrink-0 rounded-xl border border-line-muted bg-surface text-base text-ink"
      >
        ←
      </Link>
      <div className="flex-1">
        <div className="text-[15px] font-extrabold">
          {lecture === null ? "AI 이해도 퀴즈" : `${lecture.title} · AI 이해도 퀴즈`}
        </div>
        <div className="text-[12.5px] text-ink-faint">
          {showResult ? "결과 확인" : total === 0 ? "" : `${idx + 1} / ${total}`}
        </div>
      </div>
    </div>
  );

  const shell = (body: React.ReactNode) => (
    <div className="min-h-screen bg-canvas px-6 py-7">
      <div className="mx-auto max-w-[680px]">
        {header}
        {body}
      </div>
    </div>
  );

  if (quiz.status !== "ready" || question === undefined) {
    return shell(
      <div className="z-card-lg px-[30px] py-[70px] text-center text-ink-fainter">
        <div className="mb-3.5 flex justify-center">
          {quiz.status === "forbidden" ? (
            <PictoLock size={44} />
          ) : quiz.status === "failed" ? (
            <PictoWarn size={44} />
          ) : (
            <PictoClockMuted size={44} />
          )}
        </div>
        <div className="mb-1 font-bold text-ink-muted">{NOTICE[noticeKeyOf(quiz.status)]}</div>
        <div className="text-[13.5px]">{NOTICE_DETAIL[noticeKeyOf(quiz.status)]}</div>
        {quiz.status === "failed" && (
          <button type="button" onClick={quiz.retry} className="z-btn z-btn-outline z-btn-md mt-4">
            다시 시도
          </button>
        )}
      </div>,
    );
  }

  return shell(
    <>
      <div className="mb-6 h-2 overflow-hidden rounded-full bg-[#e9ecf7]">
        <div
          className="h-full rounded-full bg-primary transition-[width] duration-200"
          style={{ width: `${progressPct}%` }}
        />
      </div>

      {!showResult ? (
        <div className="z-card-lg px-[30px] py-7">
          <span className="z-badge mb-4 rounded-full bg-primary-soft px-3 py-[5px] text-[12.5px] text-primary">
            문항 {idx + 1}
          </span>
          <h1 className="mb-[22px] text-xl font-extrabold leading-[1.5] tracking-[-.3px]">
            {question.text}
          </h1>

          <div className="flex flex-col gap-[11px]">
            {question.options.map((option) => {
              const isPicked = picked[question.questionId] === option.optionId;
              return (
                <button
                  type="button"
                  key={option.optionId}
                  aria-pressed={isPicked}
                  onClick={() =>
                    setPicked((prev) => ({ ...prev, [question.questionId]: option.optionId }))
                  }
                  className={`flex w-full cursor-pointer items-center gap-[13px] rounded-[14px] border-[1.5px] px-[18px] py-4 text-left font-sans text-[14.5px] font-semibold ${
                    isPicked
                      ? "border-primary bg-primary-soft text-ink"
                      : "border-line-muted bg-surface text-ink"
                  }`}
                >
                  <span
                    aria-hidden="true"
                    className={`flex size-[22px] shrink-0 items-center justify-center rounded-full border-[1.5px] ${
                      isPicked ? "border-primary bg-primary" : "border-line-muted bg-surface"
                    }`}
                  />
                  <span className="flex-1">{option.text}</span>
                </button>
              );
            })}
          </div>

          {quiz.submitStatus === "failed" && (
            <p className="mt-[18px] text-[13px] font-bold text-danger">
              답안을 제출하지 못했어요. 잠시 후 다시 시도해 주세요.
            </p>
          )}

          <div className="mt-[22px] flex items-center justify-between gap-3">
            <button
              type="button"
              onClick={() => setIdx((n) => Math.max(0, n - 1))}
              disabled={idx === 0}
              className={`z-btn z-btn-md text-[14.5px] ${
                idx === 0
                  ? "cursor-not-allowed border border-line-muted bg-surface text-ink-fainter"
                  : "border border-line-primary bg-surface text-primary"
              }`}
            >
              이전
            </button>

            {idx + 1 < total ? (
              <button
                type="button"
                onClick={() => setIdx((n) => Math.min(total - 1, n + 1))}
                className="z-btn z-btn-primary rounded-[13px] px-7 py-[13px] text-[14.5px]"
              >
                다음 문제 →
              </button>
            ) : (
              /* 모든 문항을 채워야 서버가 받는다. 덜 고른 상태로 눌러 400 을 받게 두지 않는다. */
              <button
                type="button"
                onClick={() =>
                  quiz.submit(
                    questions.map((q) => ({
                      questionId: q.questionId,
                      selectedOptionId: picked[q.questionId] as string,
                    })),
                  )
                }
                disabled={answeredCount < total || quiz.submitStatus === "submitting"}
                className={`z-btn z-btn-md text-[14.5px] text-white ${
                  answeredCount < total || quiz.submitStatus === "submitting"
                    ? "cursor-not-allowed bg-disabled"
                    : "z-btn-primary"
                }`}
              >
                {quiz.submitStatus === "submitting"
                  ? "제출 중…"
                  : answeredCount < total
                    ? `${total - answeredCount}문항 남았어요`
                    : "제출하기"}
              </button>
            )}
          </div>
        </div>
      ) : (
        <>
          <div className="z-card-lg mb-[18px] px-8 py-[30px] text-center">
            <div className="mb-2.5 flex justify-center">
              <PictoSpark size={40} />
            </div>
            <h1 className="mb-2 text-[22px] font-extrabold">
              {total}개 개념 중 {correctCount}개를 확인했어요.
            </h1>
            <p className="text-[14.5px] text-ink-muted">
              {reviewCount > 0
                ? `다시 살펴볼 개념이 ${reviewCount}개 있어요.`
                : "모든 개념을 잘 확인했어요"}
            </p>
          </div>

          <div className="z-card-lg mb-[18px] px-6 py-[22px]">
            <div className="mb-3.5 font-extrabold">문제별 정답과 해설</div>
            <div className="flex flex-col gap-3.5">
              {questions.map((q, i) => {
                const grading = gradingOf(q);
                const ok = grading?.correct === true;
                const correctText = q.options.find(
                  (option) => option.optionId === grading?.correctOptionId,
                )?.text;
                return (
                  <div key={q.questionId} className="flex gap-[13px] border-b border-primary-softer pb-3.5">
                    <span
                      className={`flex size-6 shrink-0 items-center justify-center rounded-full text-[13px] font-black text-white ${
                        ok ? "bg-[#15bd7d]" : "bg-danger"
                      }`}
                    >
                      {ok ? "✓" : "✕"}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="mb-0.5 text-[11.5px] font-bold text-ink-faint">
                        문항 {i + 1}
                      </div>
                      <div className="mb-[5px] text-sm font-bold leading-[1.5]">{q.text}</div>
                      {correctText !== undefined && (
                        <div className="mb-1 text-[13px] leading-[1.6] text-ink-sub">
                          <b className="text-primary-dark">정답</b> · {correctText}
                        </div>
                      )}
                      {grading !== null && grading.explanation.length > 0 && (
                        <div className="mb-2 text-[13px] leading-[1.6] text-ink-faint">
                          {grading.explanation}
                        </div>
                      )}
                      {/*
                        갈 시각을 아는 문항만 링크를 낸다. 근거 구간(sectionStartedOffsetMs)은 249 가
                        채우기 시작하면 실려 오고, 그전에는 버튼이 나오지 않는다 — 갈 곳을 모르는
                        버튼을 두면 눌러도 아무 일이 없거나 엉뚱한 자리로 간다.
                      */}
                      {grading?.sectionStartSeconds !== null &&
                        grading?.sectionStartSeconds !== undefined && (
                          <Link
                            href={`/my-lectures/${lectureId}/report?tab=clip&seek=${grading.sectionStartSeconds}`}
                            className="inline-flex items-center gap-1.5 text-[12.5px] font-extrabold text-primary no-underline"
                          >
                            <PictoLink size={14} />
                            관련 강의 구간 다시 보기
                          </Link>
                        )}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* 다시 풀기는 없다 — 서버가 재제출을 받지 않는다(이미 제출된 퀴즈는 409). */}
          <Link
            href={`/my-lectures/${lectureId}/report?tab=report`}
            className="z-btn z-btn-primary z-btn-block"
          >
            학습 리포트로 돌아가기
          </Link>
        </>
      )}
    </>,
  );
}

/**
 * `ready` 인데 여기까지 온 것은 문항이 하나도 없다는 뜻이다 — 조회는 끝났으므로 "불러오는 중" 은
 * 영영 지나가지 않는 거짓말이 된다. 리포트의 퀴즈 카드와 같은 문구로 준비 전임을 알린다.
 */
const noticeKeyOf = (status: string): keyof typeof NOTICE =>
  status === "forbidden" || status === "failed" || status === "notReady" || status === "ready"
    ? (status as keyof typeof NOTICE)
    : "loading";

const NOTICE = {
  loading: "퀴즈를 불러오는 중이에요",
  notReady: "아직 퀴즈가 준비되지 않았어요",
  ready: "아직 퀴즈가 준비되지 않았어요",
  forbidden: "이 수업의 퀴즈를 볼 수 없어요",
  failed: "퀴즈를 불러오지 못했어요",
} as const;

const NOTICE_DETAIL = {
  loading: "잠시만 기다려 주세요.",
  notReady: "수업 분석이 끝나면 퀴즈를 풀 수 있어요.",
  ready: "수업 분석이 끝나면 퀴즈를 풀 수 있어요.",
  forbidden: "내가 참여한 수업이 맞는지 확인해 주세요.",
  failed: "잠시 후 다시 시도해 주세요.",
} as const;
