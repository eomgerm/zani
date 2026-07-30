import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  UNDERSTANDING_CHECK_COOLDOWN_MS,
  UNDERSTANDING_CHECK_SECONDS,
  useUnderstandingCheckPrompt,
} from "./useUnderstandingCheckPrompt";

const NOW = new Date("2026-07-27T12:00:00Z");
const DURATION_MS = UNDERSTANDING_CHECK_SECONDS * 1000;

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
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
      remainingMs: DURATION_MS,
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
    expect(sendResponse).toHaveBeenCalledWith("s1", "prompt-1", {
      kind: "UNDERSTANDING_CHECK",
      answer: "CONFUSED",
      shownAt: NOW.toISOString(),
      respondedAt: new Date(NOW.getTime() + 8_000).toISOString(),
    });
  });

  // 무전송을 신호로 쓰면 서버가 학생의 무응답과 브라우저 중단을 구분할 수 없다.
  it("sends NO_RESPONSE when the prompt closes without an answer", () => {
    const sendResponse = vi.fn().mockResolvedValue(undefined);
    const onTimedOut = vi.fn();
    const { result } = renderHook(() =>
      useUnderstandingCheckPrompt({ sessionId: "s1", sendResponse, onTimedOut }),
    );

    act(() => result.current.trigger("prompt-1"));
    act(() => vi.advanceTimersByTime(DURATION_MS));

    expect(result.current.prompt).toBeNull();
    expect(onTimedOut).toHaveBeenCalledTimes(1);
    expect(sendResponse).toHaveBeenCalledWith("s1", "prompt-1", {
      kind: "UNDERSTANDING_CHECK",
      answer: "NO_RESPONSE",
      shownAt: NOW.toISOString(),
      respondedAt: new Date(NOW.getTime() + DURATION_MS).toISOString(),
    });
  });

  it("does not throw when the automatic NO_RESPONSE fails to send", () => {
    const sendResponse = vi.fn().mockRejectedValue(new Error("network down"));
    const { result } = renderHook(() =>
      useUnderstandingCheckPrompt({ sessionId: "s1", sendResponse }),
    );

    act(() => result.current.trigger("prompt-1"));

    expect(() => act(() => vi.advanceTimersByTime(DURATION_MS))).not.toThrow();
    expect(result.current.prompt).toBeNull();
  });

  it("ignores a second response to the same prompt", async () => {
    const sendResponse = vi.fn().mockResolvedValue(undefined);
    const { result } = renderHook(() =>
      useUnderstandingCheckPrompt({ sessionId: "s1", sendResponse }),
    );

    act(() => result.current.trigger("prompt-1"));
    await act(async () => {
      await result.current.respond("OK");
    });
    await act(async () => {
      await result.current.respond("MISSED");
    });

    expect(sendResponse).toHaveBeenCalledTimes(1);
    expect(sendResponse).toHaveBeenCalledWith(
      "s1",
      "prompt-1",
      expect.objectContaining({ answer: "OK" }),
    );
  });

  // 판정 파이프라인(75)이 연속 카운터를 0으로 되돌릴 수 있어야 한다(§5).
  it("reports a close both when answered and when it times out", async () => {
    const onClosed = vi.fn();
    const { result } = renderHook(() =>
      useUnderstandingCheckPrompt({
        sessionId: "s1",
        sendResponse: vi.fn().mockResolvedValue(undefined),
        onClosed,
      }),
    );

    act(() => result.current.trigger("prompt-1"));
    await act(async () => {
      await result.current.respond("OK");
    });
    expect(onClosed).toHaveBeenCalledTimes(1);

    act(() => vi.advanceTimersByTime(UNDERSTANDING_CHECK_COOLDOWN_MS));
    act(() => result.current.trigger("prompt-2"));
    act(() => vi.advanceTimersByTime(DURATION_MS));

    expect(onClosed).toHaveBeenCalledTimes(2);
  });

  it("does not open while the tab is hidden", () => {
    // jsdom 의 visibilityState 는 getter 라 spy 로 덮는다.
    vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
    const { result } = renderHook(() =>
      useUnderstandingCheckPrompt({ sessionId: "s1", sendResponse: vi.fn() }),
    );

    act(() => result.current.trigger("prompt-1"));

    expect(result.current.prompt).toBeNull();
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
      useUnderstandingCheckPrompt({
        sessionId: "s1",
        sendResponse: vi.fn().mockResolvedValue(undefined),
      }),
    );

    act(() => result.current.trigger("prompt-1"));
    await act(async () => {
      await result.current.respond("OK");
    });
    act(() => vi.advanceTimersByTime(UNDERSTANDING_CHECK_COOLDOWN_MS - 1));
    act(() => result.current.trigger("prompt-2"));

    expect(result.current.prompt).toBeNull();
  });

  it("allows a retrigger once the 5-minute cooldown has passed", async () => {
    const { result } = renderHook(() =>
      useUnderstandingCheckPrompt({
        sessionId: "s1",
        sendResponse: vi.fn().mockResolvedValue(undefined),
      }),
    );

    act(() => result.current.trigger("prompt-1"));
    await act(async () => {
      await result.current.respond("OK");
    });
    act(() => vi.advanceTimersByTime(UNDERSTANDING_CHECK_COOLDOWN_MS));
    act(() => result.current.trigger("prompt-2"));

    expect(result.current.prompt?.promptId).toBe("prompt-2");
  });

  // 표시 시각이 아니라 닫힌 시각부터 재는지 — 30초를 다 쓴 프롬프트에서만 차이가 드러난다.
  it("measures the cooldown from when the prompt closed, not when it opened", () => {
    const { result } = renderHook(() =>
      useUnderstandingCheckPrompt({
        sessionId: "s1",
        sendResponse: vi.fn().mockResolvedValue(undefined),
      }),
    );

    act(() => result.current.trigger("prompt-1"));
    act(() => vi.advanceTimersByTime(DURATION_MS)); // 무응답으로 자동 종료.

    // 표시 시각 기준이라면 이 시점에 쿨다운이 끝나지만, 닫힌 시각 기준이면 아직 30초 남았다.
    act(() => vi.advanceTimersByTime(UNDERSTANDING_CHECK_COOLDOWN_MS - DURATION_MS));
    act(() => result.current.trigger("prompt-2"));
    expect(result.current.prompt).toBeNull();

    act(() => vi.advanceTimersByTime(DURATION_MS));
    act(() => result.current.trigger("prompt-3"));
    expect(result.current.prompt?.promptId).toBe("prompt-3");
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
      await result.current.respond("OK");
    });

    let secondAttempt: boolean | undefined;
    await act(async () => {
      secondAttempt = await result.current.respond("MISSED");
    });

    expect(secondAttempt).toBe(false);
    expect(sendResponse).toHaveBeenCalledTimes(1);
  });
});
