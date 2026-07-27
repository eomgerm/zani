import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { AudioClipSnapshot } from "@/features/media/audioRingBuffer";
import type {
  AudioCaptureState,
  InstructorAudioBufferHandle,
} from "@/features/media/useInstructorAudioBuffer";
import {
  AudioClipApiError,
  type AudioClipRequestEvent,
  type SubscribeAudioClipHandlers,
} from "../infrastructure/audioClipApi";
import { useAudioClipRequests } from "./useAudioClipRequests";

const SESSION_ID = "42";
const TOKEN = "access-token-1";

const clipFixture: AudioClipSnapshot = {
  blob: new Blob([new Uint8Array(8)], { type: "audio/webm;codecs=opus" }),
  mimeType: "audio/webm;codecs=opus",
  capturedFromMs: 0,
  capturedToMs: 180_000,
  durationMs: 180_000,
  segments: [{ fromMs: 0, toMs: 180_000 }],
};

/** 지금(가짜 시계) 기준 expiresInMs 뒤에 만료되는 요청 이벤트. */
const requestEvent = (clipId: string, expiresInMs = 60_000): AudioClipRequestEvent => ({
  clipId,
  windowSeconds: 300,
  expiresAt: new Date(Date.now() + expiresInMs).toISOString(),
});

type SubscribeCall = {
  sessionId: string;
  accessToken: string;
  handlers: SubscribeAudioClipHandlers;
  signal: AbortSignal | undefined;
  resolve: () => void;
  reject: (error: unknown) => void;
};

/** 구독 수명주기를 테스트가 직접 조종할 수 있는 가짜 subscribe. abort 시 AbortError 로 거절한다. */
const createSubscribeFake = () => {
  const calls: SubscribeCall[] = [];
  const fn = vi.fn(
    (
      sessionId: string,
      accessToken: string,
      handlers: SubscribeAudioClipHandlers,
      options?: { signal?: AbortSignal },
    ) =>
      new Promise<void>((resolve, reject) => {
        options?.signal?.addEventListener("abort", () =>
          reject(new DOMException("aborted", "AbortError")),
        );
        calls.push({ sessionId, accessToken, handlers, signal: options?.signal, resolve, reject });
      }),
  );
  return { fn, calls, last: () => calls[calls.length - 1] };
};

const createBuffer = (
  overrides: Partial<{
    captureState: AudioCaptureState;
    availableMs: number;
    snapshot: () => Promise<AudioClipSnapshot | null>;
  }> = {},
): InstructorAudioBufferHandle => ({
  captureState: overrides.captureState ?? "recording",
  availableMs: vi.fn(() => overrides.availableMs ?? 180_000),
  snapshot: vi.fn(overrides.snapshot ?? (async () => clipFixture)),
});

const setup = (
  overrides: {
    buffer?: InstructorAudioBufferHandle;
    sessionId?: string | null;
    accessToken?: string | null;
  } = {},
) => {
  const subscribe = createSubscribeFake();
  const upload = vi.fn(async () => {});
  const reportFailure = vi.fn(async () => {});
  const buffer = overrides.buffer ?? createBuffer();

  const view = renderHook(() =>
    useAudioClipRequests(
      overrides.sessionId === undefined ? SESSION_ID : overrides.sessionId,
      overrides.accessToken === undefined ? TOKEN : overrides.accessToken,
      buffer,
      { subscribe: subscribe.fn, upload, reportFailure },
    ),
  );
  return { subscribe, upload, reportFailure, buffer, ...view };
};

/** 훅 내부의 async 파이프라인(스냅샷→업로드→보고)을 흘려보낸다. */
const flush = () =>
  act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });

