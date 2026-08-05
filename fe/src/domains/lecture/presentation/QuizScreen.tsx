"use client";

import { useState } from "react";
import Link from "next/link";
import { PictoLink, PictoSpark } from "@/shared/ui";
import { lectures, quizData } from "./fixtures";

/**
 * AI 이해도 퀴즈. 문항 풀이 → 정답/해설 → 결과 리뷰 흐름을 로컬 상태로 진행한다.
 *
 * <p>문항은 아직 fixture 다. 다만 **되돌아갈 주소는 fixture 에서 뽑지 않는다** — 실제 세션 id 는
 * fixture 목록에 없어 `lectures[0]` 로 떨어지고, 그러면 뒤로가기가 `/my-lectures/s1/report` 처럼
 * 존재하지 않는 수업을 가리켜 "리포트를 볼 수 없어요" 로 끝난다. 이동에는 URL 로 받은 `lectureId`
 * 를 쓴다.
 */
export function QuizScreen({ lectureId }: { lectureId: string }) {
  // 제목만 fixture 에서 읽는다. 링크에 쓰면 위 주석의 문제가 생긴다.
  const lecture = lectures.find((l) => l.id === lectureId) ?? lectures[0];
  const total = quizData.length;

  const [idx, setIdx] = useState(0);
  const [picked, setPicked] = useState<number | null>(null);
  const [submitted, setSubmitted] = useState(false);
  const [answers, setAnswers] = useState<number[]>([]);
  const [done, setDone] = useState(false);

  const q = quizData[idx];
  const correct = picked === q?.answer;
  const score = answers.filter((a, i) => a === quizData[i].answer).length;
  // 답을 낸 문항 중 틀린 것만 "다시 살펴볼 개념"으로 센다(프로토타입 reviewConcepts).
  const reviewCount = answers.filter((a, i) => a !== quizData[i].answer).length;
  const progressPct = done ? 100 : Math.round(((idx + (submitted ? 1 : 0)) / total) * 100);

  const submit = () => {
    if (picked === null) return;
    setSubmitted(true);
    setAnswers((prev) => [...prev.slice(0, idx), picked]);
  };
  const next = () => {
    if (idx + 1 >= total) return setDone(true);
    setIdx(idx + 1);
    setPicked(null);
    setSubmitted(false);
  };
  const restart = () => {
    setIdx(0);
    setPicked(null);
    setSubmitted(false);
    setAnswers([]);
    setDone(false);
  };

  return (
    <div className="min-h-screen bg-canvas px-6 py-7">
      <div className="mx-auto max-w-[680px]">
        <div className="mb-5 flex items-center gap-3.5">
          <Link
            href={`/my-lectures/${lectureId}/report?tab=report`}
            className="z-btn size-[38px] shrink-0 rounded-xl border border-line-muted bg-surface text-base text-ink"
          >
            ←
          </Link>
          <div className="flex-1">
            <div className="text-[15px] font-extrabold">{lecture.title} · AI 이해도 퀴즈</div>
            <div className="text-[12.5px] text-ink-faint">
              {done ? "결과 확인" : `${idx + 1} / ${total}`}
            </div>
          </div>
        </div>

        <div className="mb-6 h-2 overflow-hidden rounded-full bg-[#e9ecf7]">
          <div
            className="h-full rounded-full bg-primary transition-[width] duration-200"
            style={{ width: `${progressPct}%` }}
          />
        </div>

        {!done ? (
          <div className="z-card-lg px-[30px] py-7">
            <span className="z-badge mb-4 rounded-full bg-primary-soft px-3 py-[5px] text-[12.5px] text-primary">
              {q.concept}
            </span>
            <h1 className="mb-[22px] text-xl font-extrabold leading-[1.5] tracking-[-.3px]">
              {q.q}
            </h1>

            <div className="flex flex-col gap-[11px]">
              {q.opts.map((opt, i) => {
                const isPicked = picked === i;
                const isAnswer = i === q.answer;

                // 제출 후에는 정답/내 오답만 색으로 남기고 나머지 보기는 흐리게 죽인다.
                const optCls = !submitted
                  ? isPicked
                    ? "border-primary bg-primary-soft text-ink cursor-pointer"
                    : "border-line-muted bg-surface text-ink cursor-pointer"
                  : isAnswer
                    ? "border-[#98e1c5] bg-primary-mint text-primary-dark cursor-default"
                    : isPicked
                      ? "border-[#f4b8c1] bg-[#fff0f2] text-danger cursor-default"
                      : "border-line-mint bg-faint text-ink-fainter cursor-default";

                // 제출 전에는 마크가 비어 있고, 제출 후 정답/오답에만 채워진다.
                const markCls = !submitted
                  ? "bg-transparent"
                  : isAnswer
                    ? "bg-[#15bd7d]"
                    : isPicked
                      ? "bg-danger"
                      : "bg-transparent";

                return (
                  <button
                    type="button"
                    key={i}
                    onClick={() => !submitted && setPicked(i)}
                    className={`flex w-full items-center gap-[13px] rounded-[14px] border-[1.5px] px-[18px] py-4 text-left font-sans text-[14.5px] font-semibold ${optCls}`}
                  >
                    <span
                      aria-hidden="true"
                      className={`flex size-[22px] shrink-0 items-center justify-center rounded-full text-xs font-black text-white ${markCls}`}
                    >
                      {submitted && isAnswer ? "✓" : submitted && isPicked ? "✕" : ""}
                    </span>
                    <span className="flex-1">{opt}</span>
                  </button>
                );
              })}
            </div>

            {submitted && (
              <div className="mt-[22px] rounded-[14px] border border-line-mint bg-[#f6f7fd] px-5 py-[18px]">
                <div className="mb-2 flex items-center gap-2">
                  <span
                    className={`z-badge rounded-lg px-2.5 py-[3px] text-xs ${
                      correct ? "bg-primary-mint text-primary-dark" : "bg-[#fff0f2] text-danger"
                    }`}
                  >
                    {correct ? "정답" : "오답"}
                  </span>
                  <span className="text-[13.5px] font-extrabold text-ink-label">{q.concept}</span>
                </div>
                <p className="mb-3 text-[13.5px] leading-[1.65] text-ink-sub">{q.explain}</p>
                <button className="inline-flex cursor-pointer items-center gap-1.5 border-0 bg-transparent p-0 font-sans text-[13px] font-extrabold text-primary">
                  <PictoLink size={14} />
                  관련 강의 구간 {q.t} 다시 보기
                </button>
              </div>
            )}

            <div className="mt-[22px] flex justify-end">
              {submitted ? (
                <button
                  type="button"
                  onClick={next}
                  className="z-btn z-btn-primary rounded-[13px] px-7 py-[13px] text-[14.5px]"
                >
                  {idx + 1 >= total ? "결과 보기" : "다음 문제 →"}
                </button>
              ) : (
                <button
                  onClick={submit}
                  disabled={picked === null}
                  className={`z-btn z-btn-md text-[14.5px] text-white ${
                    picked === null ? "cursor-not-allowed bg-disabled" : "z-btn-primary"
                  }`}
                >
                  답안 제출
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
                {total}개 개념 중 {score}개를 확인했어요.
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
                {quizData.map((item, i) => {
                  const ok = answers[i] === item.answer;
                  return (
                    <div
                      key={i}
                      className="flex gap-[13px] border-b border-primary-softer pb-3.5"
                    >
                      <span
                        className={`flex size-6 shrink-0 items-center justify-center rounded-full text-[13px] font-black text-white ${
                          ok ? "bg-[#15bd7d]" : "bg-danger"
                        }`}
                      >
                        {ok ? "✓" : "✕"}
                      </span>
                      <div className="min-w-0 flex-1">
                        <div className="mb-0.5 text-[11.5px] font-bold text-ink-faint">
                          {item.concept}
                        </div>
                        <div className="mb-[5px] text-sm font-bold leading-[1.5]">{item.q}</div>
                        <div className="mb-1 text-[13px] leading-[1.6] text-ink-sub">
                          <b className="text-primary-dark">정답</b> · {item.opts[item.answer]}
                        </div>
                        <div className="mb-2 text-[13px] leading-[1.6] text-ink-faint">
                          {item.explain}
                        </div>
                        <button
                          type="button"
                          className="cursor-pointer border-0 bg-transparent p-0 font-sans text-[12.5px] font-extrabold text-primary"
                        >
                          ▶ 관련 복습 구간 다시 보기
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>

            <div className="flex gap-3">
              <button
                onClick={restart}
                className="z-btn z-btn-block flex-1 border border-line-primary bg-surface text-primary"
              >
                다시 풀기
              </button>
              <Link
                href={`/my-lectures/${lectureId}/report?tab=report`}
                className="z-btn z-btn-primary z-btn-block flex-1"
              >
                학습 리포트로 돌아가기
              </Link>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
