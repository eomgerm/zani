"use client";

import { useState } from "react";
import Link from "next/link";
import { lectures, quizData } from "./fixtures";

/**
 * AI 이해도 퀴즈. 문항 풀이 → 정답/해설 → 결과 리뷰 흐름을 로컬 상태로 진행한다.
 */
export function QuizScreen({ lectureId }: { lectureId: string }) {
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
            href={`/my-lectures/${lecture.id}/report`}
            className="z-btn size-[38px] shrink-0 rounded-xl border border-line-muted bg-surface text-base text-ink"
          >
            ←
          </Link>
          <div className="flex-1">
            <div className="text-[15px] font-extrabold">{lecture.title} · AI 이해도 퀴즈</div>
            <div className="text-[12.5px] text-ink-faint">
              {done ? "결과 확인" : `${idx + 1} / ${total} 문항`}
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
                const showState = submitted && (isAnswer || isPicked);
                const borderCls = submitted
                  ? isAnswer
                    ? "border-primary"
                    : isPicked
                      ? "border-danger"
                      : "border-line-muted"
                  : isPicked
                    ? "border-primary"
                    : "border-line-muted";
                const bgCls = showState
                  ? isAnswer
                    ? "bg-primary-soft"
                    : "bg-danger-softer"
                  : "bg-surface";
                const markFilled = isPicked || (submitted && isAnswer);
                const markBg = submitted
                  ? isAnswer
                    ? "bg-primary text-white"
                    : isPicked
                      ? "bg-danger text-white"
                      : "bg-surface text-ink-faint"
                  : isPicked
                    ? "bg-primary text-white"
                    : "bg-surface text-ink-faint";

                return (
                  <button
                    key={i}
                    onClick={() => !submitted && setPicked(i)}
                    className={`flex items-center gap-3 rounded-[13px] border-[1.5px] px-4 py-3.5 text-left font-sans text-[14.5px] text-ink ${borderCls} ${bgCls} ${
                      submitted ? "cursor-default" : "cursor-pointer"
                    }`}
                  >
                    <span
                      className={`flex size-6 shrink-0 items-center justify-center rounded-full border-[1.5px] text-xs font-extrabold ${borderCls} ${markBg} ${
                        markFilled ? "border-transparent" : ""
                      }`}
                    >
                      {submitted && isAnswer ? "✓" : submitted && isPicked ? "✕" : i + 1}
                    </span>
                    <span className="flex-1">{opt}</span>
                  </button>
                );
              })}
            </div>

            {submitted && (
              <div className="mt-[22px] rounded-[14px] border border-line-mint bg-faint px-5 py-[18px]">
                <div className="mb-2 flex items-center gap-2">
                  <span
                    className={`rounded-full px-2.5 py-[3px] text-[11.5px] font-extrabold ${
                      correct
                        ? "bg-primary-soft text-primary-deep"
                        : "bg-danger-soft text-danger"
                    }`}
                  >
                    {correct ? "정답" : "오답"}
                  </span>
                  <span className="text-[13.5px] font-extrabold text-ink-label">{q.concept}</span>
                </div>
                <p className="mb-3 text-[13.5px] leading-[1.65] text-ink-sub">{q.explain}</p>
                <button className="cursor-pointer border-0 bg-transparent p-0 font-sans text-[13px] font-extrabold text-primary">
                  🔗 관련 강의 구간 {q.t} 다시 보기
                </button>
              </div>
            )}

            <div className="mt-[22px] flex justify-end">
              {submitted ? (
                <button onClick={next} className="z-btn z-btn-primary z-btn-md text-[14.5px]">
                  {idx + 1 >= total ? "결과 보기" : "다음 문제"}
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
              <div className="mb-2.5 text-[40px]">🎉</div>
              <h1 className="mb-2 text-[22px] font-extrabold">
                {total}문제 중 {score}문제를 맞혔어요
              </h1>
              <p className="text-[14.5px] text-ink-muted">
                틀린 문항의 관련 구간을 다시 보면 이해도가 올라가요.
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
                        className={`flex size-[26px] shrink-0 items-center justify-center rounded-full text-[13px] font-extrabold ${
                          ok ? "bg-primary-soft text-primary-deep" : "bg-danger-soft text-danger"
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
                        <div className="text-[13px] leading-[1.6] text-ink-faint">
                          {item.explain}
                        </div>
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
                href={`/my-lectures/${lecture.id}/report`}
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
