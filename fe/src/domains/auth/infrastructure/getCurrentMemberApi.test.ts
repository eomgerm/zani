import { afterEach, describe, expect, it, vi } from "vitest";

import { GetCurrentMemberRequestError, getCurrentMember } from "./getCurrentMemberApi";

describe("getCurrentMember", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("requests the current member using a bearer access token", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com/");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            isSuccess: true,
            data: {
              email: "user@example.com",
              displayName: "테스트 사용자",
              profileImageUrl: "https://example.com/pic.png",
            },
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );

    await expect(getCurrentMember("signed-access-token")).resolves.toEqual({
      email: "user@example.com",
      displayName: "테스트 사용자",
      profileImageUrl: "https://example.com/pic.png",
    });
    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/members/me",
      expect.objectContaining({
        method: "GET",
        credentials: "include",
        headers: expect.objectContaining({ Authorization: "Bearer signed-access-token" }),
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
            data: { email: "user@example.com", displayName: "테스트 사용자", profileImageUrl: null },
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );

    await expect(getCurrentMember("token")).resolves.toMatchObject({ profileImageUrl: null });
  });

  it("throws when the access token is rejected", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ isSuccess: false, code: "COMM_401", message: "unauthorized" }), {
          status: 401,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    await expect(getCurrentMember("bad-token")).rejects.toBeInstanceOf(GetCurrentMemberRequestError);
  });

  it("throws when the success envelope has a malformed data shape", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ isSuccess: true, data: { email: "" } }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    await expect(getCurrentMember("token")).rejects.toBeInstanceOf(GetCurrentMemberRequestError);
  });
});
