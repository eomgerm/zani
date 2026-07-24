import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/domains/lecture", () => ({
  RoomScreen: ({ sessionId }: { sessionId: string }) => <div>room-session-{sessionId}</div>,
}));

import Page from "./page";

describe("room page", () => {
  it("forwards the dynamic session ID to RoomScreen", async () => {
    render(await Page({ params: Promise.resolve({ sessionId: "123" }) }));

    expect(screen.getByText("room-session-123")).toBeVisible();
  });
});
