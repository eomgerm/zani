import { afterEach, describe, expect, it, vi } from "vitest";

import {
  PromptResponseSendError,
  sendPromptResponse,
  type PromptResponsePayload,
} from "./promptResponseApi";

const payload: PromptResponsePayload = {
  kind: "UNDERSTANDING_CHECK",
  answer: "CONFUSED",
  shownAt: "2026-07-28T09:00:00.000Z",
  respondedAt: "2026-07-28T09:00:08.000Z",
};

describe("sendPromptResponse", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("posts the full response payload to the session's prompt responses endpoint", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com/");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));

    await sendPromptResponse("55", "prompt-1", payload);

    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/sessions/55/prompts/prompt-1/responses",
      expect.objectContaining({
        method: "POST",
        credentials: "include",
        body: JSON.stringify(payload),
      }),
    );
  });

  // 서버가 계약 밖 필드를 400 으로 거절하므로 네 값 외에는 실리지 않아야 한다.
  it("sends exactly the four contracted fields", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await sendPromptResponse("55", "prompt-1", payload);

    const sentBody = JSON.parse(fetchMock.mock.calls[0][1].body as string);
    expect(Object.keys(sentBody).sort()).toEqual(["answer", "kind", "respondedAt", "shownAt"]);
  });

  it("carries NON_RESPONSE for a prompt that closed without an answer", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await sendPromptResponse("55", "prompt-1", { ...payload, answer: "NON_RESPONSE" });

    expect(JSON.parse(fetchMock.mock.calls[0][1].body as string).answer).toBe("NON_RESPONSE");
  });

  it("throws PromptResponseSendError when the request fails", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 500 })));

    await expect(sendPromptResponse("55", "prompt-1", payload)).rejects.toBeInstanceOf(
      PromptResponseSendError,
    );
  });

  it("encodes opaque session and prompt IDs used in the request path", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));

    await sendPromptResponse("s/55", "p/1", payload);

    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/sessions/s%2F55/prompts/p%2F1/responses",
      expect.anything(),
    );
  });
});
