import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { SessionPresenceNotice } from "./SessionPresenceNotice";

describe("SessionPresenceNotice", () => {
  it("renders nothing while the session is healthy", () => {
    render(<SessionPresenceNotice reconnectStatus="CONNECTED" sessionEnded={false} error={null} />);

    expect(screen.queryByRole("status")).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("warns that the class ends automatically while the instructor is away", () => {
    render(
      <SessionPresenceNotice reconnectStatus="GRACE_PERIOD" sessionEnded={false} error={null} />,
    );

    const notice = screen.getByTestId("presence-grace-period");
    expect(notice).toHaveAttribute("aria-live", "polite");
    expect(notice.textContent).toContain("5분");
  });

  it("offers a way out once the session has ended", () => {
    render(
      <SessionPresenceNotice reconnectStatus="SESSION_ENDED" sessionEnded error={null} />,
    );

    expect(screen.getByTestId("presence-session-ended")).toHaveAttribute("role", "alert");
    expect(screen.getByRole("link", { name: "나가기" })).toHaveAttribute("href", "/home");
  });

  it("shows why reporting stopped when the server rejected it", () => {
    render(
      <SessionPresenceNotice
        reconnectStatus={null}
        sessionEnded={false}
        error="이 수업의 참가자가 아니라 상태를 보고할 수 없습니다."
      />,
    );

    expect(screen.getByTestId("presence-error").textContent).toContain("참가자가 아니");
  });

  it("prefers the session end notice over the grace period one", () => {
    render(<SessionPresenceNotice reconnectStatus="GRACE_PERIOD" sessionEnded error={null} />);

    expect(screen.getByTestId("presence-session-ended")).toBeVisible();
    expect(screen.queryByTestId("presence-grace-period")).toBeNull();
  });
});
