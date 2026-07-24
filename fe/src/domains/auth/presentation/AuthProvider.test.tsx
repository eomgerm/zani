import { render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { GoogleLoginResult } from "../infrastructure/googleLoginApi";
import { AuthProvider, useAuth } from "./AuthProvider";

function Probe({ idTokenToSubmit }: { idTokenToSubmit?: string }) {
  const { accessToken, member, isAuthenticated, loginWithGoogle, logout } = useAuth();
  return (
    <>
      <output data-testid="access-token">{accessToken ?? ""}</output>
      <output data-testid="is-authenticated">{String(isAuthenticated)}</output>
      <output data-testid="display-name">{member?.displayName ?? ""}</output>
      <button onClick={() => idTokenToSubmit && loginWithGoogle(idTokenToSubmit)}>login</button>
      <button onClick={logout}>logout</button>
    </>
  );
}

const loginResult: GoogleLoginResult = {
  accessToken: "signed-access-token",
  accessTokenExpiresAt: "2026-07-24T05:00:00Z",
  email: "user@example.com",
  displayName: "테스트 사용자",
  profileImageUrl: "https://example.com/pic.png",
  newMember: false,
};

describe("AuthProvider", () => {
  it("starts unauthenticated", () => {
    render(
      <AuthProvider requestGoogleLoginFn={vi.fn()}>
        <Probe />
      </AuthProvider>,
    );

    expect(screen.getByTestId("is-authenticated")).toHaveTextContent("false");
    expect(screen.getByTestId("access-token")).toHaveTextContent("");
    expect(screen.getByTestId("display-name")).toHaveTextContent("");
  });

  it("stores the access token and member profile after a successful Google login", async () => {
    const requestGoogleLoginFn = vi.fn().mockResolvedValue(loginResult);

    render(
      <AuthProvider requestGoogleLoginFn={requestGoogleLoginFn}>
        <Probe idTokenToSubmit="raw-id-token" />
      </AuthProvider>,
    );

    screen.getByRole("button", { name: "login" }).click();

    await waitFor(() => {
      expect(screen.getByTestId("is-authenticated")).toHaveTextContent("true");
    });
    expect(screen.getByTestId("access-token")).toHaveTextContent("signed-access-token");
    expect(screen.getByTestId("display-name")).toHaveTextContent("테스트 사용자");
    expect(requestGoogleLoginFn).toHaveBeenCalledWith("raw-id-token");
  });

  it("clears the access token and member profile on logout", async () => {
    const requestGoogleLoginFn = vi.fn().mockResolvedValue(loginResult);

    render(
      <AuthProvider requestGoogleLoginFn={requestGoogleLoginFn}>
        <Probe idTokenToSubmit="raw-id-token" />
      </AuthProvider>,
    );

    screen.getByRole("button", { name: "login" }).click();
    await waitFor(() => {
      expect(screen.getByTestId("is-authenticated")).toHaveTextContent("true");
    });

    screen.getByRole("button", { name: "logout" }).click();

    await waitFor(() => {
      expect(screen.getByTestId("is-authenticated")).toHaveTextContent("false");
    });
    expect(screen.getByTestId("access-token")).toHaveTextContent("");
    expect(screen.getByTestId("display-name")).toHaveTextContent("");
  });

  it("throws when useAuth is used outside an AuthProvider", () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});

    expect(() => render(<Probe />)).toThrow("useAuth must be used within an AuthProvider.");

    consoleError.mockRestore();
  });
});
