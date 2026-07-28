import { afterEach, describe, expect, it, vi } from "vitest";

import { LogoutRequestError, logout } from "./logoutApi";

describe("logout", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("posts to the logout endpoint with the refresh cookie", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com/");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 200 })));

    await logout();

    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/auth/logout",
      expect.objectContaining({ method: "POST", credentials: "include" }),
    );
  });

  it("throws when the server rejects the request", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 500 })));

    await expect(logout()).rejects.toBeInstanceOf(LogoutRequestError);
  });
});
