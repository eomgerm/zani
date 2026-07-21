"use client";

import { useState } from "react";
import Link from "next/link";
import { color } from "@/shared/lib/theme";
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

  const submit = () => {
    if (picked === null) return;
    setSubmitted(true);
    setAnswers((prev) => [...prev.slice(0, idx), picked]);
  };
  const next = () => {
    if (idx + 1 >= total) {
      setDone(true);
      return;
    }
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

  const progressPct = done ? 100 : Math.round(((idx + (submitted ? 1 : 0)) / total) * 100);

  return (
    <div style={{ minHeight: "100vh", background: color.bg, padding: "28px 24px" }}>
      <div style={{ maxWidth: 680, margin: "0 auto" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 14, marginBottom: 20 }}>
          <Link href={`/my-lectures/${lecture.id}/report`} style={backBtn}>
            ←
          </Link>
          <div style={{ flex: 1 }}>
            <div style={{ fontWeight: 800, fontSize: 15 }}>{lecture.title} · AI 이해도 퀴즈</div>
            <div style={{ color: color.textFaint, fontSize: 12.5 }}>
              {done ? "결과 확인" : `${idx + 1} / ${total} 문항`}
            </div>
          </div>
        </div>

        <div style={{ height: 8, background: "#e9ecf7", borderRadius: 999, overflow: "hidden", marginBottom: 24 }}>
          <div style={{ height: "100%", width: `${progressPct}%`, background: color.primary, borderRadius: 999, transition: "width .2s" }} />
        </div>

        {!done ? (
          <div style={cardBox}>
            <span style={{ display: "inline-block", background: color.primarySoft, color: color.primary, padding: "5px 12px", borderRadius: 999, fontSize: 12.5, fontWeight: 800, marginBottom: 16 }}>
              {q.concept}
            </span>
            <h1 style={{ fontSize: 20, fontWeight: 800, margin: "0 0 22px", lineHeight: 1.5, letterSpacing: "-.3px" }}>{q.q}</h1>

            <div style={{ display: "flex", flexDirection: "column", gap: 11 }}>
              {q.opts.map((opt, i) => {
                const isPicked = picked === i;
                const isAnswer = i === q.answer;
                const showState = submitted && (isAnswer || isPicked);
                const stateColor = submitted ? (isAnswer ? color.primary : isPicked ? color.red : color.borderMuted) : isPicked ? color.primary : color.borderMuted;
                return (
                  <button
                    key={i}
                    onClick={() => !submitted && setPicked(i)}
                    style={{
                      display: "flex",
                      alignItems: "center",
                      gap: 12,
                      padding: "14px 16px",
                      borderRadius: 13,
                      border: `1.5px solid ${stateColor}`,
                      background: showState ? (isAnswer ? color.primarySoft : "#fdeeee") : "#fff",
                      cursor: submitted ? "default" : "pointer",
                      fontFamily: "inherit",
                      fontSize: 14.5,
                      textAlign: "left",
                      color: color.text,
                    }}
                  >
                    <span
                      style={{
                        width: 24,
                        height: 24,
                        borderRadius: "50%",
                        flexShrink: 0,
                        display: "flex",
                        alignItems: "center",
                        justifyContent: "center",
                        fontSize: 12,
                        fontWeight: 800,
                        border: `1.5px solid ${stateColor}`,
                        background: isPicked || (submitted && isAnswer) ? stateColor : "#fff",
                        color: isPicked || (submitted && isAnswer) ? "#fff" : color.textFaint,
                      }}
                    >
                      {submitted && isAnswer ? "✓" : submitted && isPicked ? "✕" : i + 1}
                    </span>
                    <span style={{ flex: 1 }}>{opt}</span>
                  </button>
                );
              })}
            </div>

            {submitted && (
              <div style={{ marginTop: 22, padding: "18px 20px", borderRadius: 14, background: color.surfaceFaint, border: `1px solid ${color.borderMint}` }}>
                <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 8 }}>
                  <span
                    style={{
                      fontSize: 11.5,
                      fontWeight: 800,
                      padding: "3px 10px",
                      borderRadius: 999,
                      background: correct ? color.primarySoft : color.redSoft,
                      color: correct ? color.primaryDeep : color.red,
                    }}
                  >
                    {correct ? "정답" : "오답"}
                  </span>
                  <span style={{ fontWeight: 800, fontSize: 13.5, color: color.textLabel }}>{q.concept}</span>
                </div>
                <p style={{ margin: "0 0 12px", color: color.textSub, fontSize: 13.5, lineHeight: 1.65 }}>{q.explain}</p>
                <button style={{ border: "none", background: "none", color: color.primary, fontWeight: 800, cursor: "pointer", fontSize: 13, padding: 0, fontFamily: "inherit" }}>
                  🔗 관련 강의 구간 {q.t} 다시 보기
                </button>
              </div>
            )}

            <div style={{ display: "flex", justifyContent: "flex-end", marginTop: 22 }}>
              {submitted ? (
                <button onClick={next} style={primaryBtn}>
                  {idx + 1 >= total ? "결과 보기" : "다음 문제"}
                </button>
              ) : (
                <button
                  onClick={submit}
                  disabled={picked === null}
                  style={{ ...primaryBtn, background: picked === null ? "#c7cbe6" : color.primary, cursor: picked === null ? "not-allowed" : "pointer" }}
                >
                  답안 제출
                </button>
              )}
            </div>
          </div>
        ) : (
          <>
            <div style={{ ...cardBox, textAlign: "center", marginBottom: 18 }}>
              <div style={{ fontSize: 40, marginBottom: 10 }}>🎉</div>
              <h1 style={{ fontSize: 22, fontWeight: 800, margin: "0 0 8px" }}>
                {total}문제 중 {score}문제를 맞혔어요
              </h1>
              <p style={{ color: color.textMuted, margin: 0, fontSize: 14.5 }}>
                틀린 문항의 관련 구간을 다시 보면 이해도가 올라가요.
              </p>
            </div>

            <div style={{ ...cardBox, marginBottom: 18 }}>
              <div style={{ fontWeight: 800, marginBottom: 14 }}>문제별 정답과 해설</div>
              <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
                {quizData.map((item, i) => {
                  const ok = answers[i] === item.answer;
                  return (
                    <div key={i} style={{ display: "flex", gap: 13, paddingBottom: 14, borderBottom: `1px solid ${color.primarySofter}` }}>
                      <span
                        style={{
                          width: 26,
                          height: 26,
                          borderRadius: "50%",
                          flexShrink: 0,
                          display: "flex",
                          alignItems: "center",
                          justifyContent: "center",
                          fontSize: 13,
                          fontWeight: 800,
                          background: ok ? color.primarySoft : color.redSoft,
                          color: ok ? color.primaryDeep : color.red,
                        }}
                      >
                        {ok ? "✓" : "✕"}
                      </span>
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ fontSize: 11.5, color: color.textFaint, fontWeight: 700, marginBottom: 2 }}>{item.concept}</div>
                        <div style={{ fontWeight: 700, fontSize: 14, marginBottom: 5, lineHeight: 1.5 }}>{item.q}</div>
                        <div style={{ fontSize: 13, color: color.textSub, lineHeight: 1.6, marginBottom: 4 }}>
                          <b style={{ color: color.primaryDark }}>정답</b> · {item.opts[item.answer]}
                        </div>
                        <div style={{ fontSize: 13, color: color.textFaint, lineHeight: 1.6 }}>{item.explain}</div>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>

            <div style={{ display: "flex", gap: 12 }}>
              <button onClick={restart} style={{ flex: 1, padding: 15, borderRadius: 14, border: "1px solid #c6eedf", background: "#fff", color: color.primary, fontWeight: 800, fontSize: 15, cursor: "pointer", fontFamily: "inherit" }}>
                다시 풀기
              </button>
              <Link href={`/my-lectures/${lecture.id}/report`} style={{ flex: 1, padding: 15, borderRadius: 14, border: "none", background: color.primary, color: "#fff", fontWeight: 800, fontSize: 15, textAlign: "center", textDecoration: "none" }}>
                학습 리포트로 돌아가기
              </Link>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

const backBtn = {
  width: 38,
  height: 38,
  borderRadius: 12,
  border: `1px solid ${color.borderMuted}`,
  background: "#fff",
  cursor: "pointer",
  fontSize: 16,
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  textDecoration: "none",
  color: color.text,
  flexShrink: 0,
} as const;

const cardBox = {
  background: "#fff",
  border: `1px solid ${color.border}`,
  borderRadius: 20,
  padding: "28px 30px",
  boxShadow: "0 4px 22px rgba(24,74,62,.05)",
} as const;

const primaryBtn = {
  padding: "13px 28px",
  borderRadius: 13,
  border: "none",
  background: color.primary,
  color: "#fff",
  fontWeight: 800,
  cursor: "pointer",
  fontSize: 14.5,
  fontFamily: "inherit",
} as const;
