import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { COACH_POLL_INTERVAL_MS, useCoachingStatus } from "./useCoachingStatus";
import { COACH_POLL_FAILURE_THRESHOLD } from "../domain/coachingAvailability";
import type { CoachPollResult } from "../infrastructure/coachPollApi";

const idle: CoachPollResult = { triggerId: null, tip: null, unavailableReason: null };

beforeEach(() => {
  vi.useFakeTimers();
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
    expect(poll).toHaveBeenCalledWith("s1", expect.anything());
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
