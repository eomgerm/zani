import { afterEach, describe, expect, it, vi } from "vitest";

import { UpdateDisplayNameRequestError, updateDisplayName } from "./updateDisplayNameApi";

describe("updateDisplayName", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("patches the display name with a bearer access token", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com/");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ isSuccess: true, data: { displayName: "바뀐 이름" } }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    await expect(updateDisplayName("signed-access-token", "바뀐 이름")).resolves.toEqual({
      displayName: "바뀐 이름",
    });
    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/members/me/display-name",
      expect.objectContaining({
        method: "PATCH",
        credentials: "include",
        body: JSON.stringify({ displayName: "바뀐 이름" }),
        headers: expect.objectContaining({ Authorization: "Bearer signed-access-token" }),
      }),
    );
  });

  it("throws when the access token is rejected", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({ isSuccess: false, code: "COMM_401", message: "unauthorized" }),
          { status: 401, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );

    await expect(updateDisplayName("bad-token", "새 이름")).rejects.toBeInstanceOf(
      UpdateDisplayNameRequestError,
    );
  });

  it("throws when the success envelope has a malformed data shape", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ isSuccess: true, data: { displayName: 42 } }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    await expect(updateDisplayName("token", "새 이름")).rejects.toBeInstanceOf(
      UpdateDisplayNameRequestError,
    );
  });
});
