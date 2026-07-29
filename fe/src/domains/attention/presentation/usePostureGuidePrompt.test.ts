import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  POSTURE_GUIDE_COOLDOWN_MS,
  POSTURE_GUIDE_SECONDS,
  usePostureGuidePrompt,
} from "./usePostureGuidePrompt";

const DURATION_MS = POSTURE_GUIDE_SECONDS * 1000;

/** 탭이 숨겨진 상태를 흉내 낸다. jsdom 의 visibilityState 는 getter 라 spy 로 덮는다. */
function hideTab() {
  vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-07-28T09:00:00Z"));
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("usePostureGuidePrompt", () => {
  it("opens with the full 30-second window when triggered", () => {
    const { result } = renderHook(() => usePostureGuidePrompt());

    act(() => result.current.trigger("posture-1"));
    act(() => vi.advanceTimersByTime(0));

    expect(result.current.prompt).toMatchObject({
      promptId: "posture-1",
      remainingMs: DURATION_MS,
    });
  });

  it("closes on acknowledgement and reports it so the counters can reset", () => {
    const onClosed = vi.fn();
    const { result } = renderHook(() => usePostureGuidePrompt({ onClosed }));

    act(() => result.current.trigger("posture-1"));
    act(() => result.current.acknowledge());

    expect(result.current.prompt).toBeNull();
    expect(onClosed).toHaveBeenCalledTimes(1);
  });

  it("closes itself after 30 seconds without an acknowledgement", () => {
    const onClosed = vi.fn();
    const { result } = renderHook(() => usePostureGuidePrompt({ onClosed }));

    act(() => result.current.trigger("posture-1"));
    act(() => vi.advanceTimersByTime(DURATION_MS));

    expect(result.current.prompt).toBeNull();
    expect(onClosed).toHaveBeenCalledTimes(1);
  });

  it("ignores a trigger while one is already open", () => {
    const { result } = renderHook(() => usePostureGuidePrompt());

    act(() => result.current.trigger("posture-1"));
    act(() => result.current.trigger("posture-2"));

    expect(result.current.prompt?.promptId).toBe("posture-1");
  });

  // 쿨타임은 이해 확인과 따로 재고, 표시 시각이 아니라 닫힌 시각부터 잰다(§5).
  it("measures its own 5-minute cooldown from when the prompt closed", () => {
    const { result } = renderHook(() => usePostureGuidePrompt());

    act(() => result.current.trigger("posture-1"));
    act(() => vi.advanceTimersByTime(DURATION_MS)); // 무응답으로 자동 종료.

    act(() => vi.advanceTimersByTime(POSTURE_GUIDE_COOLDOWN_MS - 1));
    act(() => result.current.trigger("posture-2"));
    expect(result.current.prompt).toBeNull();

    act(() => vi.advanceTimersByTime(1));
    act(() => result.current.trigger("posture-3"));
    expect(result.current.prompt?.promptId).toBe("posture-3");
  });

  it("does not open while the tab is hidden", () => {
    hideTab();
    const { result } = renderHook(() => usePostureGuidePrompt());

    act(() => result.current.trigger("posture-1"));

    expect(result.current.prompt).toBeNull();
  });

  // 확인 버튼 하나뿐이라 담긴 정보가 없다 — 서버로 보내지 않는다(§6).
  it("never reaches the network, whether acknowledged or timed out", () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const { result } = renderHook(() => usePostureGuidePrompt());

    act(() => result.current.trigger("posture-1"));
    act(() => result.current.acknowledge());
    act(() => result.current.trigger("posture-2"));
    act(() => vi.advanceTimersByTime(DURATION_MS));

    expect(fetchSpy).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });
});
