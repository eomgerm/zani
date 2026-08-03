import { afterEach, describe, expect, it, vi } from "vitest";

import type { DetectorReport } from "../domain/detectionOutcome";
import { AttentionEventSendError, sendAttentionEvent } from "./attentionEventApi";

const windowReport: DetectorReport = {
  outcome: "BARELY_ENGAGED",
  observedAtMs: Date.parse("2026-07-28T09:00:10.000Z"),
  windowStartedAtMs: Date.parse("2026-07-28T09:00:00.000Z"),
  clientEventId: "0f9a2c14-6f0e-4f2a-9a1f-6b0f2b7a1c30",
};

const stoppedStateReport: DetectorReport = {
  outcome: "CAMERA_OFF",
  observedAtMs: Date.parse("2026-07-28T09:00:20.000Z"),
  clientEventId: "8b1d0e73-2c5a-4e19-9f38-7a2c1b4e6d05",
};

const okResponse = () =>
  new Response(JSON.stringify({ isSuccess: true, data: { duplicate: false } }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });

describe("sendAttentionEvent", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("posts the observation to the session's attention events endpoint", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com/");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(okResponse()));

    await sendAttentionEvent("55", windowReport, "test-access-token");

    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/sessions/55/attention-events",
      expect.objectContaining({
        method: "POST",
        credentials: "include",
        headers: expect.objectContaining({ Authorization: "Bearer test-access-token" }),
      }),
    );
  });

  it("converts the observation timestamps to UTC instants", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    const fetchMock = vi.fn().mockResolvedValue(okResponse());
    vi.stubGlobal("fetch", fetchMock);

    await sendAttentionEvent("55", windowReport, "token");

    expect(JSON.parse(fetchMock.mock.calls[0][1].body as string)).toEqual({
      outcome: "BARELY_ENGAGED",
      observedAt: "2026-07-28T09:00:10.000Z",
      windowStartedAt: "2026-07-28T09:00:00.000Z",
      featureSchemaVersion: "mediapipe_98_v1",
      clientEventId: "0f9a2c14-6f0e-4f2a-9a1f-6b0f2b7a1c30",
    });
  });

  /*
    서버는 계약에 없는 필드가 오면 400 으로 거절한다
    (AttentionEventRequest.isFreeOfUnsupportedFields). 리포트를 그대로 펼쳐 보내면 나중에
    로컬 판정용 필드가 하나 붙는 순간 전송이 통째로 깨진다.
  */
  it("never carries a field outside the request contract", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    const fetchMock = vi.fn().mockResolvedValue(okResponse());
    vi.stubGlobal("fetch", fetchMock);

    await sendAttentionEvent(
      "55",
      { ...windowReport, probabilities: [0.1, 0.2, 0.6, 0.1] } as DetectorReport,
      "token",
    );

    const sentBody = JSON.parse(fetchMock.mock.calls[0][1].body as string);
    expect(Object.keys(sentBody).sort()).toEqual([
      "clientEventId",
      "featureSchemaVersion",
      "observedAt",
      "outcome",
      "windowStartedAt",
    ]);
  });

  // 선택 필드는 값이 없으면 키 자체를 빼야 한다. null 을 실으면 시간선 검증이 읽을 값이 생긴다.
  it("omits the window start when the report did not observe a window", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    const fetchMock = vi.fn().mockResolvedValue(okResponse());
    vi.stubGlobal("fetch", fetchMock);

    await sendAttentionEvent("55", stoppedStateReport, "token");

    const sentBody = JSON.parse(fetchMock.mock.calls[0][1].body as string);
    expect(sentBody).not.toHaveProperty("windowStartedAt");
  });

  it("reports whether the server treated the observation as a duplicate", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ isSuccess: true, data: { duplicate: true } }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    await expect(sendAttentionEvent("55", windowReport, "token")).resolves.toEqual({
      duplicate: true,
    });
  });

  // 응답 본문이 계약과 달라도 서버는 관측을 받아들였다. 그것만으로 전송을 실패로 볼 이유가 없다.
  it("treats an unreadable success body as a non-duplicate", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("not json", { status: 200 })));

    await expect(sendAttentionEvent("55", windowReport, "token")).resolves.toEqual({
      duplicate: false,
    });
  });

  it("raises the response status so the caller can decide whether to stop", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 409 })));

    await expect(sendAttentionEvent("55", windowReport, "token")).rejects.toMatchObject({
      name: "AttentionEventSendError",
      status: 409,
    });
  });

  // 응답이 아예 없으면 상태를 알 수 없다. 0 으로 올려 호출부가 재시도할 수 있게 한다.
  it("reports a network failure as status zero", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));

    const failure = await sendAttentionEvent("55", windowReport, "token").catch(
      (error: unknown) => error,
    );

    expect(failure).toBeInstanceOf(AttentionEventSendError);
    expect((failure as AttentionEventSendError).status).toBe(0);
  });

  it("encodes an opaque session id used in the request path", async () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.example.com");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(okResponse()));

    await sendAttentionEvent("s/55", windowReport, "token");

    expect(fetch).toHaveBeenCalledWith(
      "https://api.example.com/api/v1/sessions/s%2F55/attention-events",
      expect.anything(),
    );
  });
});
