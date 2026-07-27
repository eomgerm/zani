import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { AudioClipSnapshot } from "@/features/media/audioRingBuffer";
import {
  AudioClipApiError,
  reportAudioClipFailure,
  subscribeAudioClipRequests,
  uploadAudioClip,
  type AudioClipRequestEvent,
} from "./audioClipApi";

const SESSION_ID = "42";
const CLIP_ID = "9007199254740993"; // Number.MAX_SAFE_INTEGER 초과 — 문자열로만 안전한 TSID
const TOKEN = "access-token-1";

const encoder = new TextEncoder();

/** 주어진 텍스트 조각들을 순서대로 흘려보내는 SSE 응답을 만든다. */
const sseResponse = (...chunks: string[]): Response => {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
      controller.close();
    },
  });
  return new Response(stream, {
    status: 200,
    headers: { "Content-Type": "text/event-stream" },
  });
};

const envelopeResponse = (data: unknown, status = 200): Response =>
  new Response(JSON.stringify({ isSuccess: true, code: "COMMON200", message: "OK", data }), {
    status,
    headers: { "Content-Type": "application/json" },
  });

const decodeText = async (part: Blob): Promise<string> =>
  new TextDecoder().decode(await part.arrayBuffer());

beforeEach(() => {
  vi.restoreAllMocks();
});

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("subscribeAudioClipRequests", () => {
  it("스트림 URL 로 Authorization·Accept 헤더를 붙여 요청한다", async () => {
    const fetchFn = vi.fn(async () => sseResponse(""));

    await subscribeAudioClipRequests(SESSION_ID, TOKEN, { onRequest: () => {} }, { fetchFn });

    expect(fetchFn).toHaveBeenCalledTimes(1);
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toContain(`/api/v1/sessions/${SESSION_ID}/audio-clip-requests/stream`);
    const headers = init.headers as Record<string, string>;
    expect(headers.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(headers.Accept).toBe("text/event-stream");
  });

  it("AUDIO_CLIP_REQUESTED 이벤트를 파싱해 onRequest 로 전달한다", async () => {
    const events: AudioClipRequestEvent[] = [];
    const body =
      `event:AUDIO_CLIP_REQUESTED\ndata:{"clipId":"${CLIP_ID}","windowSeconds":300,"expiresAt":"2026-07-27T00:01:00Z"}\n\n` +
      `event:AUDIO_CLIP_REQUESTED\ndata:{"clipId":"2","windowSeconds":300,"expiresAt":"2026-07-27T00:02:00Z"}\n\n`;
    // 청크 경계가 이벤트 중간을 가르는 상황을 함께 검증한다.
    const fetchFn = vi.fn(async () => sseResponse(body.slice(0, 25), body.slice(25)));

    await subscribeAudioClipRequests(
      SESSION_ID,
      TOKEN,
      { onRequest: (event) => events.push(event) },
      { fetchFn },
    );

    expect(events).toEqual([
      { clipId: CLIP_ID, windowSeconds: 300, expiresAt: "2026-07-27T00:01:00Z" },
      { clipId: "2", windowSeconds: 300, expiresAt: "2026-07-27T00:02:00Z" },
    ]);
  });

  it("주석 ping 은 onPing 으로 전달한다", async () => {
    const onPing = vi.fn();
    const fetchFn = vi.fn(async () => sseResponse(":ping\n\n:ping\n\n"));

    await subscribeAudioClipRequests(SESSION_ID, TOKEN, { onRequest: () => {}, onPing }, { fetchFn });

    expect(onPing).toHaveBeenCalledTimes(2);
  });

  it("형식이 깨진 이벤트 data 는 건너뛰고 다음 이벤트를 계속 처리한다", async () => {
    const events: AudioClipRequestEvent[] = [];
    const fetchFn = vi.fn(async () =>
      sseResponse(
        "event:AUDIO_CLIP_REQUESTED\ndata:not-json\n\n",
        `event:AUDIO_CLIP_REQUESTED\ndata:{"clipId":"2","windowSeconds":300,"expiresAt":"2026-07-27T00:02:00Z"}\n\n`,
      ),
    );

    await subscribeAudioClipRequests(
      SESSION_ID,
      TOKEN,
      { onRequest: (event) => events.push(event) },
      { fetchFn },
    );

    expect(events.map((event) => event.clipId)).toEqual(["2"]);
  });

  it("HTTP 오류 상태면 상태 코드를 담은 AudioClipApiError 로 거절한다", async () => {
    const fetchFn = vi.fn(async () => new Response("forbidden", { status: 403 }));

    await expect(
      subscribeAudioClipRequests(SESSION_ID, TOKEN, { onRequest: () => {} }, { fetchFn }),
    ).rejects.toMatchObject({ name: "AudioClipApiError", status: 403 });
  });

  it("네트워크 오류면 status null 인 AudioClipApiError 로 거절한다", async () => {
    const fetchFn = vi.fn(async () => {
      throw new TypeError("Failed to fetch");
    });

    await expect(
      subscribeAudioClipRequests(SESSION_ID, TOKEN, { onRequest: () => {} }, { fetchFn }),
    ).rejects.toMatchObject({ name: "AudioClipApiError", status: null });
  });

  it("중단(abort)은 AbortError 그대로 전파해 실패와 구분할 수 있게 한다", async () => {
    const controller = new AbortController();
    const fetchFn = vi.fn(
      (_url: unknown, init?: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () =>
            reject(new DOMException("aborted", "AbortError")),
          );
        }),
    );

    const pending = subscribeAudioClipRequests(
      SESSION_ID,
      TOKEN,
      { onRequest: () => {} },
      { fetchFn, signal: controller.signal },
    );
    controller.abort();

    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
  });
});

