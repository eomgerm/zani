import { afterEach, describe, expect, it, vi } from "vitest";

const ACCESS_TOKEN = "test-access-token";

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
              sessionExpiresAt: "2026-07-24T15:00:00Z",
              expiresAt: "2026-07-23T15:00:00Z",
            },
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );

    await expect(requestMediaToken("55", ACCESS_TOKEN)).resolves.toMatchObject({
      roomName: "session-55",
    });
    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/sessions/55/media-token",
      expect.objectContaining({ method: "POST", credentials: "include" }),
    );
  });

  it("encodes an opaque session ID used in the request path", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
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
              sessionExpiresAt: "2026-07-24T15:00:00Z",
              expiresAt: "2026-07-23T15:00:00Z",
            },
          }),
        ),
      ),
    );

    await requestMediaToken("course/55?role=student", ACCESS_TOKEN);

    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/sessions/course%2F55%3Frole%3Dstudent/media-token",
      expect.anything(),
    );
  });

  it("rejects a forbidden response", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 403 })));

    await expect(requestMediaToken("55", ACCESS_TOKEN)).rejects.toBeInstanceOf(
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

    await expect(requestMediaToken("55", ACCESS_TOKEN)).rejects.toBeInstanceOf(
      MediaTokenRequestError,
    );
  });

  it.each([
    ["liveKitUrl", ""],
    ["liveKitUrl", "  "],
    ["accessToken", ""],
    ["accessToken", "  "],
    ["roomName", ""],
    ["roomName", "  "],
    ["sessionExpiresAt", ""],
    ["participantIdentity", ""],
    ["participantIdentity", "  "],
    ["expiresAt", ""],
    ["expiresAt", "  "],
  ])("rejects a success envelope with blank %s %j", async (field, blankValue) => {
    const data = {
      liveKitUrl: "wss://livekit.example.com",
      accessToken: "signed-token",
      roomName: "session-55",
      participantIdentity: "user-42",
      sessionExpiresAt: "2026-07-24T15:00:00Z",
      expiresAt: "2026-07-23T15:00:00Z",
      [field]: blankValue,
    };
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ isSuccess: true, data })),
      ),
    );

    await expect(requestMediaToken("55", ACCESS_TOKEN)).rejects.toBeInstanceOf(
      MediaTokenRequestError,
    );
  });
});
