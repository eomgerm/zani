import { renderHook, act } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useSessionTimeWarning } from "./useSessionTimeWarning";

const NOW = new Date("2026-07-26T12:00:00Z");

const isoIn = (minutes: number) => new Date(NOW.getTime() + minutes * 60_000).toISOString();

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useSessionTimeWarning", () => {
  it("stays silent while more than ten minutes remain", () => {
    const { result } = renderHook(() => useSessionTimeWarning(isoIn(30)));
    act(() => vi.advanceTimersByTime(0));

    expect(result.current.warning).toBe(false);
    expect(result.current.expired).toBe(false);
    expect(result.current.remainingMs).toBe(30 * 60_000);
  });

  it("warns once the remaining time reaches the ten minute threshold", () => {
    const { result } = renderHook(() => useSessionTimeWarning(isoIn(10)));
    act(() => vi.advanceTimersByTime(0));

    expect(result.current.warning).toBe(true);
    expect(result.current.expired).toBe(false);
  });

  it("counts down every second while warning", () => {
    const { result } = renderHook(() => useSessionTimeWarning(isoIn(2)));
    act(() => vi.advanceTimersByTime(0));
    expect(result.current.remainingMs).toBe(2 * 60_000);

    act(() => vi.advanceTimersByTime(60_000));

    expect(result.current.remainingMs).toBe(60_000);
    expect(result.current.warning).toBe(true);
  });

  it("reports expired without a negative remaining time once the deadline passes", () => {
    const { result } = renderHook(() => useSessionTimeWarning(isoIn(-1)));
    act(() => vi.advanceTimersByTime(0));

    expect(result.current.expired).toBe(true);
    expect(result.current.warning).toBe(false);
    expect(result.current.remainingMs).toBe(0);
  });

  it("stays silent when expiresAt is missing or unparsable", () => {
    const missing = renderHook(() => useSessionTimeWarning(undefined));
    const invalid = renderHook(() => useSessionTimeWarning("not-a-date"));
    act(() => vi.advanceTimersByTime(0));

    expect(missing.result.current).toEqual({ remainingMs: 0, warning: false, expired: false });
    expect(invalid.result.current).toEqual({ remainingMs: 0, warning: false, expired: false });
  });

  it("stops ticking after unmount", () => {
    const { unmount } = renderHook(() => useSessionTimeWarning(isoIn(5)));
    act(() => vi.advanceTimersByTime(0));

    unmount();

    expect(vi.getTimerCount()).toBe(0);
  });
});