describe("uploadAudioClip", () => {
  const clip: AudioClipSnapshot = {
    blob: new Blob([new Uint8Array(16)], { type: "audio/webm;codecs=opus" }),
    mimeType: "audio/webm;codecs=opus",
    capturedFromMs: Date.UTC(2026, 6, 27, 0, 0, 0),
    capturedToMs: Date.UTC(2026, 6, 27, 0, 3, 0),
    durationMs: 170_000,
    segments: [
      { fromMs: Date.UTC(2026, 6, 27, 0, 0, 0), toMs: Date.UTC(2026, 6, 27, 0, 1, 0) },
      { fromMs: Date.UTC(2026, 6, 27, 0, 1, 10), toMs: Date.UTC(2026, 6, 27, 0, 3, 0) },
    ],
  };

  it("audio·meta 파트를 multipart 로 보내고 캡처 시각을 ISO 로 변환한다", async () => {
    const fetchFn = vi.fn(async () =>
      envelopeResponse({ clipId: CLIP_ID, status: "UPLOADED", alreadyUploaded: false }),
    );

    await uploadAudioClip(SESSION_ID, CLIP_ID, clip, TOKEN, { fetchFn });

    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toContain(`/api/v1/sessions/${SESSION_ID}/audio-clips/${CLIP_ID}`);
    expect(init.method).toBe("POST");
    expect((init.headers as Record<string, string>).Authorization).toBe(`Bearer ${TOKEN}`);

    const formData = init.body as FormData;
    const audioPart = formData.get("audio") as Blob;
    expect(audioPart.type).toBe("audio/webm;codecs=opus");
    expect(audioPart.size).toBe(16);

    const meta = JSON.parse(await decodeText(formData.get("meta") as Blob)) as Record<string, unknown>;
    expect(meta).toEqual({
      capturedFrom: "2026-07-27T00:00:00.000Z",
      capturedTo: "2026-07-27T00:03:00.000Z",
      durationMs: 170_000,
      segments: [
        { from: "2026-07-27T00:00:00.000Z", to: "2026-07-27T00:01:00.000Z" },
        { from: "2026-07-27T00:01:10.000Z", to: "2026-07-27T00:03:00.000Z" },
      ],
    });
  });

  it("서버가 오류 상태를 주면 상태 코드를 담아 거절한다", async () => {
    const fetchFn = vi.fn(async () => new Response("too large", { status: 413 }));

    await expect(uploadAudioClip(SESSION_ID, CLIP_ID, clip, TOKEN, { fetchFn })).rejects.toMatchObject({
      name: "AudioClipApiError",
      status: 413,
    });
  });

  it("네트워크 오류면 status null 로 거절한다", async () => {
    const fetchFn = vi.fn(async () => {
      throw new TypeError("Failed to fetch");
    });

    await expect(uploadAudioClip(SESSION_ID, CLIP_ID, clip, TOKEN, { fetchFn })).rejects.toMatchObject({
      status: null,
    });
  });
});

describe("reportAudioClipFailure", () => {
  it("실패 사유와 가용량을 JSON 으로 보고한다", async () => {
    const fetchFn = vi.fn(async () => envelopeResponse(null));

    await reportAudioClipFailure(
      SESSION_ID,
      CLIP_ID,
      { reason: "INSUFFICIENT_AUDIO", availableMs: 45_000 },
      TOKEN,
      { fetchFn },
    );

    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toContain(`/api/v1/sessions/${SESSION_ID}/audio-clips/${CLIP_ID}/failure`);
    expect(init.method).toBe("POST");
    expect((init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
    expect(JSON.parse(init.body as string)).toEqual({
      reason: "INSUFFICIENT_AUDIO",
      availableMs: 45_000,
    });
  });

  it("서버 오류 상태를 상태 코드로 전달한다", async () => {
    const fetchFn = vi.fn(async () => new Response("conflict", { status: 409 }));

    await expect(
      reportAudioClipFailure(SESSION_ID, CLIP_ID, { reason: "UPLOAD_FAILED", availableMs: 0 }, TOKEN, {
        fetchFn,
      }),
    ).rejects.toMatchObject({ status: 409 });
  });
});

describe("AudioClipApiError", () => {
  it("이름과 status 를 보존한다", () => {
    const error = new AudioClipApiError("boom", 503);
    expect(error.name).toBe("AudioClipApiError");
    expect(error.status).toBe(503);
    expect(new AudioClipApiError("net", null).status).toBeNull();
  });
});
