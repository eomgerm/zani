import { afterEach, describe, expect, it, vi } from "vitest";

import { QuizSummaryError, requestQuizSummary } from "./quizSummaryApi";

const respondWith = (body: unknown, status = 200) => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue({
      ok: status >= 200 && status < 300,
      status,
      json: async () => body,
    }),
  );
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("requestQuizSummary", () => {
  it("문항 수와 예상 시간만 읽고 퀴즈 경로에 Bearer 토큰을 보낸다", async () => {
    respondWith({
      isSuccess: true,
      data: {
        quizId: 742891573920571390,
        title: "재귀 함수 복습 퀴즈",
        estimatedDurationMinutes: 7,
        submitted: false,
        questions: [{ questionId: 1 }, { questionId: 2 }, { questionId: 3 }],
      },
    });

    await expect(requestQuizSummary("session/1", "token")).resolves.toEqual({
      questionCount: 3,
      estimatedDurationMinutes: 7,
    });
    const [url, init] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(String(url)).toContain("/api/v1/sessions/session%2F1/quiz");
    expect(init).toMatchObject({
      credentials: "include",
      headers: { Accept: "application/json", Authorization: "Bearer token" },
    });
  });

  it("소수로 온 예상 시간은 버림한다", async () => {
    respondWith({ isSuccess: true, data: { questions: [{}], estimatedDurationMinutes: 7.9 } });

    await expect(requestQuizSummary("s1", "token")).resolves.toMatchObject({
      estimatedDurationMinutes: 7,
    });
  });

  it.each([undefined, Number.NaN, 0, -1, "10"])(
    "예상 시간이 %s 면 null 이다 — '약 0분' 을 화면에 내보내지 않는다",
    async (value) => {
      respondWith({
        isSuccess: true,
        data: { questions: [{}], estimatedDurationMinutes: value },
      });

      await expect(requestQuizSummary("s1", "token")).resolves.toMatchObject({
        questionCount: 1,
        estimatedDurationMinutes: null,
      });
    },
  );

  it("HTTP 와 전송 실패를 상태가 있는 오류로 구분한다", async () => {
    respondWith({}, 404);
    await expect(requestQuizSummary("s1", "token")).rejects.toMatchObject({ status: 404 });

    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
    await expect(requestQuizSummary("s1", "token")).rejects.toMatchObject({ status: 0 });
  });

  it("성공 봉투와 문항 배열이 잘못되면 계약 오류다", async () => {
    respondWith({ isSuccess: true, data: { questions: null, estimatedDurationMinutes: 10 } });
    await expect(requestQuizSummary("s1", "token")).rejects.toBeInstanceOf(QuizSummaryError);

    respondWith({ isSuccess: false, data: { questions: [] } });
    await expect(requestQuizSummary("s1", "token")).rejects.toBeInstanceOf(QuizSummaryError);
  });
});