const advance = (ms: number) =>
  act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useAudioClipRequests — 스트림 연결", () => {
  it("마운트하면 세션·토큰으로 구독하고 streamState 가 open 이다", async () => {
    const { subscribe, result } = setup();
    await flush();

    expect(subscribe.fn).toHaveBeenCalledTimes(1);
    expect(subscribe.last().sessionId).toBe(SESSION_ID);
    expect(subscribe.last().accessToken).toBe(TOKEN);
    expect(result.current.streamState).toBe("open");
  });

  it("세션이나 토큰이 없으면 구독하지 않는다", async () => {
    const noSession = setup({ sessionId: null });
    const noToken = setup({ accessToken: null });
    await flush();

    expect(noSession.subscribe.fn).not.toHaveBeenCalled();
    expect(noToken.subscribe.fn).not.toHaveBeenCalled();
    expect(noSession.result.current.streamState).toBe("idle");
  });

  it("연결이 끊기면 1s→2s→4s→4s 백오프로 재구독한다", async () => {
    const { subscribe } = setup();
    await flush();

    act(() => subscribe.calls[0].reject(new AudioClipApiError("down", null)));
    await advance(999);
    expect(subscribe.fn).toHaveBeenCalledTimes(1);
    await advance(1);
    expect(subscribe.fn).toHaveBeenCalledTimes(2);

    act(() => subscribe.calls[1].reject(new AudioClipApiError("down", null)));
    await advance(2_000);
    expect(subscribe.fn).toHaveBeenCalledTimes(3);

    act(() => subscribe.calls[2].reject(new AudioClipApiError("down", null)));
    await advance(4_000);
    expect(subscribe.fn).toHaveBeenCalledTimes(4);

    // 이후에도 4초 상한으로 계속 재시도한다.
    act(() => subscribe.calls[3].reject(new AudioClipApiError("down", null)));
    await advance(4_000);
    expect(subscribe.fn).toHaveBeenCalledTimes(5);
  });

  it("스트림 활동(ping)이 있으면 백오프가 1초부터 다시 시작된다", async () => {
    const { subscribe } = setup();
    await flush();

    act(() => subscribe.calls[0].reject(new AudioClipApiError("down", null)));
    await advance(1_000); // 재구독 #2
    act(() => subscribe.calls[1].reject(new AudioClipApiError("down", null)));
    await advance(2_000); // 재구독 #3

    act(() => subscribe.calls[2].handlers.onPing?.()); // 활동 → 백오프 리셋
    act(() => subscribe.calls[2].reject(new AudioClipApiError("down", null)));

    await advance(1_000);
    expect(subscribe.fn).toHaveBeenCalledTimes(4);
  });

  it("401·403 처럼 자격이 없는 오류면 재구독을 멈추고 stopped 가 된다", async () => {
    const { subscribe, result } = setup();
    await flush();

    act(() => subscribe.calls[0].reject(new AudioClipApiError("forbidden", 403)));
    await advance(10_000);

    expect(subscribe.fn).toHaveBeenCalledTimes(1);
    expect(result.current.streamState).toBe("stopped");
  });

  it("ping 이 60초 동안 오지 않으면 죽은 연결로 보고 끊은 뒤 재구독한다", async () => {
    const { subscribe } = setup();
    await flush();

    // 30초마다 ping 이 오는 동안은 유지된다.
    await advance(30_000);
    act(() => subscribe.calls[0].handlers.onPing?.());
    await advance(59_000);
    expect(subscribe.calls[0].signal?.aborted).toBe(false);

    await advance(1_000); // 마지막 ping 이후 60초 경과
    expect(subscribe.calls[0].signal?.aborted).toBe(true);
    await flush();
    expect(subscribe.fn).toHaveBeenCalledTimes(2);
  });

  it("unmount 하면 연결을 중단하고 다시 구독하지 않는다", async () => {
    const { subscribe, unmount } = setup();
    await flush();

    unmount();

    expect(subscribe.calls[0].signal?.aborted).toBe(true);
    await advance(10_000);
    expect(subscribe.fn).toHaveBeenCalledTimes(1);
  });
});

