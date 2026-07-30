import { renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useStartOnConnected } from "./useStartOnConnected";

const { authState } = vi.hoisted(() => ({
  authState: { accessToken: "access-token" as string | null },
}));

vi.mock("@/domains/auth", () => ({
  useAuth: () => authState,
}));

const started = {
  sessionId: "100",
  status: "LIVE",
  inviteCode: "GPH7GQ5Q",
  expiresAt: "2026-07-30T12:00:00Z",
  started: true,
};

type Options = {
  sessionStatus?: string | null;
  connected?: boolean;
  isInstructor?: boolean;
  startSession?: ReturnType<typeof vi.fn>;
};

const render = ({
  sessionStatus = "PREPARING",
  connected = true,
  isInstructor = true,
  startSession = vi.fn().mockResolvedValue(started),
}: Options = {}) => {
  const view = renderHook(() =>
    useStartOnConnected({
      sessionId: "100",
      sessionStatus,
      connected,
      isInstructor,
      startSession,
    }),
  );
  return { ...view, startSession };
};

beforeEach(() => {
  authState.accessToken = "access-token";
  vi.spyOn(console, "warn").mockImplementation(() => {});
});

afterEach(() => {
  vi.restoreAllMocks();
});

/**
 * 수업은 강사가 실제로 강의실에 연결된 뒤 시작한다. 생성 화면에서 시작하면 아직 연결되지 않은 상태로 초대 코드가 열려, 연결이 실패했을 때 학생만 강사 없는 방에 들어온다.
 */
describe("useStartOnConnected", () => {
  it("강사가 연결되면 준비 중인 수업을 시작한다", async () => {
    const { startSession, result } = render();

    await waitFor(() => expect(result.current.started).toBe(true));
    expect(startSession).toHaveBeenCalledWith("100", "access-token");
  });

  /** 연결 전에 시작하면 초대 코드가 먼저 열려 이 변경의 목적이 사라진다. */
  it("아직 연결되지 않았으면 시작하지 않는다", () => {
    const { startSession } = render({ connected: false });

    expect(startSession).not.toHaveBeenCalled();
  });

  /** 학생 화면이 시작을 부르면 강사가 준비를 마치기 전에 수업이 열린다. */
  it("학생 화면에서는 시작하지 않는다", () => {
    const { startSession } = render({ isInstructor: false });

    expect(startSession).not.toHaveBeenCalled();
  });

  it("이미 진행 중인 수업에는 다시 시작을 보내지 않는다", () => {
    const { startSession } = render({ sessionStatus: "LIVE" });

    expect(startSession).not.toHaveBeenCalled();
  });

  /** 서버가 상태를 내려주지 않는 구성에서는 부르지 않는다 — 이미 시작된 수업에 다시 부르는 것보다 안전하다. */
  it("상태를 모르면 시작하지 않는다", () => {
    const { startSession } = render({ sessionStatus: null });

    expect(startSession).not.toHaveBeenCalled();
  });

  it("로그인 토큰이 없으면 시작하지 않는다", () => {
    authState.accessToken = null;
    const { startSession } = render();

    expect(startSession).not.toHaveBeenCalled();
  });

  /** 재연결마다 요청을 보낼 이유가 없다. 서버도 멱등하지만 불필요한 호출을 남기지 않는다. */
  it("성공한 뒤에는 다시 보내지 않는다", async () => {
    const { startSession, result, rerender } = render();
    await waitFor(() => expect(result.current.started).toBe(true));

    rerender();
    rerender();

    expect(startSession).toHaveBeenCalledTimes(1);
  });

  /** 실패는 화면에 띄우지 않는다. 강사는 이미 방 안에 있고 수업을 진행할 수 있다 — 다만 학생이 못 들어오므로 원인은 남긴다. */
  it("실패하면 원인만 남기고 시작 표시를 켜지 않는다", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const { result } = render({ startSession: vi.fn().mockRejectedValue(new Error("boom")) });

    await waitFor(() => expect(warn).toHaveBeenCalled());
    expect(result.current.started).toBe(false);
  });
});
