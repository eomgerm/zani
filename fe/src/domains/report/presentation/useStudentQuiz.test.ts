import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const auth = vi.hoisted(() => ({ accessToken: "token" as string | null }));
vi.mock("@/domains/auth", () => ({ useAuth: () => auth }));

import { StudentQuizError, type StudentQuiz } from "../infrastructure/studentQuizApi";
import { useStudentQuiz } from "./useStudentQuiz";

const quiz: StudentQuiz = {
  title: "퀴즈",
  description: "설명",
  estimatedDurationMinutes: 3,
  submitted: false,
  questions: [
    {
      questionId: "11",
      order: 1,
      text: "문항",
      options: [{ optionId: "101", order: 1, text: "보기" }],
      grading: null,
    },
  ],
};

beforeEach(() => {
  auth.accessToken = "token";
});

describe("useStudentQuiz", () => {
  it("퀴즈를 조회한다", async () => {
    const { result } = renderHook(() =>
      useStudentQuiz({ sessionId: "s1", request: vi.fn().mockResolvedValue(quiz) }),
    );

    await waitFor(() => expect(result.current.status).toBe("ready"));
    expect(result.current.quiz?.questions).toHaveLength(1);
  });

  it.each([
    [403, "forbidden"],
    [404, "notReady"],
    [500, "failed"],
  ] as const)("HTTP %s 를 %s 로 구분한다", async (status, expected) => {
    const { result } = renderHook(() =>
      useStudentQuiz({
        sessionId: "s1",
        request: vi.fn().mockRejectedValue(new StudentQuizError("no", status)),
      }),
    );

    await waitFor(() => expect(result.current.status).toBe(expected));
  });

  it("답안을 제출하면 채점을 들고 상태가 submitted 가 된다", async () => {
    const grading = {
      totalCount: 1,
      correctCount: 1,
      gradingByQuestionId: {
        "11": {
          correct: true,
          selectedOptionId: "101",
          correctOptionId: "101",
          explanation: "해설",
          sectionStartSeconds: null,
        },
      },
    };
    const submitRequest = vi.fn().mockResolvedValue(grading);
    const { result } = renderHook(() =>
      useStudentQuiz({ sessionId: "s1", request: vi.fn().mockResolvedValue(quiz), submitRequest }),
    );
    await waitFor(() => expect(result.current.status).toBe("ready"));

    act(() => result.current.submit([{ questionId: "11", selectedOptionId: "101" }]));

    await waitFor(() => expect(result.current.submitStatus).toBe("submitted"));
    expect(result.current.grading).toEqual(grading);
    expect(submitRequest).toHaveBeenCalledWith("s1", "token", [
      { questionId: "11", selectedOptionId: "101" },
    ]);
  });

  it("이미 제출된 퀴즈(409)는 실패가 아니라 다시 조회해 저장된 채점을 가져온다", async () => {
    const submittedQuiz: StudentQuiz = {
      ...quiz,
      submitted: true,
      questions: [
        {
          ...quiz.questions[0],
          grading: {
            correct: false,
            selectedOptionId: "101",
            correctOptionId: "102",
            explanation: "저장된 해설",
            sectionStartSeconds: null,
          },
        },
      ],
    };
    const request = vi.fn().mockResolvedValueOnce(quiz).mockResolvedValueOnce(submittedQuiz);
    const { result } = renderHook(() =>
      useStudentQuiz({
        sessionId: "s1",
        request,
        submitRequest: vi.fn().mockRejectedValue(new StudentQuizError("dup", 409)),
      }),
    );
    await waitFor(() => expect(result.current.status).toBe("ready"));

    act(() => result.current.submit([{ questionId: "11", selectedOptionId: "101" }]));

    await waitFor(() => expect(result.current.submitStatus).toBe("alreadySubmitted"));
    await waitFor(() => expect(result.current.quiz?.submitted).toBe(true));
    expect(request).toHaveBeenCalledTimes(2);
  });

  it("그 밖의 제출 실패는 failed 다", async () => {
    const { result } = renderHook(() =>
      useStudentQuiz({
        sessionId: "s1",
        request: vi.fn().mockResolvedValue(quiz),
        submitRequest: vi.fn().mockRejectedValue(new StudentQuizError("boom", 500)),
      }),
    );
    await waitFor(() => expect(result.current.status).toBe("ready"));

    act(() => result.current.submit([{ questionId: "11", selectedOptionId: "101" }]));

    await waitFor(() => expect(result.current.submitStatus).toBe("failed"));
  });

  it("빈 답안은 보내지 않는다 — 서버가 400 으로 되돌린다", async () => {
    const submitRequest = vi.fn();
    const { result } = renderHook(() =>
      useStudentQuiz({ sessionId: "s1", request: vi.fn().mockResolvedValue(quiz), submitRequest }),
    );
    await waitFor(() => expect(result.current.status).toBe("ready"));

    act(() => result.current.submit([]));

    expect(submitRequest).not.toHaveBeenCalled();
    expect(result.current.submitStatus).toBe("idle");
  });

  it("언마운트하면 진행 중 조회를 취소한다", async () => {
    const request = vi.fn().mockImplementation(
      (_id: string, _token: string, signal?: AbortSignal) =>
        new Promise((_resolve, reject) => {
          signal?.addEventListener("abort", () => reject(new Error("aborted")));
        }),
    );
    const { unmount } = renderHook(() => useStudentQuiz({ sessionId: "s1", request }));

    unmount();

    await waitFor(() => expect(request.mock.calls[0][2]?.aborted).toBe(true));
  });
});