describe("useAudioClipRequests — 요청 판정", () => {
  it("정상이면 snapshot 을 업로드한다", async () => {
    const { subscribe, upload, reportFailure, buffer } = setup();
    await flush();

    act(() => subscribe.last().handlers.onRequest(requestEvent("clip-1")));
    await flush();

    expect(buffer.snapshot).toHaveBeenCalledTimes(1);
    expect(upload).toHaveBeenCalledTimes(1);
    const [sessionId, clipId, clip, token] = (upload as ReturnType<typeof vi.fn>).mock
      .calls[0] as unknown as [string, string, AudioClipSnapshot, string];
    expect([sessionId, clipId, token]).toEqual([SESSION_ID, "clip-1", TOKEN]);
    expect(clip).toBe(clipFixture);
    expect(reportFailure).not.toHaveBeenCalled();
  });

  it("가용량이 1분 미만이면 업로드하지 않고 INSUFFICIENT_AUDIO 와 가용량을 보고한다", async () => {
    const { subscribe, upload, reportFailure } = setup({
      buffer: createBuffer({ availableMs: 30_000 }),
    });
    await flush();

    act(() => subscribe.last().handlers.onRequest(requestEvent("clip-1")));
    await flush();

    expect(upload).not.toHaveBeenCalled();
    expect(reportFailure).toHaveBeenCalledWith(
      SESSION_ID,
      "clip-1",
      { reason: "INSUFFICIENT_AUDIO", availableMs: 30_000 },
      TOKEN,
      expect.anything(),
    );
  });

  it("일시정지 상태로 가용량이 0 이면 MICROPHONE_OFF 를 보고한다", async () => {
    const { subscribe, reportFailure } = setup({
      buffer: createBuffer({ captureState: "paused", availableMs: 0 }),
    });
    await flush();

    act(() => subscribe.last().handlers.onRequest(requestEvent("clip-1")));
    await flush();

    expect(reportFailure).toHaveBeenCalledWith(
      SESSION_ID,
      "clip-1",
      { reason: "MICROPHONE_OFF", availableMs: 0 },
      TOKEN,
      expect.anything(),
    );
  });

  it("캡처가 불가능한 상태(idle)면 CAPTURE_UNAVAILABLE 을 보고한다", async () => {
    const { subscribe, reportFailure } = setup({
      buffer: createBuffer({ captureState: "idle", availableMs: 0 }),
    });
    await flush();

    act(() => subscribe.last().handlers.onRequest(requestEvent("clip-1")));
    await flush();

    expect(reportFailure).toHaveBeenCalledWith(
      SESSION_ID,
      "clip-1",
      { reason: "CAPTURE_UNAVAILABLE", availableMs: 0 },
      TOKEN,
      expect.anything(),
    );
  });

  it("snapshot 이 null 이면 CAPTURE_UNAVAILABLE 을 보고한다", async () => {
    const { subscribe, upload, reportFailure } = setup({
      buffer: createBuffer({ snapshot: async () => null }),
    });
    await flush();

    act(() => subscribe.last().handlers.onRequest(requestEvent("clip-1")));
    await flush();

    expect(upload).not.toHaveBeenCalled();
    expect(reportFailure).toHaveBeenCalledWith(
      SESSION_ID,
      "clip-1",
      expect.objectContaining({ reason: "CAPTURE_UNAVAILABLE" }),
      TOKEN,
      expect.anything(),
    );
  });

  it("이미 만료된 요청은 무시한다", async () => {
    const { subscribe, upload, reportFailure, buffer } = setup();
    await flush();

    act(() => subscribe.last().handlers.onRequest(requestEvent("clip-1", -1_000)));
    await flush();

    expect(buffer.snapshot).not.toHaveBeenCalled();
    expect(upload).not.toHaveBeenCalled();
    expect(reportFailure).not.toHaveBeenCalled();
  });
});

