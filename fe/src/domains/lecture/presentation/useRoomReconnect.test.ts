import { renderHook, act } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useRoomReconnect } from "./useRoomReconnect";

const hoisted = vi.hoisted(() => ({
  connectionState: "connecting" as "connecting" | "connected" | "error",
  retry: vi.fn(),
}));

vi.mock("./RoomProvider", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./RoomProvider")>();
  return {
    ...actual,
    useRoomConnection: () => ({
      room: null,
      connectionState: hoisted.connectionState,
      error: null,
      retry: hoisted.retry,
    }),
  };
});

const setState = (state: "connecting" | "connected" | "error") => {
  hoisted.connectionState = state;
};

beforeEach(() => {
  hoisted.connectionState = "connecting";
  hoisted.retry.mockClear();
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useRoomReconnect", () => {
  it("reports stable and MEASURABLE while connected", () => {
    setState("connected");
    const { result } = renderHook(() => useRoomReconnect());

    expect(result.current.status).toBe("stable");
    expect(result.current.measurability).toBe("MEASURABLE");
  });

  it("reports reconnecting and UNMEASURABLE while connecting", () => {
    const { result } = renderHook(() => useRoomReconnect());

    expect(result.current.status).toBe("reconnecting");
    expect(result.current.measurability).toBe("UNMEASURABLE");
  });

  it("rejoins after each delay on error and stops at failed once the limit is exceeded", () => {
    setState("error");
    const { result } = renderHook(() =>
      useRoomReconnect({ rejoinDelayMs: 1000, maxRejoinAttempts: 2 }),
    );

    expect(result.current.status).toBe("rejoining");
    expect(hoisted.retry).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(hoisted.retry).toHaveBeenCalledTimes(1);
    expect(result.current.rejoinAttempts).toBe(1);

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(hoisted.retry).toHaveBeenCalledTimes(2);
    expect(result.current.rejoinAttempts).toBe(2);
    expect(result.current.status).toBe("failed");

    act(() => {
      vi.advanceTimersByTime(5000);
    });
    expect(hoisted.retry).toHaveBeenCalledTimes(2);
  });

  it("notifies onMeasurabilityChange only when measurability actually changes", () => {
    const onMeasurabilityChange = vi.fn();
    const { rerender } = renderHook(() => useRoomReconnect({ onMeasurabilityChange }));

    expect(onMeasurabilityChange).toHaveBeenNthCalledWith(1, "UNMEASURABLE");

    act(() => {
      setState("connected");
    });
    rerender();
    expect(onMeasurabilityChange).toHaveBeenNthCalledWith(2, "MEASURABLE");

    rerender();
    expect(onMeasurabilityChange).toHaveBeenCalledTimes(2);

    act(() => {
      setState("error");
    });
    rerender();
    expect(onMeasurabilityChange).toHaveBeenNthCalledWith(3, "UNMEASURABLE");
  });
});
