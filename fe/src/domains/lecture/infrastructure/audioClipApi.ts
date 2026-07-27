/**
 * 오디오 클립 API 클라이언트: 클립 요청 SSE 구독, 클립 업로드, 실패 보고.
 *
 * 액세스 토큰은 AuthProvider 의 메모리 상태에만 있으므로 호출자가 인자로 전달한다.
 * 모든 실패는 {@link AudioClipApiError}(status: HTTP 상태, 네트워크 오류는 null)로
 * 정규화하고, 중단(abort)만 AbortError 그대로 전파해 재시도 로직이 실패와 구분할 수
 * 있게 한다.
 */

import type { AudioClipSnapshot } from "@/features/media/audioRingBuffer";
import { createSseParser } from "./sseParser";

export type AudioClipRequestEvent = {
  /** TSID. JS number 정밀도(2^53)를 넘으므로 항상 문자열로 다룬다. */
  readonly clipId: string;
  readonly windowSeconds: number;
  readonly expiresAt: string;
};

export type AudioClipFailureReason =
  | "INSUFFICIENT_AUDIO"
  | "MICROPHONE_OFF"
  | "CAPTURE_UNAVAILABLE"
  | "UPLOAD_FAILED";

export type SubscribeAudioClipHandlers = {
  onRequest: (event: AudioClipRequestEvent) => void;
  /** 서버 생존 신호(SSE 주석). 죽은 연결 감지의 입력이다. */
  onPing?: () => void;
};

export class AudioClipApiError extends Error {
  /** HTTP 상태 코드. 응답을 받지 못한 네트워크 오류는 null 이다. */
  readonly status: number | null;

  constructor(message: string, status: number | null) {
    super(message);
    this.name = "AudioClipApiError";
    this.status = status;
  }
}

type FetchLike = typeof fetch;

type RequestOptions = {
  readonly signal?: AbortSignal;
  /** 테스트 주입점. 기본은 전역 fetch. */
  readonly fetchFn?: FetchLike;
};

export const AUDIO_CLIP_REQUESTED_EVENT = "AUDIO_CLIP_REQUESTED";

const apiBaseUrl = () => (process.env.NEXT_PUBLIC_API_BASE_URL ?? "").replace(/\/$/, "");

const isAbortError = (error: unknown): boolean =>
  typeof error === "object" && error !== null && (error as { name?: unknown }).name === "AbortError";

const toApiError = (error: unknown): AudioClipApiError =>
  new AudioClipApiError(error instanceof Error ? error.message : "Network request failed.", null);

const authHeaders = (accessToken: string): Record<string, string> => ({
  Authorization: `Bearer ${accessToken}`,
});

/** 이벤트 data 를 검증해 파싱한다. 형식이 깨졌으면 null — 한 이벤트가 스트림 전체를 죽이지 않게 한다. */
const parseRequestEvent = (data: string): AudioClipRequestEvent | null => {
  try {
    const parsed = JSON.parse(data) as Record<string, unknown>;
    if (
      typeof parsed !== "object" ||
      parsed === null ||
      typeof parsed.clipId !== "string" ||
      parsed.clipId.trim() === "" ||
      typeof parsed.windowSeconds !== "number" ||
      typeof parsed.expiresAt !== "string"
    ) {
      return null;
    }
    return {
      clipId: parsed.clipId,
      windowSeconds: parsed.windowSeconds,
      expiresAt: parsed.expiresAt,
    };
  } catch {
    return null;
  }
};

/**
 * 클립 요청 SSE 스트림을 구독한다. 스트림이 정상 종료되면 resolve 하고, HTTP·네트워크
 * 오류는 AudioClipApiError, 중단은 AbortError 로 거절한다. 재구독·백오프는 호출자 몫이다.
 */
