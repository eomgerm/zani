import { afterEach, describe, expect, it, vi } from "vitest";

import { CoachPollError, pollCoach } from "./coachPollApi";

const ok = (data: unknown) =>
  new Response(JSON.stringify({ isSuccess: true, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });

const tip = {
  tipType: "CONFUSED_HIGH",
  title: "추가 설명이 필요해요",
  message: "전체 학생의 30%가 현재 내용을 헷갈려 하고 있어요.",
  targetConcept: "클로저",
};

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe("pollCoach", () => {
  it("reads a waiting tip from the session's coach poll endpoint", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com/");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        ok({ triggerId: "t-1", tip, unavailableReason: null, audioUploadRequest: null }),
      ),
    );

    const result = await pollCoach("55");

    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/sessions/55/coach/poll",
      expect.objectContaining({ method: "GET", credentials: "include" }),
    );
    expect(result).toEqual({
      triggerId: "t-1",
      tip,
      unavailableReason: null,
      audioUploadRequest: null,
    });
  });

  it("reports an idle poll as nothing waiting", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(ok({ triggerId: null, tip: null })));

    const result = await pollCoach("55");

    expect(result.triggerId).toBeNull();
    expect(result.tip).toBeNull();
    expect(result.unavailableReason).toBeNull();
  });

  it("keeps the reason so the caller can tell a failure from an idle poll", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(ok({ tip: null, unavailableReason: "TRANSCRIPTION_FAILED" })),
    );

    expect((await pollCoach("55")).unavailableReason).toBe("TRANSCRIPTION_FAILED");
  });

  // 필수 필드가 빠진 팁은 표시하지 않는다(86 요구사항). 통째로 버린다.
  it.each([
    ["tipType", { ...tip, tipType: "SOMETHING_ELSE" }],
    ["title", { ...tip, title: "" }],
    ["message", { ...tip, message: "  " }],
    ["targetConcept", { ...tip, targetConcept: undefined }],
  ])("drops a tip whose %s is missing or invalid", async (_field, broken) => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(ok({ triggerId: "t-1", tip: broken })));

    expect((await pollCoach("55")).tip).toBeNull();
  });

  // 업로드 요청은 85 소유라 형태를 해석하지 않고 그대로 넘긴다.
  it("passes the audio upload request through untouched", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    const audioUploadRequest = { anything: "the server decides" };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(ok({ tip: null, audioUploadRequest })));

    expect((await pollCoach("55")).audioUploadRequest).toEqual(audioUploadRequest);
  });

  it("reports a failed status with the code so the caller can react", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 503 })));

    await expect(pollCoach("55")).rejects.toMatchObject({ status: 503 });
  });

  it("turns a network failure into a poll error rather than leaking it", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));

    await expect(pollCoach("55")).rejects.toBeInstanceOf(CoachPollError);
  });

  it("encodes an opaque session id used in the path", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(ok({ tip: null })));

    await pollCoach("s/55");

    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/sessions/s%2F55/coach/poll",
      expect.anything(),
    );
  });
});
