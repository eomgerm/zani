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

function renderCameraGuide(initial: CameraAvailability) {
  return renderHook(({ camera }) => useCameraGuidePrompt({ camera }), {
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
