import { render, screen, act } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { SessionTimeWarning } from "./SessionTimeWarning";

const NOW = new Date("2026-07-26T12:00:00Z");

const isoIn = (minutes: number) => new Date(NOW.getTime() + minutes * 60_000).toISOString();

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
});

afterEach(() => {
  vi.useRealTimers();
});

describe("SessionTimeWarning", () => {
  it("renders nothing while the session is far from its maximum duration", () => {
    render(<SessionTimeWarning expiresAt={isoIn(45)} />);
    act(() => vi.advanceTimersByTime(0));

    expect(screen.queryByRole("status")).toBeNull();
  });

  it("announces the remaining time when the deadline is near", () => {
    render(<SessionTimeWarning expiresAt={isoIn(9)} />);
    act(() => vi.advanceTimersByTime(0));

    const banner = screen.getByRole("status");
    expect(banner).toHaveAttribute("aria-live", "polite");
    expect(banner.textContent).toContain("9:00");
  });

  it("announces the imminent shutdown once the deadline has passed", () => {
    render(<SessionTimeWarning expiresAt={isoIn(-2)} />);
    act(() => vi.advanceTimersByTime(0));

    expect(screen.getByRole("status").textContent).toContain("곧 자동으로 종료");
  });

  it("renders nothing without an expiry time", () => {
    render(<SessionTimeWarning />);
    act(() => vi.advanceTimersByTime(0));

    expect(screen.queryByRole("status")).toBeNull();
  });
});
