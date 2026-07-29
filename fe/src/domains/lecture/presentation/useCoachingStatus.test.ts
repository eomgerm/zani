import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// 토큰은 인증 컨텍스트가 메모리에만 들고 있는 값이다. 여기서는 고정값으로 대체한다.
const auth = vi.hoisted(() => ({ accessToken: "test-access-token" as string | null }));
vi.mock("@/domains/auth", () => ({ useAuth: () => auth }));

import { COACH_POLL_INTERVAL_MS, useCoachingStatus } from "./useCoachingStatus";
import { COACH_POLL_FAILURE_THRESHOLD } from "../domain/coachingAvailability";
import { CoachPollError, type CoachPollResult } from "../infrastructure/coachPollApi";

const idle: CoachPollResult = { triggerId: null, tip: null, unavailableReason: null };

beforeEach(() => {
  vi.useFakeTimers();
  auth.accessToken = "test-access-token";
});

afterEach(() => {
  vi.useRealTimers();
});

/** 첫 조회는 마운트 직후 곧바로 나간다. 그 결과를 흘려보낸다. */
const flushFirstPoll = async () => {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
};

/** 주기를 n 번 흘려보낸다. */
const advancePolls = async (n: number) => {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(COACH_POLL_INTERVAL_MS * n);
  });
};

