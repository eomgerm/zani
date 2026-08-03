import { renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const auth = vi.hoisted(() => ({ accessToken: "test-access-token" as string | null }));
vi.mock("@/domains/auth", () => ({ useAuth: () => auth }));

import { useAttentionTimeline } from "./useAttentionTimeline";
import { AttentionTimelineError } from "../infrastructure/attentionTimelineApi";

beforeEach(() => {
  auth.accessToken = "test-access-token";
});

describe("useAttentionTimeline", () => {
  it("fetches once and exposes the timeline", async () => {
    const request = vi.fn().mockResolvedValue({ points: [] });

    const { result } = renderHook(() =>
      useAttentionTimeline({ sessionId: "s1", enabled: true, request }),
    );

    await waitFor(() => expect(result.current.status).toBe("ready"));
    expect(request).toHaveBeenCalledTimes(1);
  });

  it("does not fetch until it is enabled", () => {
    const request = vi.fn();

    renderHook(() => useAttentionTimeline({ sessionId: "s1", enabled: false, request }));

    // 역할을 아직 모르면 어느 엔드포인트를 부를지도 모른다. 부르면 403 이 난다.
    expect(request).not.toHaveBeenCalled();
  });

  it("maps 403 to forbidden and 409 to live", async () => {
    const forbidden = renderHook(() =>
      useAttentionTimeline({
        sessionId: "s1",
        enabled: true,
        request: vi.fn().mockRejectedValue(new AttentionTimelineError("nope", 403)),
      }),
    );
    await waitFor(() => expect(forbidden.result.current.status).toBe("forbidden"));

    const live = renderHook(() =>
      useAttentionTimeline({
        sessionId: "s1",
        enabled: true,
        request: vi.fn().mockRejectedValue(new AttentionTimelineError("live", 409)),
      }),
    );
    await waitFor(() => expect(live.result.current.status).toBe("live"));
  });

  it("retries after a failure", async () => {
    const request = vi
      .fn()
      .mockRejectedValueOnce(new AttentionTimelineError("boom", 0))
      .mockResolvedValueOnce({ points: [] });

    const { result } = renderHook(() =>
      useAttentionTimeline({ sessionId: "s1", enabled: true, request }),
    );

    await waitFor(() => expect(result.current.status).toBe("failed"));
    result.current.retry();
    await waitFor(() => expect(result.current.status).toBe("ready"));
  });

  it("aborts the in-flight request when it unmounts", async () => {
    const request = vi.fn().mockImplementation(
      (_id, _token, signal?: AbortSignal) =>
        new Promise((_resolve, reject) => {
          signal?.addEventListener("abort", () => reject(new Error("aborted")));
        }),
    );

    const { unmount } = renderHook(() =>
      useAttentionTimeline({ sessionId: "s1", enabled: true, request }),
    );
    unmount();

    await waitFor(() => expect(request.mock.calls[0][2]?.aborted).toBe(true));
  });
});
