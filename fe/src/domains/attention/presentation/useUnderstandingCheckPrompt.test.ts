import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  UNDERSTANDING_CHECK_COOLDOWN_MS,
  UNDERSTANDING_CHECK_SECONDS,
  useUnderstandingCheckPrompt,
} from "./useUnderstandingCheckPrompt";

const NOW = new Date("2026-07-27T12:00:00Z");

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useUnderstandingCheckPrompt", () => {
  it("opens a prompt with the full 30-second window when triggered", () => {
    const { result } = renderHook(() =>
      useUnderstandingCheckPrompt({ sessionId: "s1", sendResponse: vi.fn() }),
    );

    act(() => result.current.trigger("prompt-1"));
    act(() => vi.advanceTimersByTime(0));

    expect(result.current.prompt).toMatchObject({
      promptId: "prompt-1",
      remainingMs: UNDERSTANDING_CHECK_SECONDS * 1000,
    });
  });

  it("closes and reports the response when the student answers before the deadline", async () => {
    const sendResponse = vi.fn().mockResolvedValue(undefined);
    const { result } = renderHook(() =>
      useUnderstandingCheckPrompt({ sessionId: "s1", sendResponse }),
    );

    act(() => result.current.trigger("prompt-1"));
    act(() => vi.advanceTimersByTime(8_000));
    let sent: boolean | undefined;
    await act(async () => {
      sent = await result.current.respond("CONFUSED");
    });

    expect(sent).toBe(true);
    expect(result.current.prompt).toBeNull();
    expect(sendResponse).toHaveBeenCalledWith("s1", "prompt-1", "CONFUSED");
  });

  it("auto-closes as a non-response after 30 seconds without sending anything", () => {
    const sendResponse = vi.fn();
    const onTimedOut = vi.fn();
    const { result } = renderHook(() =>
      useUnderstandingCheckPrompt({ sessionId: "s1", sendResponse, onTimedOut }),
    );

    act(() => result.current.trigger("prompt-1"));
    act(() => vi.advanceTimersByTime(UNDERSTANDING_CHECK_SECONDS * 1000));

    expect(result.current.prompt).toBeNull();
    expect(onTimedOut).toHaveBeenCalledTimes(1);
    expect(sendResponse).not.toHaveBeenCalled();
  });

  it("ignores a second response to the same prompt", async () => {
    const sendResponse = vi.fn().mockResolvedValue(undefined);
    const { result } = renderHook(() =>
      useUnderstandingCheckPrompt({ sessionId: "s1", sendResponse }),
    );

    act(() => result.current.trigger("prompt-1"));
    await act(async () => {
      await result.current.respond("UNDERSTOOD");
    });
    await act(async () => {
      await result.current.respond("MISSED");
    });

    expect(sendResponse).toHaveBeenCalledTimes(1);
    expect(sendResponse).toHaveBeenCalledWith("s1", "prompt-1", "UNDERSTOOD");
  });

  it("ignores a trigger while a prompt is already open", () => {
    const { result } = renderHook(() =>
      useUnderstandingCheckPrompt({ sessionId: "s1", sendResponse: vi.fn() }),
    );

    act(() => result.current.trigger("prompt-1"));
    act(() => result.current.trigger("prompt-2"));

    expect(result.current.prompt?.promptId).toBe("prompt-1");
  });

  it("ignores a retrigger within the 5-minute cooldown after the last prompt closed", async () => {
    const { result } = renderHook(() =>
      useUnderstandingCheckPrompt({ sessionId: "s1", sendResponse: vi.fn().mockResolvedValue(undefined) }),
    );

    act(() => result.current.trigger("prompt-1"));
    await act(async () => {
      await result.current.respond("UNDERSTOOD");
    });
    act(() => vi.advanceTimersByTime(UNDERSTANDING_CHECK_COOLDOWN_MS - 1));
    act(() => result.current.trigger("prompt-2"));

    expect(result.current.prompt).toBeNull();
  });

  it("allows a retrigger once the 5-minute cooldown has passed", async () => {
    const { result } = renderHook(() =>
      useUnderstandingCheckPrompt({ sessionId: "s1", sendResponse: vi.fn().mockResolvedValue(undefined) }),
    );

    act(() => result.current.trigger("prompt-1"));
    await act(async () => {
      await result.current.respond("UNDERSTOOD");
    });
    act(() => vi.advanceTimersByTime(UNDERSTANDING_CHECK_COOLDOWN_MS));
    act(() => result.current.trigger("prompt-2"));

    expect(result.current.prompt?.promptId).toBe("prompt-2");
  });

  it("resolves to false instead of throwing when sending the response fails", async () => {
    const sendResponse = vi.fn().mockRejectedValue(new Error("network down"));
    const { result } = renderHook(() =>
      useUnderstandingCheckPrompt({ sessionId: "s1", sendResponse }),
    );

    act(() => result.current.trigger("prompt-1"));
    let sent: boolean | undefined;
    await act(async () => {
      sent = await result.current.respond("MISSED");
    });

    expect(sent).toBe(false);
    expect(result.current.prompt).toBeNull();
  });

  it("resolves to false for a duplicate response instead of sending again", async () => {
    const sendResponse = vi.fn().mockResolvedValue(undefined);
    const { result } = renderHook(() =>
      useUnderstandingCheckPrompt({ sessionId: "s1", sendResponse }),
    );

    act(() => result.current.trigger("prompt-1"));
    await act(async () => {
      await result.current.respond("UNDERSTOOD");
    });

    let secondAttempt: boolean | undefined;
    await act(async () => {
      secondAttempt = await result.current.respond("MISSED");
    });

    expect(secondAttempt).toBe(false);
    expect(sendResponse).toHaveBeenCalledTimes(1);
  });
});
