import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { usePromptTimer } from "./usePromptTimer";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("usePromptTimer", () => {
  it("counts down from the given duration while active", () => {
    const { result } = renderHook(() => usePromptTimer(true, 10_000, vi.fn()));

    expect(result.current.remainingMs).toBe(10_000);

    act(() => vi.advanceTimersByTime(4_000));

    expect(result.current.remainingMs).toBe(6_000);
  });

  it("calls onElapsed exactly once when the duration passes", () => {
    const onElapsed = vi.fn();
    const { result } = renderHook(() => usePromptTimer(true, 1_000, onElapsed));

    act(() => vi.advanceTimersByTime(1_000));

    expect(result.current.remainingMs).toBe(0);
    expect(onElapsed).toHaveBeenCalledTimes(1);

    act(() => vi.advanceTimersByTime(5_000));

    expect(onElapsed).toHaveBeenCalledTimes(1);
  });

  it("does nothing while inactive", () => {
    const onElapsed = vi.fn();
    const { result } = renderHook(() => usePromptTimer(false, 1_000, onElapsed));

    act(() => vi.advanceTimersByTime(5_000));

    expect(result.current.remainingMs).toBe(0);
    expect(onElapsed).not.toHaveBeenCalled();
  });

  it("restarts the countdown when active turns on", () => {
    const onElapsed = vi.fn();
    const { result, rerender } = renderHook(
      ({ active }) => usePromptTimer(active, 2_000, onElapsed),
      { initialProps: { active: false } },
    );

    expect(result.current.remainingMs).toBe(0);

    rerender({ active: true });
    act(() => vi.advanceTimersByTime(0));

    expect(result.current.remainingMs).toBe(2_000);

    act(() => vi.advanceTimersByTime(2_000));

    expect(onElapsed).toHaveBeenCalledTimes(1);
  });

  it("stops ticking once active turns off before elapsing", () => {
    const onElapsed = vi.fn();
    const { rerender } = renderHook(
      ({ active }) => usePromptTimer(active, 2_000, onElapsed),
      { initialProps: { active: true } },
    );

    act(() => vi.advanceTimersByTime(1_000));
    rerender({ active: false });
    act(() => vi.advanceTimersByTime(5_000));

    expect(onElapsed).not.toHaveBeenCalled();
  });

  it("stops ticking after unmount", () => {
    const { unmount } = renderHook(() => usePromptTimer(true, 2_000, vi.fn()));

    act(() => vi.advanceTimersByTime(500));
    unmount();

    expect(vi.getTimerCount()).toBe(0);
  });
});
