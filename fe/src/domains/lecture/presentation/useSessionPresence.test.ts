import { renderHook, act } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { PresenceReportError, type PresenceSnapshot } from "../infrastructure/presenceApi";
import { useSessionPresence } from "./useSessionPresence";

const hoisted = vi.hoisted(() => ({
  status: "stable" as string,
  authState: { accessToken: "test-access-token" as string | null },
}));

// presence 보고는 Bearer Access Token 이 필요하다. 인증 컨텍스트 전체를 띄우지 않고 토큰만 흉내 낸다.
vi.mock("@/domains/auth", () => ({
  useAuth: () => hoisted.authState,
}));

vi.mock("./useRoomReconnect", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./useRoomReconnect")>();
  return {
    ...actual,
    useRoomReconnect: () => ({
      connectionState: hoisted.status === "stable" ? "connected" : "connecting",
      status: hoisted.status,
      measurability: hoisted.status === "stable" ? "MEASURABLE" : "UNMEASURABLE",
      rejoinAttempts: 0,
    }),
  };
});

const connectedSnapshot: PresenceSnapshot = { reconnectStatus: "CONNECTED", sessionEnded: false };

const reporter = (snapshot: PresenceSnapshot = connectedSnapshot) =>
  vi.fn().mockResolvedValue(snapshot);

const renderPresence = (report: ReturnType<typeof reporter>) =>
  renderHook(() => useSessionPresence("session-1", { intervalMs: 10_000, report }));

beforeEach(() => {
  hoisted.status = "stable";
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useSessionPresence", () => {
  it("reports a connected heartbeat as soon as the room is joined", async () => {
    const report = reporter();

    const { result } = renderPresence(report);
    await act(async () => vi.advanceTimersByTime(0));

    expect(report).toHaveBeenCalledTimes(1);
    const [sessionId, heartbeat] = report.mock.calls[0];
    expect(sessionId).toBe("session-1");
    expect(heartbeat.connectionState).toBe("CONNECTED");
    expect(typeof heartbeat.heartbeatAt).toBe("string");
    expect(result.current.reconnectStatus).toBe("CONNECTED");
  });

  it("keeps reporting on every interval", async () => {
    const report = reporter();
    renderPresence(report);
    await act(async () => vi.advanceTimersByTime(0));

    await act(async () => vi.advanceTimersByTime(20_000));

    expect(report).toHaveBeenCalledTimes(3);
  });

  it("reports RECONNECTING while LiveKit is recovering the connection", async () => {
    const report = reporter();
    // 먼저 붙어야 그다음 끊김이 "재연결" 이다. 붙은 적 없는 상태는 보고하지 않는다(아래 테스트).
    const { rerender } = renderPresence(report);
    await act(async () => vi.advanceTimersByTime(0));

    hoisted.status = "reconnecting";
    rerender();
    await act(async () => vi.advanceTimersByTime(0));

    expect(report.mock.calls.at(-1)?.[1].connectionState).toBe("RECONNECTING");
  });

  /**
   * 강의실에 들어온 직후 LiveKit 핸드셰이크가 끝나기 전에는 상태가 "reconnecting" 이다. 이걸 보고하면
   * 서버가 강사 이탈로 보고 5분 유예를 시작해, 수업을 만든 직후 "강사 연결이 끊겼습니다" 가 잠깐 뜬다.
   */
  it("첫 연결이 끝나기 전에는 보고하지 않는다", async () => {
    hoisted.status = "reconnecting";
    const report = reporter();

    renderPresence(report);
    await act(async () => vi.advanceTimersByTime(30_000));

    expect(report).not.toHaveBeenCalled();
  });

  it("reports DISCONNECTED once rejoining has given up", async () => {
    const report = reporter();
    const { rerender } = renderPresence(report);
    await act(async () => vi.advanceTimersByTime(0));

    hoisted.status = "failed";
    rerender();
    await act(async () => vi.advanceTimersByTime(0));

    expect(report.mock.calls.at(-1)?.[1].connectionState).toBe("DISCONNECTED");
  });

  it("surfaces the grace period signal from the server", async () => {
    const report = reporter({ reconnectStatus: "GRACE_PERIOD", sessionEnded: false });

    const { result } = renderPresence(report);
    await act(async () => vi.advanceTimersByTime(0));

    expect(result.current.reconnectStatus).toBe("GRACE_PERIOD");
    expect(result.current.sessionEnded).toBe(false);
  });

  it("stops reporting once the server ends the session", async () => {
    const report = reporter({ reconnectStatus: "SESSION_ENDED", sessionEnded: true });

    const { result } = renderPresence(report);
    await act(async () => vi.advanceTimersByTime(0));
    expect(result.current.sessionEnded).toBe(true);

    await act(async () => vi.advanceTimersByTime(30_000));

    expect(report).toHaveBeenCalledTimes(1);
  });

  it("stops reporting when the participant is not a member", async () => {
    const report = vi.fn().mockRejectedValue(new PresenceReportError("forbidden", 403));

    const { result } = renderPresence(report);
    await act(async () => vi.advanceTimersByTime(0));
    await act(async () => vi.advanceTimersByTime(30_000));

    expect(result.current.error).not.toBeNull();
    expect(report).toHaveBeenCalledTimes(1);
    // 멤버십 거절은 방이 끝난 것이 아니다 — 종료 신호로 오해해 내보내면 안 된다.
    expect(result.current.sessionEnded).toBe(false);
  });

  it("stops reporting when the room is already closed", async () => {
    const report = vi.fn().mockRejectedValue(new PresenceReportError("conflict", 409));

    const { result } = renderPresence(report);
    await act(async () => vi.advanceTimersByTime(0));

    expect(result.current.error).not.toBeNull();
    // 409 는 이미 종료된 방 — 화면이 남은 참가자를 내보낼 수 있게 종료 신호로도 올린다.
    expect(result.current.sessionEnded).toBe(true);
  });

  it("retries after a transient failure", async () => {
    const report = vi
      .fn()
      .mockRejectedValueOnce(new PresenceReportError("server error", 500))
      .mockResolvedValue(connectedSnapshot);

    const { result } = renderPresence(report);
    await act(async () => vi.advanceTimersByTime(0));
    expect(result.current.error).toBeNull();

    await act(async () => vi.advanceTimersByTime(10_000));

    expect(report).toHaveBeenCalledTimes(2);
    expect(result.current.reconnectStatus).toBe("CONNECTED");
  });

  it("stops the heartbeat timer after unmount", async () => {
    const report = reporter();
    const { unmount } = renderPresence(report);
    await act(async () => vi.advanceTimersByTime(0));

    unmount();
    await act(async () => vi.advanceTimersByTime(30_000));

    expect(report).toHaveBeenCalledTimes(1);
  });
});