describe("useCoachingStatus", () => {
  it("polls right away instead of waiting a full interval", async () => {
    const poll = vi.fn().mockResolvedValue(idle);

    renderHook(() => useCoachingStatus({ sessionId: "s1", enabled: true, poll }));
    await flushFirstPoll();

    expect(poll).toHaveBeenCalledTimes(1);
    // 쿠키는 refresh 전용이라 Bearer 토큰이 없으면 서버가 401 을 준다.
    expect(poll).toHaveBeenCalledWith("s1", "test-access-token", expect.anything());
  });

  it("does not poll before a token is available", async () => {
    auth.accessToken = null;
    const poll = vi.fn().mockResolvedValue(idle);

    renderHook(() => useCoachingStatus({ sessionId: "s1", enabled: true, poll }));
    await advancePolls(3);

    expect(poll).not.toHaveBeenCalled();
  });

  it("keeps polling every 10 seconds", async () => {
    const poll = vi.fn().mockResolvedValue(idle);

    renderHook(() => useCoachingStatus({ sessionId: "s1", enabled: true, poll }));
    await flushFirstPoll();
    await advancePolls(2);

    expect(poll).toHaveBeenCalledTimes(3);
  });

  // 학생은 팁을 받지 않는다. 폴링 자체가 돌면 안 된다.
  it("does not poll while disabled", async () => {
    const poll = vi.fn().mockResolvedValue(idle);

    renderHook(() => useCoachingStatus({ sessionId: "s1", enabled: false, poll }));
    await advancePolls(3);

    expect(poll).not.toHaveBeenCalled();
  });

  // 트리거 하나가 실패한 것은 코칭이 죽은 것이 아니다. 다음 트리거에서 회복된다(85 계약).
  it.each([
    "NO_TRANSCRIPT",
    "TRANSCRIPTION_FAILED",
    "TIP_GENERATION_FAILED",
    "LOW_CONFIDENCE",
  ] as const)("stays quiet when the server reports %s", async (unavailableReason) => {
    const poll = vi.fn().mockResolvedValue({ ...idle, unavailableReason });

    const { result } = renderHook(() =>
      useCoachingStatus({ sessionId: "s1", enabled: true, poll }),
    );
    await flushFirstPoll();

    expect(result.current.availability).toBe("ACTIVE");
  });

  // 한 번의 실패는 네트워크가 잠깐 흔들린 것일 수 있다.
  it("tolerates failures until they repeat enough", async () => {
    const poll = vi.fn().mockRejectedValue(new Error("network down"));

    const { result } = renderHook(() =>
      useCoachingStatus({ sessionId: "s1", enabled: true, poll }),
    );
    await flushFirstPoll();
    await advancePolls(COACH_POLL_FAILURE_THRESHOLD - 2);

    expect(result.current.availability).toBe("ACTIVE");
  });

  it("reports once the failures reach the threshold", async () => {
    const poll = vi.fn().mockRejectedValue(new Error("network down"));

    const { result } = renderHook(() =>
      useCoachingStatus({ sessionId: "s1", enabled: true, poll }),
    );
    await flushFirstPoll();
    await advancePolls(COACH_POLL_FAILURE_THRESHOLD - 1);

    expect(result.current.availability).toBe("POLL_FAILED");
  });

  it("clears the notice as soon as one poll succeeds again", async () => {
    const poll = vi.fn().mockRejectedValue(new Error("network down"));

    const { result } = renderHook(() =>
      useCoachingStatus({ sessionId: "s1", enabled: true, poll }),
    );
    await flushFirstPoll();
    await advancePolls(COACH_POLL_FAILURE_THRESHOLD - 1);
    expect(result.current.availability).toBe("POLL_FAILED");

    poll.mockResolvedValue(idle);
    await advancePolls(1);

    expect(result.current.availability).toBe("ACTIVE");
  });

  // 85 컨트롤러가 "클라이언트는 폴링을 멈춘다" 로 못박은 상태들이다. 다시 물어도 답이 같다.
  it.each([
    [409, "이미 종료된 세션"],
    [403, "팁을 받을 수 없는 역할"],
  ])("stops polling on %i (%s)", async (status) => {
    const poll = vi.fn().mockRejectedValue(new CoachPollError("nope", status));

    const { result } = renderHook(() =>
      useCoachingStatus({ sessionId: "s1", enabled: true, poll }),
    );
    await flushFirstPoll();
    await advancePolls(5);

    expect(poll).toHaveBeenCalledTimes(1);
    // 다시 물을 수 없는 상태를 고장으로 알리지는 않는다.
    expect(result.current.availability).toBe("ACTIVE");
  });

  // 이 폴링이 곧 트리거 판정이라(85) 겹쳐 돌면 분모 조회와 쿨타임 소모가 두 번 일어난다.
  it("waits for the previous poll before scheduling the next", async () => {
    let settle: (value: CoachPollResult) => void = () => {};
    const poll = vi.fn().mockImplementation(
      () =>
        new Promise<CoachPollResult>((resolve) => {
          settle = resolve;
        }),
    );

    renderHook(() => useCoachingStatus({ sessionId: "s1", enabled: true, poll }));
    await flushFirstPoll();

    // 응답이 주기보다 오래 걸려도 다음 조회가 겹쳐 나가지 않는다.
    await advancePolls(3);
    expect(poll).toHaveBeenCalledTimes(1);

    await act(async () => {
      settle(idle);
      await vi.advanceTimersByTimeAsync(0);
    });
    await advancePolls(1);

    expect(poll).toHaveBeenCalledTimes(2);
  });

  // 팁 카드(86)가 여기서 팁을 받아 간다.
  it("hands every successful result to the caller", async () => {
    const onResult = vi.fn();
    const result: CoachPollResult = {
      triggerId: "t-1",
      tip: {
        tipType: "CONFUSED",
        title: "지금 다시 짚고 갈 개념이 있습니다",
        message: "전체 학생의 34%가 헷갈려하고 있습니다.",
        targetConcept: "재귀 호출의 종료 조건",
      },
      unavailableReason: null,
    };
    const poll = vi.fn().mockResolvedValue(result);

    renderHook(() => useCoachingStatus({ sessionId: "s1", enabled: true, poll, onResult }));
    await flushFirstPoll();

    expect(onResult).toHaveBeenCalledWith(result);
  });

  it("stops polling once unmounted", async () => {
    const poll = vi.fn().mockResolvedValue(idle);

    const { unmount } = renderHook(() =>
      useCoachingStatus({ sessionId: "s1", enabled: true, poll }),
    );
    await flushFirstPoll();
    unmount();
    await advancePolls(3);

    expect(poll).toHaveBeenCalledTimes(1);
  });
});
