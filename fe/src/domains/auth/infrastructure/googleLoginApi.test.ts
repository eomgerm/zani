import { afterEach, describe, expect, it, vi } from "vitest";

import { GoogleLoginRequestError, loginWithGoogle } from "./googleLoginApi";

describe("loginWithGoogle", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("posts the ID token and returns the login result", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com/");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            isSuccess: true,
            data: {
              accessToken: "signed-access-token",
              accessTokenExpiresAt: "2026-07-24T05:00:00Z",
              email: "user@example.com",
              displayName: "테스트 사용자",
              profileImageUrl: "https://example.com/pic.png",
              newMember: true,
            },
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );

    await expect(loginWithGoogle("raw-id-token")).resolves.toEqual({
      accessToken: "signed-access-token",
      accessTokenExpiresAt: "2026-07-24T05:00:00Z",
      email: "user@example.com",
      displayName: "테스트 사용자",
      profileImageUrl: "https://example.com/pic.png",
      newMember: true,
    });
    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/auth/login/google",
      expect.objectContaining({
        method: "POST",
        credentials: "include",
        body: JSON.stringify({ idToken: "raw-id-token" }),
      }),
    );
  });

  it("accepts a null profile image URL", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            isSuccess: true,
            data: {
              accessToken: "signed-access-token",
              accessTokenExpiresAt: "2026-07-24T05:00:00Z",
              email: "user@example.com",
              displayName: "테스트 사용자",
              profileImageUrl: null,
              newMember: false,
            },
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );

    await expect(loginWithGoogle("raw-id-token")).resolves.toMatchObject({ profileImageUrl: null });
  });

  it("throws when the server rejects the token", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({ isSuccess: false, code: "AUTH_008", message: "Google ID token is invalid" }),
          { status: 401, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );

    await expect(loginWithGoogle("garbage")).rejects.toBeInstanceOf(GoogleLoginRequestError);
  });

  it("throws when the success envelope has a malformed data shape", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ isSuccess: true, data: { accessToken: "" } }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    await expect(loginWithGoogle("raw-id-token")).rejects.toBeInstanceOf(GoogleLoginRequestError);
  });
});
