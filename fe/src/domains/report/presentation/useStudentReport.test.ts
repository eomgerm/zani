import { renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/domains/auth", () => ({ useAuth: () => ({ accessToken: "token" }) }));

import { StudentReportError, type StudentReport } from "../infrastructure/studentReportApi";
import { useStudentReport } from "./useStudentReport";

const reportWith = (overrides: Partial<StudentReport> = {}): StudentReport => ({
  recordingUrl: "https://media.example/lecture.mp4?token=abc",
  durationSeconds: 90,
  transcript: [],
  recommendations: [],
  seekTimestamp: 0,
  ...overrides,
});

describe("useStudentReport", () => {
  it("조회가 성공하면 ready 와 리포트를 준다", async () => {
    const { result } = renderHook(() =>
      useStudentReport({ sessionId: "s1", request: async () => reportWith() }),
    );

    await waitFor(() => expect(result.current.status).toBe("ready"));
    expect(result.current.report?.recordingUrl).toContain("lecture.mp4");
  });

  it("403 은 forbidden, 404 와 409 는 notReady 다", async () => {
    const forbidden = renderHook(() =>
      useStudentReport({
        sessionId: "s1",
        request: async () => {
          throw new StudentReportError("forbidden", 403);
        },
      }),
    );
    await waitFor(() => expect(forbidden.result.current.status).toBe("forbidden"));

    const notReady = renderHook(() =>
      useStudentReport({
        sessionId: "s1",
        request: async () => {
          throw new StudentReportError("not ready", 404);
        },
      }),
    );
    await waitFor(() => expect(notReady.result.current.status).toBe("notReady"));

    const live = renderHook(() =>
      useStudentReport({
        sessionId: "s1",
        request: async () => {
          throw new StudentReportError("still live", 409);
        },
      }),
    );
    await waitFor(() => expect(live.result.current.status).toBe("notReady"));
  });

  it("그 밖의 실패는 failed 이고 retry 로 다시 조회한다", async () => {
    let calls = 0;
    const request = vi.fn(async () => {
      calls += 1;
      if (calls === 1) throw new StudentReportError("boom", 500);
      return reportWith();
    });

    const { result } = renderHook(() => useStudentReport({ sessionId: "s1", request }));

    await waitFor(() => expect(result.current.status).toBe("failed"));
    result.current.retry();
    await waitFor(() => expect(result.current.status).toBe("ready"));
  });

  it("언마운트하면 진행 중 요청을 취소한다", async () => {
    const request = vi.fn(
      (_sessionId: string, _token: string, signal?: AbortSignal) =>
        new Promise<StudentReport>(() => {
          void signal;
        }),
    );

    const { unmount } = renderHook(() => useStudentReport({ sessionId: "s1", request }));
    unmount();

    await waitFor(() => expect(request.mock.calls[0][2]?.aborted).toBe(true));
  });

  it("reissueRecordingUrl 은 새 URL 만 돌려주고 화면 상태를 건드리지 않는다", async () => {
    let calls = 0;
    const request = vi.fn(async () => {
      calls += 1;
      return reportWith({
        recordingUrl: `https://media.example/lecture.mp4?token=t${calls}`,
      });
    });

    const { result } = renderHook(() => useStudentReport({ sessionId: "s1", request }));
    await waitFor(() => expect(result.current.status).toBe("ready"));

    const fresh = await result.current.reissueRecordingUrl();

    expect(fresh).toBe("https://media.example/lecture.mp4?token=t2");
    // 재발급은 별도 조회다 — 이미 그려진 리포트는 그대로다.
    expect(result.current.report?.recordingUrl).toBe("https://media.example/lecture.mp4?token=t1");
  });

  it("재발급이 실패하면 null 이다 — 전사·추천까지 오류로 뒤집지 않는다", async () => {
    let calls = 0;
    const request = vi.fn(async () => {
      calls += 1;
      if (calls > 1) throw new StudentReportError("expired", 401);
      return reportWith();
    });

    const { result } = renderHook(() => useStudentReport({ sessionId: "s1", request }));
    await waitFor(() => expect(result.current.status).toBe("ready"));

    await expect(result.current.reissueRecordingUrl()).resolves.toBeNull();
    expect(result.current.status).toBe("ready");
  });
});
