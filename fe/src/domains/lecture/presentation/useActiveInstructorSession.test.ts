import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { SessionSummary } from "@/domains/lecture/infrastructure/sessionListApi";
import { useActiveInstructorSession } from "./useActiveInstructorSession";

const { authState } = vi.hoisted(() => ({
  authState: { accessToken: "access-token" as string | null },
}));

vi.mock("@/domains/auth", () => ({
  useAuth: () => authState,
}));

const summary = (over: Partial<SessionSummary> = {}): SessionSummary => ({
  sessionId: "100",
  inviteCode: "AAAAAAAA",
  title: "테스트 수업",
  instructorName: "박강사",
  status: "LIVE",
  role: "INSTRUCTOR",
  startedAt: "2026-08-03T09:00:00Z",
  endedAt: null,
  participantCount: 3,
  reportStatus: "NONE",
  rejoinable: true,
  ...over,
});

const lister = (sessions: SessionSummary[]) => vi.fn().mockResolvedValue(sessions);

beforeEach(() => {
  authState.accessToken = "access-token";
  vi.spyOn(console, "warn").mockImplementation(() => {});
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("useActiveInstructorSession", () => {
  /** 배너의 용도는 "돌아가기·종료" 다. 아직 끝내지 않은 내 수업만 대상이다. */
  it("강사로 진행 중인 수업을 찾는다", async () => {
    const { result } = renderHook(() => useActiveInstructorSession(lister([summary()])));

    await waitFor(() => expect(result.current.session?.sessionId).toBe("100"));
  });

  /** 준비 중인 수업도 아직 끝내지 않은 수업이라, 이게 있으면 새 수업을 만들 수 없다. */
  it("준비 중인 수업도 찾는다", async () => {
    const { result } = renderHook(() =>
      useActiveInstructorSession(lister([summary({ status: "PREPARING" })])),
    );

    await waitFor(() => expect(result.current.session?.status).toBe("PREPARING"));
  });

  /** 학생으로 들어간 수업은 내가 종료할 수 없다. 배너에 띄우면 할 수 없는 조작을 권하는 셈이다. */
  it("학생으로 참여한 수업은 무시한다", async () => {
    const requestList = lister([summary({ role: "STUDENT" })]);
    const { result } = renderHook(() => useActiveInstructorSession(requestList));

    await waitFor(() => expect(requestList).toHaveBeenCalled());

    expect(result.current.session).toBeNull();
  });

  it("이미 끝난 수업은 무시한다", async () => {
    const requestList = lister([summary({ status: "ENDED" })]);
    const { result } = renderHook(() => useActiveInstructorSession(requestList));

    await waitFor(() => expect(requestList).toHaveBeenCalled());

    expect(result.current.session).toBeNull();
  });

  /** 조회 실패와 "활성 수업 없음" 을 밖으로 구분하지 않는다. 대신 원인은 콘솔에 남겨 디버깅 단서를 지킨다. */
  it("조회가 실패하면 배너를 숨기고 원인만 남긴다", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const requestList = vi.fn().mockRejectedValue(new Error("boom"));

    const { result } = renderHook(() => useActiveInstructorSession(requestList));

    await waitFor(() => expect(warn).toHaveBeenCalled());
    expect(result.current.session).toBeNull();
  });

  /** 취소는 실패가 아니다. 화면을 떠났거나 토큰이 갱신되어 다시 조회하는 경우다. */
  it("언마운트로 취소된 조회는 오류로 남기지 않는다", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    let reject!: (reason: unknown) => void;
    const requestList = vi.fn(
      (_token: string, signal?: AbortSignal) =>
        new Promise<SessionSummary[]>((_resolve, promiseReject) => {
          reject = promiseReject;
          signal?.addEventListener("abort", () => promiseReject(new Error("aborted")));
        }),
    );

    const { unmount } = renderHook(() => useActiveInstructorSession(requestList));
    unmount();
    await act(async () => {
      reject(new Error("aborted"));
    });

    expect(warn).not.toHaveBeenCalled();
  });

  it("로그인 전에는 조회하지 않는다", async () => {
    authState.accessToken = null;
    const requestList = lister([summary()]);

    const { result } = renderHook(() => useActiveInstructorSession(requestList));

    expect(requestList).not.toHaveBeenCalled();
    expect(result.current.session).toBeNull();
  });

  /** 수업을 종료한 뒤 배너가 사라져야 한다. 다시 조회해서 확인한다. */
  it("refresh 를 부르면 다시 조회한다", async () => {
    const requestList = vi
      .fn()
      .mockResolvedValueOnce([summary()])
      .mockResolvedValueOnce([summary({ status: "ENDED" })]);
    const { result } = renderHook(() => useActiveInstructorSession(requestList));
    await waitFor(() => expect(result.current.session).not.toBeNull());

    act(() => result.current.refresh());

    await waitFor(() => expect(result.current.session).toBeNull());
    expect(requestList).toHaveBeenCalledTimes(2);
  });
});
