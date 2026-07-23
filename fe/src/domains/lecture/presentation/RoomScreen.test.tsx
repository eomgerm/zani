import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const roomConnection = vi.hoisted(() => ({
  connectionState: "connecting" as "connecting" | "connected" | "error",
  error: null as string | null,
  retry: vi.fn(),
}));

vi.mock("./RoomProvider", () => ({
  RoomProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useRoomConnection: () => roomConnection,
}));

import { RoomScreen } from "./RoomScreen";

afterEach(() => {
  cleanup();
  roomConnection.retry.mockClear();
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
