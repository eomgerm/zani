import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/domains/auth", () => ({ useAuth: () => ({ accessToken: "token" }) }));

import { useInstructorReport } from "./useInstructorReport";
import {
  InstructorReportError,
  type InstructorReport,
  type InstructorReportRequester,
} from "../infrastructure/instructorReportApi";

const report: InstructorReport = {
  overallFeedback: "좋았습니다.",
  stats: { studentCount: 3, durationSeconds: 600, questionCount: 2, alertCount: 1 },
  scores: [],
  insights: [],
};

const failing = (status: number): InstructorReportRequester => async () => {
  throw new InstructorReportError(`status ${status}`, status);
};

describe("useInstructorReport", () => {
  it("성공하면 ready 와 함께 리포트를 준다", async () => {
    const { result } = renderHook(() =>
      useInstructorReport({ sessionId: "s1", request: async () => report }),
    );

    await waitFor(() => expect(result.current.status).toBe("ready"));
    expect(result.current.report).toEqual(report);
  });

  it("403 과 404 를 다른 상태로 가른다", async () => {
    for (const [status, expected] of [
      [403, "forbidden"],
      [404, "notReady"],
      // 서버는 진행 중을 404 로 번역하는 계약이지만 409 를 그대로 내보내는 선례가 있다.
      [409, "notReady"],
      [500, "failed"],
    ] as const) {
      const { result } = renderHook(() =>
        useInstructorReport({ sessionId: "s1", request: failing(status) }),
      );

      await waitFor(() => expect(result.current.status).toBe(expected));
      expect(result.current.report).toBeNull();
    }
  });

  it("응답을 못 받은 경우(상태 0)는 실패로 읽는다", async () => {
    const { result } = renderHook(() =>
      useInstructorReport({ sessionId: "s1", request: failing(0) }),
    );

    // 네트워크가 끊긴 것을 "권한 없음" 이나 "아직 없음" 으로 말하면 안 된다.
    await waitFor(() => expect(result.current.status).toBe("failed"));
  });

  it("retry 가 다시 조회한다", async () => {
    const request = vi
      .fn()
      .mockRejectedValueOnce(new InstructorReportError("boom", 500))
      .mockResolvedValueOnce(report) as unknown as InstructorReportRequester;

    const { result } = renderHook(() => useInstructorReport({ sessionId: "s1", request }));

    await waitFor(() => expect(result.current.status).toBe("failed"));
    act(() => result.current.retry());

    await waitFor(() => expect(result.current.status).toBe("ready"));
  });

  it("재시도하는 동안 이전 실패를 물려주지 않는다", async () => {
    let resolve: ((value: InstructorReport) => void) | null = null;
    const request = vi
      .fn()
      .mockRejectedValueOnce(new InstructorReportError("boom", 500))
      .mockImplementationOnce(
        () =>
          new Promise<InstructorReport>((r) => {
            resolve = r;
          }),
      ) as unknown as InstructorReportRequester;

    const { result } = renderHook(() => useInstructorReport({ sessionId: "s1", request }));

    await waitFor(() => expect(result.current.status).toBe("failed"));
    act(() => result.current.retry());

    // 답이 오기 전에 "불러오지 못했어요" 가 그대로 남으면 재시도가 먹지 않은 것처럼 보인다.
    expect(result.current.status).toBe("loading");

    await act(async () => {
      resolve?.(report);
    });
    await waitFor(() => expect(result.current.status).toBe("ready"));
  });

  it("언마운트하면 진행 중인 요청을 취소한다", async () => {
    const aborted: boolean[] = [];
    const request: InstructorReportRequester = (_sessionId, _token, signal) =>
      new Promise<InstructorReport>(() => {
        signal?.addEventListener("abort", () => aborted.push(true));
      });

    const { unmount } = renderHook(() => useInstructorReport({ sessionId: "s1", request }));
    unmount();

    expect(aborted).toEqual([true]);
  });
});
