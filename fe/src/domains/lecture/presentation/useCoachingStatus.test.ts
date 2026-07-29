import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { COACH_POLL_INTERVAL_MS, useCoachingStatus } from "./useCoachingStatus";
import type { CoachPollResult } from "../infrastructure/coachPollApi";

const idle: CoachPollResult = {
  triggerId: null,
  tip: null,
  unavailableReason: null,
  audioUploadRequest: null,
};

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

    await act(async () => {
      await vi.advanceTimersByTimeAsync(COACH_POLL_INTERVAL_MS * 2);
    });

    expect(poll).toHaveBeenCalledTimes(3);
  });

  // 학생은 팁을 받지 않는다. 폴링 자체가 돌면 안 된다.
  it("does not poll while disabled", async () => {
    const poll = vi.fn().mockResolvedValue(idle);

    renderHook(() => useCoachingStatus({ sessionId: "s1", enabled: false, poll }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(COACH_POLL_INTERVAL_MS * 3);
    });

    expect(poll).not.toHaveBeenCalled();
  });

  it("reports a server-side failure so the instructor knows coaching broke", async () => {
    const poll = vi.fn().mockResolvedValue({ ...idle, unavailableReason: "TRANSCRIPTION_FAILED" });

    const { result } = renderHook(() =>
      useCoachingStatus({ sessionId: "s1", enabled: true, poll }),
    );
    await flushFirstPoll();

    expect(result.current.availability).toBe("TRANSCRIPTION_FAILED");
  });

  // 버퍼 부족·낮은 신뢰도는 정상 동작이라 알릴 것이 없다.
  it.each(["NO_TRANSCRIPT", "LOW_CONFIDENCE"] as const)(
    "stays quiet for %s",
    async (unavailableReason) => {
      const poll = vi.fn().mockResolvedValue({ ...idle, unavailableReason });

      const { result } = renderHook(() =>
        useCoachingStatus({ sessionId: "s1", enabled: true, poll }),
      );
      await flushFirstPoll();

      expect(result.current.availability).toBe("ACTIVE");
    },
  );

  it("reports a failed poll — a tip the instructor cannot receive is the same to them", async () => {
    const poll = vi.fn().mockRejectedValue(new Error("network down"));

    const { result } = renderHook(() =>
      useCoachingStatus({ sessionId: "s1", enabled: true, poll }),
    );
    await flushFirstPoll();

    expect(result.current.availability).toBe("POLL_FAILED");
  });

  it("recovers once a later poll succeeds", async () => {
    const poll = vi.fn().mockRejectedValueOnce(new Error("network down")).mockResolvedValue(idle);

    const { result } = renderHook(() =>
      useCoachingStatus({ sessionId: "s1", enabled: true, poll }),
    );
    await flushFirstPoll();
    expect(result.current.availability).toBe("POLL_FAILED");

    await act(async () => {
      await vi.advanceTimersByTimeAsync(COACH_POLL_INTERVAL_MS);
    });

    expect(result.current.availability).toBe("ACTIVE");
  });

  // 팁 카드(86)가 여기서 팁을 받아 간다.
  it("hands every successful result to the caller", async () => {
    const onResult = vi.fn();
    const result: CoachPollResult = {
      triggerId: "t-1",
      tip: {
        tipType: "CONFUSED_HIGH",
        title: "추가 설명이 필요해요",
        message: "전체 학생의 30%가 ...",
        targetConcept: "클로저",
      },
      unavailableReason: null,
      audioUploadRequest: null,
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

    await act(async () => {
      await vi.advanceTimersByTimeAsync(COACH_POLL_INTERVAL_MS * 3);
    });

    expect(poll).toHaveBeenCalledTimes(1);
  });
});
