import { render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { GoogleLoginResult } from "../infrastructure/googleLoginApi";
import type { CurrentMember } from "../infrastructure/getCurrentMemberApi";
import { AuthProvider, useAuth } from "./AuthProvider";

function Probe({ idTokenToSubmit }: { idTokenToSubmit?: string }) {
  const { accessToken, member, isAuthenticated, isInitializing, loginWithGoogle, logout } = useAuth();
  return (
    <>
      <output data-testid="access-token">{accessToken ?? ""}</output>
      <output data-testid="is-authenticated">{String(isAuthenticated)}</output>
      <output data-testid="is-initializing">{String(isInitializing)}</output>
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

const restoredMember: CurrentMember = {
  email: "restored@example.com",
  displayName: "복원된 사용자",
  profileImageUrl: null,
};

// 세션 복원 부트 effect가 기본으로 실행되므로, 복원 자체를 테스트하지 않는 케이스에서는
// 항상 거부되는 스텁을 명시해 실제 fetch 호출·불필요한 act 경고를 피한다.
const noSession = () => vi.fn().mockRejectedValue(new Error("no session"));

describe("AuthProvider", () => {
  it("starts initializing, then settles unauthenticated once restore fails", async () => {
    render(
      <AuthProvider requestGoogleLoginFn={vi.fn()} requestRefreshSessionFn={noSession()}>
        <Probe />
      </AuthProvider>,
    );

    expect(screen.getByTestId("is-initializing")).toHaveTextContent("true");

    await waitFor(() => {
      expect(screen.getByTestId("is-initializing")).toHaveTextContent("false");
    });
    expect(screen.getByTestId("is-authenticated")).toHaveTextContent("false");
    expect(screen.getByTestId("access-token")).toHaveTextContent("");
    expect(screen.getByTestId("display-name")).toHaveTextContent("");
  });

  it("restores the session on boot when a valid refresh cookie exists", async () => {
    const requestRefreshSessionFn = vi
      .fn()
      .mockResolvedValue({ accessToken: "restored-access-token", accessTokenExpiresAt: "2026-07-24T06:00:00Z" });
    const requestCurrentMemberFn = vi.fn().mockResolvedValue(restoredMember);

    render(
      <AuthProvider
        requestGoogleLoginFn={vi.fn()}
        requestRefreshSessionFn={requestRefreshSessionFn}
        requestCurrentMemberFn={requestCurrentMemberFn}
      >
        <Probe />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByTestId("is-authenticated")).toHaveTextContent("true");
    });
    expect(screen.getByTestId("access-token")).toHaveTextContent("restored-access-token");
    expect(screen.getByTestId("display-name")).toHaveTextContent("복원된 사용자");
    expect(requestCurrentMemberFn).toHaveBeenCalledWith("restored-access-token");
  });

  it("stores the access token and member profile after a successful Google login", async () => {
    const requestGoogleLoginFn = vi.fn().mockResolvedValue(loginResult);

    render(
      <AuthProvider requestGoogleLoginFn={requestGoogleLoginFn} requestRefreshSessionFn={noSession()}>
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

  it("clears the access token and member profile on logout and tells the server", async () => {
    const requestGoogleLoginFn = vi.fn().mockResolvedValue(loginResult);
    const requestLogoutFn = vi.fn().mockResolvedValue(undefined);

    render(
      <AuthProvider
        requestGoogleLoginFn={requestGoogleLoginFn}
        requestRefreshSessionFn={noSession()}
        requestLogoutFn={requestLogoutFn}
      >
        <Probe idTokenToSubmit="raw-id-token" />
      </AuthProvider>,
    );

    screen.getByRole("button", { name: "login" }).click();
    await waitFor(() => {
      expect(screen.getByTestId("is-authenticated")).toHaveTextContent("true");
    });

    screen.getByRole("button", { name: "logout" }).click();

    // 로컬 상태는 서버 응답을 기다리지 않고 지운다.
    await waitFor(() => {
      expect(screen.getByTestId("is-authenticated")).toHaveTextContent("false");
    });
    expect(screen.getByTestId("access-token")).toHaveTextContent("");
    expect(screen.getByTestId("display-name")).toHaveTextContent("");
    await waitFor(() => {
      expect(requestLogoutFn).toHaveBeenCalledTimes(1);
    });
  });

  it("does not throw or restore state when the server logout call fails", async () => {
    const requestGoogleLoginFn = vi.fn().mockResolvedValue(loginResult);
    const requestLogoutFn = vi.fn().mockRejectedValue(new Error("network down"));

    render(
      <AuthProvider
        requestGoogleLoginFn={requestGoogleLoginFn}
        requestRefreshSessionFn={noSession()}
        requestLogoutFn={requestLogoutFn}
      >
        <Probe idTokenToSubmit="raw-id-token" />
      </AuthProvider>,
    );

    screen.getByRole("button", { name: "login" }).click();
    await waitFor(() => {
      expect(screen.getByTestId("is-authenticated")).toHaveTextContent("true");
    });

    expect(() => screen.getByRole("button", { name: "logout" }).click()).not.toThrow();

    await waitFor(() => {
      expect(screen.getByTestId("is-authenticated")).toHaveTextContent("false");
    });
  });

  it("throws when useAuth is used outside an AuthProvider", () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});

    expect(() => render(<Probe />)).toThrow("useAuth must be used within an AuthProvider.");

    consoleError.mockRestore();
  });
});