describe("useAudioClipRequests — 업로드 재시도", () => {
  it("일시 오류면 1s/2s/4s 뒤에 재시도하고, 총 4회 실패하면 UPLOAD_FAILED 를 보고하고 멈춘다", async () => {
    const { subscribe, upload, reportFailure } = setup();
    (upload as ReturnType<typeof vi.fn>).mockRejectedValue(new AudioClipApiError("503", 503));
    await flush();

    act(() => subscribe.last().handlers.onRequest(requestEvent("clip-1", 60_000)));
    await flush();
    expect(upload).toHaveBeenCalledTimes(1);

    await advance(1_000);
    expect(upload).toHaveBeenCalledTimes(2);
    await advance(2_000);
    expect(upload).toHaveBeenCalledTimes(3);
    await advance(4_000);
    expect(upload).toHaveBeenCalledTimes(4);

    expect(reportFailure).toHaveBeenCalledWith(
      SESSION_ID,
      "clip-1",
      expect.objectContaining({ reason: "UPLOAD_FAILED" }),
      TOKEN,
      expect.anything(),
    );

    await advance(10_000);
    expect(upload).toHaveBeenCalledTimes(4); // 5번째 시도는 없다
  });

  it("409 처럼 서버가 이미 종결한 오류면 즉시 포기하고 재시도·보고 모두 하지 않는다", async () => {
    const { subscribe, upload, reportFailure } = setup();
    (upload as ReturnType<typeof vi.fn>).mockRejectedValue(new AudioClipApiError("expired", 409));
    await flush();

    act(() => subscribe.last().handlers.onRequest(requestEvent("clip-1")));
    await flush();
    await advance(10_000);

    expect(upload).toHaveBeenCalledTimes(1);
    expect(reportFailure).not.toHaveBeenCalled();
  });

  it("재시도 대기 중 요청이 만료되면 다음 시도를 포기한다", async () => {
    const { subscribe, upload, reportFailure } = setup();
    (upload as ReturnType<typeof vi.fn>).mockRejectedValue(new AudioClipApiError("503", 503));
    await flush();

    // 만료까지 1.5초: 시도1(0s), 시도2(1s)까지만 가능하고 시도3(3s)은 만료 뒤라 포기한다.
    act(() => subscribe.last().handlers.onRequest(requestEvent("clip-1", 1_500)));
    await flush();
    await advance(1_000);
    expect(upload).toHaveBeenCalledTimes(2);

    await advance(10_000);
    expect(upload).toHaveBeenCalledTimes(2);
    expect(reportFailure).not.toHaveBeenCalled(); // 만료는 서버가 이미 아는 종결 — 보고 생략
  });

  it("처리 중 새 요청이 오면 이전 처리를 중단(coalesce)하고 최신 요청만 진행한다", async () => {
    const { subscribe, upload } = setup();
    const uploadMock = upload as ReturnType<typeof vi.fn>;
    // clip-1 은 계속 실패(재시도 유발), clip-2 는 성공.
    uploadMock.mockImplementation(async (_s: string, clipId: string) => {
      if (clipId === "clip-1") {
        throw new AudioClipApiError("503", 503);
      }
    });
    await flush();

    act(() => subscribe.last().handlers.onRequest(requestEvent("clip-1")));
    await flush();
    expect(uploadMock).toHaveBeenCalledTimes(1);
    const firstSignal = (uploadMock.mock.calls[0] as unknown as [unknown, unknown, unknown, unknown, { signal?: AbortSignal }])[4]?.signal;

    act(() => subscribe.last().handlers.onRequest(requestEvent("clip-2")));
    await flush();

    expect(firstSignal?.aborted).toBe(true); // 이전 처리에 전달된 signal 이 중단된다
    const clipIds = uploadMock.mock.calls.map((call) => (call as unknown as [unknown, string])[1]);
    expect(clipIds).toContain("clip-2");

    // clip-1 의 재시도 타이머가 남아 있어도 더는 업로드하지 않는다.
    await advance(10_000);
    const clip1Attempts = uploadMock.mock.calls.filter(
      (call) => (call as unknown as [unknown, string])[1] === "clip-1",
    ).length;
    expect(clip1Attempts).toBe(1);
  });
});
