import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

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

  /** 강사 미복귀로 끝난 경우는 이유를 알려줄 수 있다. heartbeat 응답이 그렇게 말해줬기 때문이다. */
  it("names instructor absence as the reason when the server said so", () => {
    render(<SessionPresenceNotice reconnectStatus="SESSION_ENDED" sessionEnded error={null} />);

    const notice = screen.getByTestId("presence-session-ended");
    expect(notice).toHaveAttribute("role", "alert");
    expect(notice.textContent).toContain("강사가 복귀하지 않아");
    expect(notice.textContent).toContain("잠시 후 강의실에서 나갑니다");
  });

  /**
   * 강사가 직접 종료하면 다음 요청이 409 로 막혀 본문이 없다. 왜 끝났는지 모르는 상태이므로
   * 이유를 단정하지 않는다 — "강사가 복귀하지 않아" 라고 쓰면 사실과 다른 안내가 된다.
   */
  it("does not guess a reason when the server only reported that it is over", () => {
    render(<SessionPresenceNotice reconnectStatus="CONNECTED" sessionEnded error={null} />);

    const notice = screen.getByTestId("presence-session-ended");
    expect(notice.textContent).toContain("수업이 종료되었습니다");
    expect(notice.textContent).not.toContain("강사가 복귀하지 않아");
  });

  /** 자동 이동을 기다리지 않으려는 사용자를 위한 출구. */
  it("lets the viewer leave immediately", () => {
    const onLeave = vi.fn();
    render(
      <SessionPresenceNotice
        reconnectStatus={null}
        sessionEnded
        error={null}
        onLeave={onLeave}
      />,
    );

    fireEvent.click(screen.getByTestId("presence-leave-now"));

    expect(onLeave).toHaveBeenCalled();
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
