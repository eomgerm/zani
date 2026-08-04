import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const auth = vi.hoisted(() => ({ accessToken: "token" as string | null }));
vi.mock("@/domains/auth", () => ({ useAuth: () => auth }));

import { QuizSummaryError } from "../infrastructure/quizSummaryApi";
import { useQuizSummary } from "./useQuizSummary";

beforeEach(() => {
  auth.accessToken = "token";
});

describe("useQuizSummary", () => {
  it("퀴즈 요약을 조회한다", async () => {
    const summary = { questionCount: 3, estimatedDurationMinutes: 10 };
    const { result } = renderHook(() =>
      useQuizSummary({ sessionId: "s1", request: vi.fn().mockResolvedValue(summary) }),
    );

    await waitFor(() => expect(result.current.status).toBe("ready"));
    expect(result.current.summary).toEqual(summary);
  });

  it("404 는 notReady, 그 밖의 실패는 failed 다", async () => {
    const missing = renderHook(() =>
      useQuizSummary({
        sessionId: "s1",
        request: vi.fn().mockRejectedValue(new QuizSummaryError("missing", 404)),
      }),
    );
    await waitFor(() => expect(missing.result.current.status).toBe("notReady"));

    const failed = renderHook(() =>
      useQuizSummary({ sessionId: "s1", request: vi.fn().mockRejectedValue(new Error("boom")) }),
    );
    await waitFor(() => expect(failed.result.current.status).toBe("failed"));
  });

  it("실패 후 retry 로 다시 조회한다", async () => {
    const request = vi
      .fn()
      .mockRejectedValueOnce(new Error("boom"))
      .mockResolvedValueOnce({ questionCount: 1, estimatedDurationMinutes: 5 });
    const { result } = renderHook(() => useQuizSummary({ sessionId: "s1", request }));
    await waitFor(() => expect(result.current.status).toBe("failed"));

    act(() => result.current.retry());

    await waitFor(() => expect(result.current.status).toBe("ready"));
    expect(request).toHaveBeenCalledTimes(2);
  });

  it("언마운트하면 진행 중 요청을 취소한다", async () => {
    const request = vi.fn().mockImplementation(
      (_id: string, _token: string, signal?: AbortSignal) =>
        new Promise((_resolve, reject) => {
          signal?.addEventListener("abort", () => reject(new Error("aborted")));
        }),
    );
    const { unmount } = renderHook(() => useQuizSummary({ sessionId: "s1", request }));

    unmount();

    await waitFor(() => expect(request.mock.calls[0][2]?.aborted).toBe(true));
  });
});
