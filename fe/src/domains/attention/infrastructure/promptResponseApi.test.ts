import { afterEach, describe, expect, it, vi } from "vitest";

import { PromptResponseSendError, sendPromptResponse } from "./promptResponseApi";

describe("sendPromptResponse", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("posts the response value to the session's prompt responses endpoint", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com/");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));

    await sendPromptResponse("55", "prompt-1", "CONFUSED");

    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/sessions/55/prompts/prompt-1/responses",
      expect.objectContaining({
        method: "POST",
        credentials: "include",
        body: JSON.stringify({ value: "CONFUSED" }),
      }),
    );
  });

  it("throws PromptResponseSendError when the request fails", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 500 })));

    await expect(sendPromptResponse("55", "prompt-1", "UNDERSTOOD")).rejects.toBeInstanceOf(
      PromptResponseSendError,
    );
  });

  it("encodes opaque session and prompt IDs used in the request path", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));

    await sendPromptResponse("s/55", "p/1", "MISSED");

    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/sessions/s%2F55/prompts/p%2F1/responses",
      expect.anything(),
    );
  });
});
