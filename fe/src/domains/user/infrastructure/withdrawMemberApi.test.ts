import { afterEach, describe, expect, it, vi } from "vitest";

import { WithdrawMemberRequestError, withdrawMember } from "./withdrawMemberApi";

describe("withdrawMember", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("deletes the member with a bearer access token", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com/");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ isSuccess: true, data: null }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    await expect(withdrawMember("signed-access-token")).resolves.toBeUndefined();
    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/members/me",
      expect.objectContaining({
        method: "DELETE",
        credentials: "include",
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

    await expect(withdrawMember("bad-token")).rejects.toBeInstanceOf(WithdrawMemberRequestError);
  });

  it("throws when the member is already withdrawn", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({ isSuccess: false, code: "MEMBER_APP_002", message: "not found" }),
          { status: 404, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );

    await expect(withdrawMember("token")).rejects.toMatchObject({ status: 404 });
  });
});
