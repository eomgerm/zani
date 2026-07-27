import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { EndSessionRequestError } from "../../../infrastructure/endSessionApi";
import { EndSessionButton } from "./EndSessionButton";

const push = vi.fn();
const auth = vi.hoisted(() => ({ accessToken: "test-access-token" as string | null }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push }),
}));

vi.mock("@/domains/auth", () => ({
  useAuth: () => ({ accessToken: auth.accessToken }),
}));

beforeEach(() => {
  push.mockReset();
  auth.accessToken = "test-access-token";
});

describe("EndSessionButton", () => {
  it("asks for confirmation before ending the class", () => {
    const endSessionRequest = vi.fn();
    render(<EndSessionButton sessionId="123" endSessionRequest={endSessionRequest} />);

    fireEvent.click(screen.getByTestId("end-session-button"));

    expect(screen.getByTestId("end-session-confirm")).toBeVisible();
    expect(endSessionRequest).not.toHaveBeenCalled();
  });

  it("does not end the class when the instructor cancels", () => {
    const endSessionRequest = vi.fn();
    render(<EndSessionButton sessionId="123" endSessionRequest={endSessionRequest} />);

    fireEvent.click(screen.getByTestId("end-session-button"));
    fireEvent.click(screen.getByTestId("end-session-cancel"));

    expect(screen.getByTestId("end-session-button")).toBeVisible();
    expect(endSessionRequest).not.toHaveBeenCalled();
  });

  it("ends the class and leaves the room once confirmed", async () => {
    const endSessionRequest = vi
      .fn()
      .mockResolvedValue({ sessionId: 123, status: "ENDED", ended: true });
    render(<EndSessionButton sessionId="123" endSessionRequest={endSessionRequest} />);

    fireEvent.click(screen.getByTestId("end-session-button"));
    fireEvent.click(screen.getByTestId("end-session-confirm"));

    await waitFor(() => expect(push).toHaveBeenCalledWith("/home"));
    expect(endSessionRequest).toHaveBeenCalledWith("123", "test-access-token");
  });

  it("asks the instructor to sign in again when the session token is gone", async () => {
    auth.accessToken = null;
    const endSessionRequest = vi.fn();
    render(<EndSessionButton sessionId="123" endSessionRequest={endSessionRequest} />);

    fireEvent.click(screen.getByTestId("end-session-button"));
    fireEvent.click(screen.getByTestId("end-session-confirm"));

    expect((await screen.findByTestId("end-session-error")).textContent).toContain("로그인");
    expect(endSessionRequest).not.toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
  });

  it("sends only one request when the confirm button is clicked twice", async () => {
    const endSessionRequest = vi
      .fn()
      .mockResolvedValue({ sessionId: 123, status: "ENDED", ended: true });
    render(<EndSessionButton sessionId="123" endSessionRequest={endSessionRequest} />);

    fireEvent.click(screen.getByTestId("end-session-button"));
    fireEvent.click(screen.getByTestId("end-session-confirm"));
    fireEvent.click(screen.getByTestId("end-session-confirm"));

    await waitFor(() => expect(push).toHaveBeenCalled());
    expect(endSessionRequest).toHaveBeenCalledTimes(1);
  });

  it("explains that only the host instructor may end the class", async () => {
    const endSessionRequest = vi
      .fn()
      .mockRejectedValue(new EndSessionRequestError("forbidden", 403));
    render(<EndSessionButton sessionId="123" endSessionRequest={endSessionRequest} />);

    fireEvent.click(screen.getByTestId("end-session-button"));
    fireEvent.click(screen.getByTestId("end-session-confirm"));

    expect((await screen.findByTestId("end-session-error")).textContent).toContain("강사만");
    expect(push).not.toHaveBeenCalled();
  });

  it("stays in the room and allows a retry when the request fails", async () => {
    const endSessionRequest = vi.fn().mockRejectedValue(new Error("network down"));
    render(<EndSessionButton sessionId="123" endSessionRequest={endSessionRequest} />);

    fireEvent.click(screen.getByTestId("end-session-button"));
    fireEvent.click(screen.getByTestId("end-session-confirm"));

    expect(await screen.findByTestId("end-session-error")).toBeVisible();
    expect(screen.getByTestId("end-session-button")).toBeVisible();
    expect(push).not.toHaveBeenCalled();
  });
});
