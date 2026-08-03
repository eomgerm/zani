import { afterEach, describe, expect, it, vi } from "vitest";

import { UpdateReportEmailRequestError, updateReportEmail } from "./updateReportEmailApi";

describe("updateReportEmail", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("patches the report email setting with a bearer access token", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com/");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ isSuccess: true, data: { reportEmailEnabled: false } }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    await expect(updateReportEmail("signed-access-token", false)).resolves.toEqual({
      reportEmailEnabled: false,
    });
    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/members/me",
      expect.objectContaining({
        method: "PATCH",
        credentials: "include",
        body: JSON.stringify({ reportEmailEnabled: false }),
        headers: expect.objectContaining({ Authorization: "Bearer signed-access-token" }),
      }),
    );
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

    await expect(updateReportEmail("bad-token", true)).rejects.toBeInstanceOf(
      UpdateReportEmailRequestError,
    );
  });

  it("throws when the success envelope has a malformed data shape", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ isSuccess: true, data: { reportEmailEnabled: "yes" } }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    await expect(updateReportEmail("token", true)).rejects.toBeInstanceOf(
      UpdateReportEmailRequestError,
    );
  });
});
