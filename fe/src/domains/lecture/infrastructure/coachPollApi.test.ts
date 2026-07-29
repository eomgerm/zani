import { afterEach, describe, expect, it, vi } from "vitest";

import { CoachPollError, pollCoach } from "./coachPollApi";

const ok = (data: unknown) =>
  new Response(JSON.stringify({ isSuccess: true, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });

const tip = {
  tipType: "CONFUSED",
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
      vi.fn().mockResolvedValue(ok({ triggerId: "t-1", tip, unavailableReason: null })),
    );

    const result = await pollCoach("55", "test-access-token");

    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/sessions/55/coaching-tip",
      expect.objectContaining({ method: "GET", credentials: "include" }),
    );
    expect(result).toEqual({ triggerId: "t-1", tip, unavailableReason: null });
  });

  it("reports an idle poll as nothing waiting", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(ok({ triggerId: null, tip: null })));

    const result = await pollCoach("55", "test-access-token");

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

    expect((await pollCoach("55", "test-access-token")).unavailableReason).toBe("TRANSCRIPTION_FAILED");
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

    expect((await pollCoach("55", "test-access-token")).tip).toBeNull();
  });

  // 계약에 없는 값이 늘어도 무시한다. 85 가 나중에 필드를 더해도 이 어댑터는 흔들리지 않는다.
  it("ignores fields the contract does not define", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(ok({ tip: null, somethingAddedLater: { any: "shape" } })),
    );

    expect(await pollCoach("55", "test-access-token")).toEqual({
      triggerId: null,
      tip: null,
      unavailableReason: null,
    });
  });

  // 쿠키는 refresh 전용이라 API 인증 경로가 아니다. Bearer 가 없으면 서버가 401 을 준다.
  it("sends the access token as a bearer header", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    const fetchMock = vi.fn().mockResolvedValue(ok({ tip: null }));
    vi.stubGlobal("fetch", fetchMock);

    await pollCoach("55", "test-access-token");

    expect(fetchMock.mock.calls[0][1].headers).toMatchObject({
      Authorization: "Bearer test-access-token",
    });
  });

  // 85 컨트롤러가 "클라이언트는 폴링을 멈춘다" 로 못박은 상태들이다.
  it.each([403, 409])("marks %i as a reason to stop polling", async (status) => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status })));

    await expect(pollCoach("55", "test-access-token")).rejects.toMatchObject({
      shouldStopPolling: true,
    });
  });

  // 401·404·503 은 상황이 달라질 수 있어 계속 조회한다.
  it.each([401, 404, 503])("keeps polling after %i", async (status) => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status })));

    await expect(pollCoach("55", "test-access-token")).rejects.toMatchObject({
      shouldStopPolling: false,
    });
  });

  it("reports a failed status with the code so the caller can react", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 503 })));

    await expect(pollCoach("55", "test-access-token")).rejects.toMatchObject({ status: 503 });
  });

  it("turns a network failure into a poll error rather than leaking it", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));

    await expect(pollCoach("55", "test-access-token")).rejects.toBeInstanceOf(CoachPollError);
  });

  it("encodes an opaque session id used in the path", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(ok({ tip: null })));

    await pollCoach("s/55", "test-access-token");

    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/sessions/s%2F55/coaching-tip",
      expect.anything(),
    );
  });
});
