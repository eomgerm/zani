import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const ACCESS_TOKEN = vi.hoisted(() => "test-access-token");

// 전송에는 Bearer 토큰이 필요하다. 인증 컨텍스트가 없는 단위 테스트에서는 대체한다.
const auth = vi.hoisted(() => ({ accessToken: ACCESS_TOKEN as string | null }));
vi.mock("@/domains/auth", () => ({ useAuth: () => auth }));

import { AttentionEventSendError } from "../infrastructure/attentionEventApi";
import type { DetectorReport } from "../domain/detectionOutcome";
import {
  ATTENTION_EVENT_RETRY_DELAY_MS,
  useAttentionEventReporter,
} from "./useAttentionEventReporter";

const reportAt = (observedAtMs: number, clientEventId: string): DetectorReport => ({
  outcome: "ENGAGED",
  observedAtMs,
  windowStartedAtMs: observedAtMs - 10_000,
  clientEventId,
});

const ACCEPTED = { duplicate: false };

/** 전송을 진행시키고 그 사이에 걸린 재시도 타이머까지 흘려보낸다. */
const flush = async (ms = 0) => {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
};

beforeEach(() => {
  vi.useFakeTimers();
  auth.accessToken = ACCESS_TOKEN;
  vi.spyOn(console, "warn").mockImplementation(() => {});
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("useAttentionEventReporter", () => {
  it("sends each observation to the session it belongs to", async () => {
    const send = vi.fn().mockResolvedValue(ACCEPTED);
    const { result } = renderHook(() => useAttentionEventReporter({ sessionId: "55", send }));

    act(() => result.current(reportAt(10_000, "event-1")));
    await flush();

    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith(
      "55",
      reportAt(10_000, "event-1"),
      ACCESS_TOKEN,
      expect.any(AbortSignal),
    );
  });

  // 서버가 clientEventId 로 중복을 판별하므로 같은 값으로 다시 보내면 duplicate 로 성공한다.
  // 새 값을 만들면 같은 관측이 두 번 반영된다.
  it("retries a network failure with the same client event id", async () => {
    const send = vi
      .fn()
      .mockRejectedValueOnce(new AttentionEventSendError("offline", 0))
      .mockResolvedValue({ duplicate: true });
    const { result } = renderHook(() => useAttentionEventReporter({ sessionId: "55", send }));

    act(() => result.current(reportAt(10_000, "event-1")));
    await flush(ATTENTION_EVENT_RETRY_DELAY_MS);

    expect(send).toHaveBeenCalledTimes(2);
    expect(send.mock.calls[1][1]).toEqual(send.mock.calls[0][1]);
    expect(send.mock.calls[1][1].clientEventId).toBe("event-1");
  });

  // 판정은 10초마다 새 관측을 만든다. 무한 재시도는 밀린 큐만 키운다.
  it("gives up on an observation after one retry", async () => {
    const send = vi.fn().mockRejectedValue(new AttentionEventSendError("offline", 0));
    const { result } = renderHook(() => useAttentionEventReporter({ sessionId: "55", send }));

    act(() => result.current(reportAt(10_000, "event-1")));
    await flush(ATTENTION_EVENT_RETRY_DELAY_MS * 10);

    expect(send).toHaveBeenCalledTimes(2);
  });

  it("retries a server error because the observation may still be accepted", async () => {
    const send = vi
      .fn()
      .mockRejectedValueOnce(new AttentionEventSendError("redis down", 503))
      .mockResolvedValue(ACCEPTED);
    const { result } = renderHook(() => useAttentionEventReporter({ sessionId: "55", send }));

    act(() => result.current(reportAt(10_000, "event-1")));
    await flush(ATTENTION_EVENT_RETRY_DELAY_MS);

    expect(send).toHaveBeenCalledTimes(2);
  });

  // 400·403 은 다시 보내도 같은 답이 온다. 이 관측만 버리고 다음 창은 그대로 시도한다.
  it("drops a rejected observation without retrying it", async () => {
    const send = vi
      .fn()
      .mockRejectedValueOnce(new AttentionEventSendError("bad request", 400))
      .mockResolvedValue(ACCEPTED);
    const { result } = renderHook(() => useAttentionEventReporter({ sessionId: "55", send }));

    act(() => result.current(reportAt(10_000, "event-1")));
    await flush(ATTENTION_EVENT_RETRY_DELAY_MS * 10);
    expect(send).toHaveBeenCalledTimes(1);

    act(() => result.current(reportAt(20_000, "event-2")));
    await flush();
    expect(send).toHaveBeenCalledTimes(2);
  });

  // 종료된 세션은 다음 창에도 종료돼 있다. 계속 보내면 10초마다 409 만 쌓인다.
  it("stops sending for the rest of the session after a 409", async () => {
    const send = vi.fn().mockRejectedValue(new AttentionEventSendError("session ended", 409));
    const { result } = renderHook(() => useAttentionEventReporter({ sessionId: "55", send }));

    act(() => result.current(reportAt(10_000, "event-1")));
    await flush(ATTENTION_EVENT_RETRY_DELAY_MS * 10);
    expect(send).toHaveBeenCalledTimes(1);

    act(() => result.current(reportAt(20_000, "event-2")));
    act(() => result.current(reportAt(30_000, "event-3")));
    await flush(ATTENTION_EVENT_RETRY_DELAY_MS * 10);

    expect(send).toHaveBeenCalledTimes(1);
  });

  // 래치는 그 수업에만 걸린다. 다른 수업에 들어가면 다시 보내야 한다.
  it("sends again after moving to another session", async () => {
    const send = vi.fn().mockRejectedValue(new AttentionEventSendError("session ended", 409));
    const { result, rerender } = renderHook(
      (props: { sessionId: string }) =>
        useAttentionEventReporter({ sessionId: props.sessionId, send }),
      { initialProps: { sessionId: "55" } },
    );

    act(() => result.current(reportAt(10_000, "event-1")));
    await flush();
    expect(send).toHaveBeenCalledTimes(1);

    rerender({ sessionId: "56" });
    act(() => result.current(reportAt(20_000, "event-2")));
    await flush();

    expect(send).toHaveBeenCalledTimes(2);
    expect(send.mock.calls[1][0]).toBe("56");
  });

  // 전송이 판정 루프를 막아서는 안 된다(수업 진행 우선).
  it("never throws out of the report callback", async () => {
    const send = vi.fn().mockRejectedValue(new AttentionEventSendError("offline", 0));
    const { result } = renderHook(() => useAttentionEventReporter({ sessionId: "55", send }));

    expect(() => result.current(reportAt(10_000, "event-1"))).not.toThrow();
    await flush(ATTENTION_EVENT_RETRY_DELAY_MS * 10);
  });

  // 토큰이 아직 없으면 보내도 401 이다. 세션 복원이 끝나면 다음 창이 곧 온다.
  it("holds off while no access token is available", async () => {
    auth.accessToken = null;
    const send = vi.fn().mockResolvedValue(ACCEPTED);
    const { result } = renderHook(() => useAttentionEventReporter({ sessionId: "55", send }));

    act(() => result.current(reportAt(10_000, "event-1")));
    await flush();

    expect(send).not.toHaveBeenCalled();
  });

  it("abandons an in-flight send when the student leaves the room", async () => {
    const send = vi.fn().mockResolvedValue(ACCEPTED);
    const { result, unmount } = renderHook(() =>
      useAttentionEventReporter({ sessionId: "55", send }),
    );

    act(() => result.current(reportAt(10_000, "event-1")));
    const signal = send.mock.calls[0][3] as AbortSignal;
    expect(signal.aborted).toBe(false);

    unmount();

    expect(signal.aborted).toBe(true);
  });

  it("does not retry after the student leaves the room", async () => {
    const send = vi.fn().mockRejectedValue(new AttentionEventSendError("offline", 0));
    const { result, unmount } = renderHook(() =>
      useAttentionEventReporter({ sessionId: "55", send }),
    );

    act(() => result.current(reportAt(10_000, "event-1")));
    await flush();
    unmount();
    await flush(ATTENTION_EVENT_RETRY_DELAY_MS * 10);

    expect(send).toHaveBeenCalledTimes(1);
  });
});