export async function subscribeAudioClipRequests(
  sessionId: string,
  accessToken: string,
  handlers: SubscribeAudioClipHandlers,
  options: RequestOptions = {},
): Promise<void> {
  const fetchFn = options.fetchFn ?? fetch;

  let response: Response;
  try {
    response = await fetchFn(
      `${apiBaseUrl()}/api/v1/sessions/${encodeURIComponent(sessionId)}/audio-clip-requests/stream`,
      {
        method: "GET",
        headers: { ...authHeaders(accessToken), Accept: "text/event-stream" },
        credentials: "include",
        cache: "no-store",
        signal: options.signal,
      },
    );
  } catch (error) {
    if (isAbortError(error)) {
      throw error;
    }
    throw toApiError(error);
  }

  if (!response.ok) {
    throw new AudioClipApiError(`Audio clip stream failed with status ${response.status}.`, response.status);
  }
  const body = response.body;
  if (!body) {
    throw new AudioClipApiError("Audio clip stream response has no body.", null);
  }

  const parser = createSseParser({
    onEvent: (event) => {
      if (event.name !== AUDIO_CLIP_REQUESTED_EVENT) {
        return;
      }
      const request = parseRequestEvent(event.data);
      if (request) {
        handlers.onRequest(request);
      }
    },
    onComment: () => handlers.onPing?.(),
  });

  const reader = body.getReader();
  const decoder = new TextDecoder();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) {
        return;
      }
      parser.push(decoder.decode(value, { stream: true }));
    }
  } catch (error) {
    if (isAbortError(error)) {
      throw error;
    }
    throw toApiError(error);
  } finally {
    reader.releaseLock();
  }
}

/** 공통 요청 실행: 상태·네트워크 오류를 AudioClipApiError 로 정규화하고 성공 봉투를 확인한다. */
async function requestWithEnvelope(
  url: string,
  init: RequestInit,
  fetchFn: FetchLike,
): Promise<void> {
  let response: Response;
  try {
    response = await fetchFn(url, init);
  } catch (error) {
    if (isAbortError(error)) {
      throw error;
    }
    throw toApiError(error);
  }

  if (!response.ok) {
    throw new AudioClipApiError(`Request failed with status ${response.status}.`, response.status);
  }

  let envelope: unknown;
  try {
    envelope = await response.json();
  } catch {
    throw new AudioClipApiError("Response was not valid JSON.", response.status);
  }
  if (
    typeof envelope !== "object" ||
    envelope === null ||
    (envelope as { isSuccess?: unknown }).isSuccess !== true
  ) {
    throw new AudioClipApiError("Response had an invalid envelope.", response.status);
  }
}

/**
 * 링버퍼 스냅샷을 multipart(audio + meta JSON)로 업로드한다. 캡처 시각(epoch ms)은
 * 서버 계약에 맞춰 ISO-8601 문자열로 변환한다.
 */
export async function uploadAudioClip(
  sessionId: string,
  clipId: string,
  clip: AudioClipSnapshot,
  accessToken: string,
  options: RequestOptions = {},
): Promise<void> {
  const meta = {
    capturedFrom: new Date(clip.capturedFromMs).toISOString(),
    capturedTo: new Date(clip.capturedToMs).toISOString(),
    durationMs: clip.durationMs,
    segments: clip.segments.map((segment) => ({
      from: new Date(segment.fromMs).toISOString(),
      to: new Date(segment.toMs).toISOString(),
    })),
  };

  const formData = new FormData();
  formData.append("audio", clip.blob, "clip.webm");
  formData.append("meta", new Blob([JSON.stringify(meta)], { type: "application/json" }));

  await requestWithEnvelope(
    `${apiBaseUrl()}/api/v1/sessions/${encodeURIComponent(sessionId)}/audio-clips/${encodeURIComponent(clipId)}`,
    {
      method: "POST",
      // Content-Type 은 boundary 를 포함해 브라우저가 설정하도록 지정하지 않는다.
      headers: authHeaders(accessToken),
      credentials: "include",
      body: formData,
      signal: options.signal,
    },
    options.fetchFn ?? fetch,
  );
}

/** 클립을 확보하지 못한 사유를 보고해 서버가 만료를 기다리지 않고 요청을 종결하게 한다. */
export async function reportAudioClipFailure(
  sessionId: string,
  clipId: string,
  failure: { reason: AudioClipFailureReason; availableMs: number },
  accessToken: string,
  options: RequestOptions = {},
): Promise<void> {
  await requestWithEnvelope(
    `${apiBaseUrl()}/api/v1/sessions/${encodeURIComponent(sessionId)}/audio-clips/${encodeURIComponent(clipId)}/failure`,
    {
      method: "POST",
      headers: { ...authHeaders(accessToken), "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify(failure),
      signal: options.signal,
    },
    options.fetchFn ?? fetch,
  );
}
