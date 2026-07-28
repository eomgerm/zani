import { afterEach, describe, expect, it, vi } from "vitest";

import { RefreshSessionRequestError, refreshSession } from "./refreshSessionApi";

describe("refreshSession", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("requests a new access token using the refresh cookie", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com/");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            isSuccess: true,
            data: { accessToken: "new-access-token", accessTokenExpiresAt: "2026-07-24T06:00:00Z" },
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );

    await expect(refreshSession()).resolves.toEqual({
      accessToken: "new-access-token",
      accessTokenExpiresAt: "2026-07-24T06:00:00Z",
    });
    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/auth/refresh",
      expect.objectContaining({ method: "POST", credentials: "include" }),
    );
  });

  it("throws when there is no valid session to restore", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ isSuccess: false, code: "AUTH_002", message: "no session" }), {
          status: 401,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    await expect(refreshSession()).rejects.toBeInstanceOf(RefreshSessionRequestError);
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

    await expect(refreshSession()).rejects.toBeInstanceOf(RefreshSessionRequestError);
  });
});
