import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  CAMERA_GUIDE_OFF_DURATION_MS,
  CAMERA_GUIDE_REMINDER_MS,
  CAMERA_GUIDE_SECONDS,
  useCameraGuidePrompt,
} from "./useCameraGuidePrompt";
import type { CameraAvailability } from "./useAttentionDetection";

const DURATION_MS = CAMERA_GUIDE_SECONDS * 1000;

function renderCameraGuide(initial: CameraAvailability, sessionId = "s1") {
  return renderHook(({ camera }) => useCameraGuidePrompt({ sessionId, camera }), {
    initialProps: { camera: initial },
  });
}

/** 탭이 숨겨진 상태를 흉내 낸다. jsdom 의 visibilityState 는 getter 라 spy 로 덮는다. */
function hideTab() {
  return vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-07-28T09:00:00Z"));
  sessionStorage.clear();
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("useCameraGuidePrompt", () => {
  it("stays quiet while the camera is on", () => {
    const { result } = renderCameraGuide("on");

    act(() => vi.advanceTimersByTime(CAMERA_GUIDE_OFF_DURATION_MS * 2));

    expect(result.current.prompt).toBeNull();
  });

  it("waits a full minute of the camera being off before prompting", () => {
    const { result } = renderCameraGuide("off");

    act(() => vi.advanceTimersByTime(CAMERA_GUIDE_OFF_DURATION_MS - 1));
    expect(result.current.prompt).toBeNull();

    act(() => vi.advanceTimersByTime(1));
    expect(result.current.prompt).toMatchObject({ cause: "disabled", durationMs: DURATION_MS });
  });

  it.each([
    ["denied", "denied"],
    ["muted", "muted"],
    ["off", "disabled"],
  ] as const)("reports %s as the %s cause so the copy can differ", (camera, cause) => {
    const { result } = renderCameraGuide(camera);

    act(() => vi.advanceTimersByTime(CAMERA_GUIDE_OFF_DURATION_MS));

    expect(result.current.prompt?.cause).toBe(cause);
  });

  it("closes at once when the camera comes back", () => {
    const { result, rerender } = renderCameraGuide("off");
    act(() => vi.advanceTimersByTime(CAMERA_GUIDE_OFF_DURATION_MS));
    expect(result.current.prompt).not.toBeNull();

    rerender({ camera: "on" });

    expect(result.current.prompt).toBeNull();
  });

  it("asks again five minutes later when the student says they will enable it", () => {
    const { result } = renderCameraGuide("off");
    act(() => vi.advanceTimersByTime(CAMERA_GUIDE_OFF_DURATION_MS));

    act(() => result.current.answer("WILL_ENABLE"));
    expect(result.current.prompt).toBeNull();

    act(() => vi.advanceTimersByTime(CAMERA_GUIDE_REMINDER_MS - 1));
    expect(result.current.prompt).toBeNull();

    act(() => vi.advanceTimersByTime(1));
    expect(result.current.prompt).not.toBeNull();
  });

  it("treats no answer like a promise to enable and asks again after five minutes", () => {
    const { result } = renderCameraGuide("off");
    act(() => vi.advanceTimersByTime(CAMERA_GUIDE_OFF_DURATION_MS));

    act(() => vi.advanceTimersByTime(DURATION_MS)); // 30초 무응답으로 자동 종료.
    expect(result.current.prompt).toBeNull();

    act(() => vi.advanceTimersByTime(CAMERA_GUIDE_REMINDER_MS));
    expect(result.current.prompt).not.toBeNull();
  });

  it("never asks again for the rest of the class when the student cannot enable it", () => {
    const { result } = renderCameraGuide("off");
    act(() => vi.advanceTimersByTime(CAMERA_GUIDE_OFF_DURATION_MS));

    act(() => result.current.answer("CANNOT_ENABLE"));
    expect(result.current.prompt).toBeNull();

    act(() => vi.advanceTimersByTime(CAMERA_GUIDE_REMINDER_MS * 4));
    expect(result.current.prompt).toBeNull();
  });

  // 새로고침으로 풀리면 "수업이 끝날 때까지"라는 약속이 깨진다(§5.2).
  it("keeps the suppression after a remount, as a page refresh would cause", () => {
    const first = renderCameraGuide("off");
    act(() => vi.advanceTimersByTime(CAMERA_GUIDE_OFF_DURATION_MS));
    act(() => first.result.current.answer("CANNOT_ENABLE"));
    first.unmount();

    const second = renderCameraGuide("off");
    act(() => vi.advanceTimersByTime(CAMERA_GUIDE_OFF_DURATION_MS * 2));

    expect(second.result.current.prompt).toBeNull();
  });

  // presentation 은 저장소 구현이 아니라 계약에만 의존한다.
  it("reads and writes suppression through the injected store", () => {
    const suppressionStore = { isSuppressed: vi.fn().mockReturnValue(false), suppress: vi.fn() };
    const { result } = renderHook(() =>
      useCameraGuidePrompt({ sessionId: "s1", camera: "off", suppressionStore }),
    );

    act(() => vi.advanceTimersByTime(CAMERA_GUIDE_OFF_DURATION_MS));
    act(() => result.current.answer("CANNOT_ENABLE"));

    expect(suppressionStore.isSuppressed).toHaveBeenCalledWith("s1");
    expect(suppressionStore.suppress).toHaveBeenCalledWith("s1");
  });

  it("keeps the suppression scoped to its own class", () => {
    const first = renderCameraGuide("off", "session-a");
    act(() => vi.advanceTimersByTime(CAMERA_GUIDE_OFF_DURATION_MS));
    act(() => first.result.current.answer("CANNOT_ENABLE"));
    first.unmount();

    const other = renderCameraGuide("off", "session-b");
    act(() => vi.advanceTimersByTime(CAMERA_GUIDE_OFF_DURATION_MS));

    expect(other.result.current.prompt).not.toBeNull();
  });

  // 응답이 집계를 바꾸지 않으므로 서버로 보낼 것이 없다(§5.2·§6).
  it("never reaches the network for any answer", () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const { result } = renderCameraGuide("off");

    act(() => vi.advanceTimersByTime(CAMERA_GUIDE_OFF_DURATION_MS));
    act(() => result.current.answer("WILL_ENABLE"));
    act(() => vi.advanceTimersByTime(CAMERA_GUIDE_REMINDER_MS));
    act(() => vi.advanceTimersByTime(DURATION_MS)); // 두 번째는 무응답으로 닫힌다.

    expect(fetchSpy).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  // 판정 파이프라인(75)이 연속 카운터를 0으로 되돌릴 수 있어야 한다(§5).
  it("reports every close, including the one caused by the camera coming back", () => {
    const onClosed = vi.fn();
    const { result, rerender } = renderHook(
      ({ camera }) => useCameraGuidePrompt({ sessionId: "s1", camera, onClosed }),
      { initialProps: { camera: "off" as CameraAvailability } },
    );

    act(() => vi.advanceTimersByTime(CAMERA_GUIDE_OFF_DURATION_MS));
    expect(result.current.prompt).not.toBeNull();

    rerender({ camera: "on" });

    expect(onClosed).toHaveBeenCalledTimes(1);
  });

  it("holds the prompt back while the tab is hidden and shows it once visible again", () => {
    const visibility = hideTab();
    const { result } = renderCameraGuide("off");

    act(() => vi.advanceTimersByTime(CAMERA_GUIDE_OFF_DURATION_MS));
    expect(result.current.prompt).toBeNull();

    visibility.mockReturnValue("visible");
    act(() => document.dispatchEvent(new Event("visibilitychange")));
    act(() => vi.advanceTimersByTime(0));

    expect(result.current.prompt).not.toBeNull();
  });
});
