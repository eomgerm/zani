import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const roomConnection = vi.hoisted(() => ({
  connectionState: "connecting" as "connecting" | "connected" | "error",
  error: null as string | null,
  sessionExpiresAt: null as string | null,
  retry: vi.fn(),
}));

vi.mock("./RoomProvider", () => ({
  RoomProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useRoomConnection: () => roomConnection,
}));

// 강사 종료 버튼이 App Router와 인증 컨텍스트를 쓰므로, 둘 다 없는 단위 테스트에서는 대체한다.
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

vi.mock("@/domains/auth", () => ({
  useAuth: () => ({ accessToken: "test-access-token" }),
}));

import { RoomScreen } from "./RoomScreen";

afterEach(() => {
  cleanup();
  roomConnection.retry.mockClear();
  roomConnection.sessionExpiresAt = null;
  vi.useRealTimers();
});

describe("RoomScreen connection status", () => {
  it("shows 연결 중 while establishing the room connection", () => {
    roomConnection.connectionState = "connecting";
    roomConnection.error = null;

    render(<RoomScreen sessionId="123" />);

    expect(screen.getByText("연결 중")).toBeVisible();
    expect(screen.getByRole("status")).toHaveAttribute("aria-live", "polite");
  });

  it("shows LIVE after the room connects", () => {
    roomConnection.connectionState = "connected";
    roomConnection.error = null;

    render(<RoomScreen sessionId="123" />);

    expect(screen.getByText("LIVE")).toBeVisible();
  });

  it("shows safe error copy and retries without exposing provider details", () => {
    roomConnection.connectionState = "error";
    roomConnection.error = "Internal token service detail";

    render(<RoomScreen sessionId="123" />);

    expect(screen.getByText("연결 실패")).toBeVisible();
    expect(screen.getByRole("alert")).toHaveTextContent("실시간 강의 연결에 실패했습니다.");
    expect(screen.queryByText("Internal token service detail")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "다시 연결" }));

    expect(roomConnection.retry).toHaveBeenCalledTimes(1);
  });
});

describe("RoomScreen maximum duration warning", () => {
  it("warns with the auto-end time the server sent on entry", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-07-26T12:00:00Z"));
    roomConnection.connectionState = "connected";
    roomConnection.error = null;
    // 서버(미디어 토큰 응답)가 알려준 자동 종료 예정 시각 — 5분 뒤
    roomConnection.sessionExpiresAt = "2026-07-26T12:05:00Z";

    render(<RoomScreen sessionId="123" />);
    await act(async () => vi.advanceTimersByTime(0));

    expect(screen.getByText(/최대 수업 시간까지/)).toBeVisible();
  });

  it("stays quiet when the server has not reported an auto-end time yet", async () => {
    vi.useFakeTimers();
    roomConnection.connectionState = "connecting";
    roomConnection.error = null;
    roomConnection.sessionExpiresAt = null;

    render(<RoomScreen sessionId="123" />);
    await act(async () => vi.advanceTimersByTime(0));

    expect(screen.queryByText(/최대 수업 시간까지/)).toBeNull();
  });
});
