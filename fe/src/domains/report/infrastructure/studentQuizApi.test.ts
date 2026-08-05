import { afterEach, describe, expect, it, vi } from "vitest";

import { requestStudentQuiz, StudentQuizError, submitQuizAnswers } from "./studentQuizApi";

/** 응답을 글자로 준다 — 어댑터가 본문을 text 로 읽고 긴 정수를 감싼 뒤 파싱하기 때문이다. */
const respondWith = (body: unknown, status = 200) => {
  const raw = typeof body === "string" ? body : JSON.stringify(body);
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue({
      ok: status >= 200 && status < 300,
      status,
      text: async () => raw,
    }),
  );
};

const envelope = (data: unknown) => ({ isSuccess: true, code: "COMMON200", message: "ok", data });

const quizWith = (overrides: Record<string, unknown> = {}) => ({
  quizId: 1,
  title: "재귀 함수 복습 퀴즈",
  description: "핵심 개념 확인",
  estimatedDurationMinutes: 3,
  submitted: false,
  questions: [
    {
      questionId: 11,
      order: 1,
      text: "재귀의 종료 조건은?",
      options: [
        { optionId: 101, order: 1, text: "없어도 된다" },
        { optionId: 102, order: 2, text: "반드시 필요하다" },
      ],
    },
  ],
  ...overrides,
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("requestStudentQuiz", () => {
  it("문항과 보기를 읽고 퀴즈 경로에 Bearer 토큰을 보낸다", async () => {
    respondWith(envelope(quizWith()));

    const quiz = await requestStudentQuiz("session/1", "token");

    expect(quiz.title).toBe("재귀 함수 복습 퀴즈");
    expect(quiz.estimatedDurationMinutes).toBe(3);
    expect(quiz.submitted).toBe(false);
    expect(quiz.questions).toHaveLength(1);
    expect(quiz.questions[0].options.map((o) => o.text)).toEqual(["없어도 된다", "반드시 필요하다"]);
    const [url, init] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(String(url)).toContain("/api/v1/sessions/session%2F1/quiz");
    expect(init).toMatchObject({ headers: { Authorization: "Bearer token" } });
  });

  it("TSID 를 문자열로 지켜 낸다 — 숫자로 파싱하면 끝자리가 뭉개진다", async () => {
    // 본문을 글자로 준다. JS 숫자 리터럴로 쓰면 이 테스트 파일에서 이미 끝자리가 뭉개져
    // 어댑터가 무엇을 받았는지 확인할 수 없다 — 2^53 을 넘는 값은 소스에 적는 순간 손상된다.
    respondWith(`{"isSuccess":true,"data":{
      "quizId":742891573920571390,"title":"퀴즈","description":"",
      "estimatedDurationMinutes":3,"submitted":false,
      "questions":[{"questionId":742891573920571392,"order":1,"text":"문항",
        "options":[{"optionId":742891573920571397,"order":1,"text":"보기"}]}]}}`);

    const quiz = await requestStudentQuiz("s1", "token");

    expect(quiz.questions[0].questionId).toBe("742891573920571392");
    expect(quiz.questions[0].options[0].optionId).toBe("742891573920571397");
  });

  it("안전 범위 안의 짧은 id 는 그대로 문자열로 읽는다", async () => {
    respondWith(envelope(quizWith()));

    const quiz = await requestStudentQuiz("s1", "token");

    expect(quiz.questions[0].questionId).toBe("11");
    expect(quiz.questions[0].options[0].optionId).toBe("101");
  });

  it("본문 문장 안의 긴 숫자는 건드리지 않는다", async () => {
    respondWith(
      envelope(
        quizWith({
          questions: [
            {
              questionId: 11,
              order: 1,
              text: "코드 1234567890123456789 를 보세요",
              options: [{ optionId: 101, order: 1, text: "보기" }],
            },
          ],
        }),
      ),
    );

    const quiz = await requestStudentQuiz("s1", "token");

    expect(quiz.questions[0].text).toBe("코드 1234567890123456789 를 보세요");
  });

  it("제출 전에는 채점이 없고, 제출 후에는 문항에 실려 온다", async () => {
    respondWith(envelope(quizWith()));
    await expect(requestStudentQuiz("s1", "token")).resolves.toMatchObject({
      questions: [{ grading: null }],
    });

    respondWith(
      envelope(
        quizWith({
          submitted: true,
          questions: [
            {
              questionId: 11,
              order: 1,
              text: "문항",
              options: [{ optionId: 101, order: 1, text: "보기" }],
              grading: {
                correct: true,
                selectedOptionId: 101,
                correctOptionId: 101,
                explanation: "해설",
                sectionStartedOffsetMs: 1450000,
              },
            },
          ],
        }),
      ),
    );

    const submittedQuiz = await requestStudentQuiz("s1", "token");

    expect(submittedQuiz.submitted).toBe(true);
    expect(submittedQuiz.questions[0].grading).toEqual({
      correct: true,
      selectedOptionId: "101",
      correctOptionId: "101",
      explanation: "해설",
      // ms 를 초로 내린다.
      sectionStartSeconds: 1450,
    });
  });

  it("구간 offset 이 없으면 null 이다 — 249 전에는 오지 않는다", async () => {
    respondWith(
      envelope(
        quizWith({
          questions: [
            {
              questionId: 11,
              order: 1,
              text: "문항",
              options: [{ optionId: 101, order: 1, text: "보기" }],
              grading: { correct: false, selectedOptionId: 101, correctOptionId: 102, explanation: "" },
            },
          ],
        }),
      ),
    );

    const quiz = await requestStudentQuiz("s1", "token");

    expect(quiz.questions[0].grading?.sectionStartSeconds).toBeNull();
  });

  it("출제 순서가 뒤섞여 와도 순서대로 세운다", async () => {
    respondWith(
      envelope(
        quizWith({
          questions: [
            { questionId: 12, order: 2, text: "둘째", options: [{ optionId: 1, order: 1, text: "a" }] },
            { questionId: 11, order: 1, text: "첫째", options: [{ optionId: 2, order: 1, text: "b" }] },
          ],
        }),
      ),
    );

    const quiz = await requestStudentQuiz("s1", "token");

    expect(quiz.questions.map((q) => q.text)).toEqual(["첫째", "둘째"]);
  });

  it("고를 보기가 없는 문항은 버린다 — 답을 낼 수 없어 제출을 막는다", async () => {
    respondWith(
      envelope(
        quizWith({
          questions: [
            { questionId: 11, order: 1, text: "정상", options: [{ optionId: 1, order: 1, text: "a" }] },
            { questionId: 12, order: 2, text: "보기 없음", options: [] },
          ],
        }),
      ),
    );

    const quiz = await requestStudentQuiz("s1", "token");

    expect(quiz.questions).toHaveLength(1);
  });

  it("예상 시간이 없으면 null 이다 — '약 0분' 을 쓰지 않는다", async () => {
    respondWith(envelope(quizWith({ estimatedDurationMinutes: undefined })));

    await expect(requestStudentQuiz("s1", "token")).resolves.toMatchObject({
      estimatedDurationMinutes: null,
    });
  });

  it.each([403, 404])("HTTP %s 를 상태가 보존된 오류로 올린다", async (status) => {
    respondWith({ isSuccess: false }, status);

    await expect(requestStudentQuiz("s1", "token")).rejects.toMatchObject({
      name: "StudentQuizError",
      status,
    });
  });

  it("전송 실패는 상태 0, 깨진 봉투는 계약 오류다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
    await expect(requestStudentQuiz("s1", "token")).rejects.toMatchObject({ status: 0 });

    respondWith("not json");
    await expect(requestStudentQuiz("s1", "token")).rejects.toBeInstanceOf(StudentQuizError);

    respondWith({ isSuccess: true, data: null });
    await expect(requestStudentQuiz("s1", "token")).rejects.toBeInstanceOf(StudentQuizError);
  });
});

describe("submitQuizAnswers", () => {
  it("답안을 일괄로 보내고 문항별 채점을 맵으로 접어 준다", async () => {
    // 채점 결과의 questionId 도 TSID 다. 글자로 줘서 끝자리를 지킨다.
    respondWith(`{"isSuccess":true,"data":{"quizId":1,"totalCount":2,"correctCount":1,"results":[
      {"questionId":742891573920571392,"correct":true,"selectedOptionId":101,
       "correctOptionId":101,"explanation":"맞았어요"},
      {"questionId":12,"correct":false,"selectedOptionId":201,
       "correctOptionId":202,"explanation":"틀렸어요"}]}}`);

    const grading = await submitQuizAnswers("s1", "token", [
      { questionId: "742891573920571392", selectedOptionId: "101" },
      { questionId: "12", selectedOptionId: "201" },
    ]);

    expect(grading.totalCount).toBe(2);
    expect(grading.correctCount).toBe(1);
    expect(grading.gradingByQuestionId["742891573920571392"].correct).toBe(true);
    expect(grading.gradingByQuestionId["12"].explanation).toBe("틀렸어요");

    const [url, init] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(String(url)).toContain("/quiz/answers");
    expect((init as RequestInit).method).toBe("POST");
    // 제출 본문의 id 도 문자열이라 끝자리가 살아 있다.
    expect(String((init as RequestInit).body)).toContain('"742891573920571392"');
  });

  it("이미 제출된 퀴즈는 409 로 올린다 — 훅이 결과 조회로 돌린다", async () => {
    respondWith({ isSuccess: false, code: "QUIZ_003" }, 409);

    await expect(submitQuizAnswers("s1", "token", [{ questionId: "1", selectedOptionId: "2" }]))
      .rejects.toMatchObject({ status: 409 });
  });

  it("답안이 계약과 다르면 400 이다", async () => {
    respondWith({ isSuccess: false, code: "QUIZ_004" }, 400);

    await expect(submitQuizAnswers("s1", "token", [{ questionId: "1", selectedOptionId: "2" }]))
      .rejects.toMatchObject({ status: 400 });
  });
});
