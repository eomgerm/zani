import { afterEach, describe, expect, it, vi } from "vitest";

import {
  MediaTokenRequestError,
  requestMediaToken,
} from "./mediaTokenApi";

describe("requestMediaToken", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("requests a media token for the selected session", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com/");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            isSuccess: true,
            data: {
              liveKitUrl: "wss://livekit.example.com",
              accessToken: "signed-token",
              roomName: "session-55",
              participantIdentity: "user-42",
              expiresAt: "2026-07-23T15:00:00Z",
            },
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );

    await expect(requestMediaToken("55")).resolves.toMatchObject({
      roomName: "session-55",
    });
    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/sessions/55/media-token",
      expect.objectContaining({ method: "POST", credentials: "include" }),
    );
  });

  it("rejects a forbidden response", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 403 })));

    await expect(requestMediaToken("55")).rejects.toBeInstanceOf(
      MediaTokenRequestError,
    );
  });

  it("rejects an invalid success envelope", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ isSuccess: true, data: { roomName: "session-55" } })),
      ),
    );

    await expect(requestMediaToken("55")).rejects.toBeInstanceOf(
      MediaTokenRequestError,
    );
  });
});
