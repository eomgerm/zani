import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const auth = vi.hoisted(() => ({ accessToken: "token" as string | null }));
vi.mock("@/domains/auth", () => ({ useAuth: () => auth }));

import { StudentReportError, type StudentReport } from "../infrastructure/studentReportApi";
import { useStudentReport } from "./useStudentReport";

const report: StudentReport = {
  activity: { publicChatCount: 3, confusedCount: 1, missedCount: 0, questionCount: 2 },
  participationSummary: "요약",
  recommendations: [],
};

beforeEach(() => {
  auth.accessToken = "token";
});

describe("useStudentReport", () => {
  it("토큰을 기다린 뒤 한 번만 조회한다", async () => {
    auth.accessToken = null;
    const request = vi.fn().mockResolvedValue(report);
    const { result, rerender } = renderHook(() => useStudentReport({ sessionId: "s1", request }));

    expect(result.current.status).toBe("loading");
    expect(request).not.toHaveBeenCalled();

    auth.accessToken = "token";
    rerender();

    await waitFor(() => expect(result.current.status).toBe("ready"));
    expect(result.current.report).toEqual(report);
    expect(request).toHaveBeenCalledTimes(1);
  });

  it.each([
    [403, "forbidden"],
    [404, "notReady"],
    [409, "live"],
    [500, "failed"],
  ] as const)("HTTP %s 를 %s 로 구분한다", async (status, expected) => {
    const { result } = renderHook(() =>
      useStudentReport({
        sessionId: "s1",
        request: vi.fn().mockRejectedValue(new StudentReportError("no", status)),
      }),
    );

    await waitFor(() => expect(result.current.status).toBe(expected));
    expect(result.current.report).toBeNull();
  });

  it("StudentReportError 가 아닌 오류도 failed 다", async () => {
    const { result } = renderHook(() =>
      useStudentReport({
        sessionId: "s1",
        request: vi.fn().mockRejectedValue(new Error("boom")),
      }),
    );

    await waitFor(() => expect(result.current.status).toBe("failed"));
  });

  it("재시도 동안 이전 결과를 노출하지 않는다", async () => {
    let resolveRetry: (value: StudentReport) => void = () => undefined;
    const request = vi
      .fn()
      .mockResolvedValueOnce(report)
      .mockImplementationOnce(
        () =>
          new Promise<StudentReport>((resolve) => {
            resolveRetry = resolve;
          }),
      );
    const { result } = renderHook(() => useStudentReport({ sessionId: "s1", request }));
    await waitFor(() => expect(result.current.status).toBe("ready"));

    act(() => result.current.retry());

    expect(result.current).toMatchObject({ status: "loading", report: null });

    act(() => resolveRetry(report));
    await waitFor(() => expect(result.current.status).toBe("ready"));
  });

  it("언마운트하면 진행 중 요청을 취소한다", async () => {
    const request = vi.fn().mockImplementation(
      (_id: string, _token: string, signal?: AbortSignal) =>
        new Promise((_resolve, reject) => {
          signal?.addEventListener("abort", () => reject(new Error("aborted")));
        }),
    );
    const { unmount } = renderHook(() => useStudentReport({ sessionId: "s1", request }));

    unmount();

    await waitFor(() => expect(request.mock.calls[0][2]?.aborted).toBe(true));
  });
});
