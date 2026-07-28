"use client";

import { useEffect, useRef, useState } from "react";

import { canUploadClip } from "@/features/media/audioRingBuffer";
import type { AudioClipSnapshot } from "@/features/media/audioRingBuffer";
import type { InstructorAudioBufferHandle } from "@/features/media/useInstructorAudioBuffer";
import {
  AudioClipApiError,
  reportAudioClipFailure,
  subscribeAudioClipRequests,
  uploadAudioClip,
  type AudioClipFailureReason,
  type AudioClipRequestEvent,
  type SubscribeAudioClipHandlers,
} from "../infrastructure/audioClipApi";

export type AudioClipStreamState = "idle" | "open" | "reconnecting" | "stopped";

/** 업로드 재시도 간격. 최초 시도 후 최대 3회 재시도한다(프로젝트 재연결 규약과 동일 간격). */
export const CLIP_UPLOAD_RETRY_DELAYS_MS = [1_000, 2_000, 4_000] as const;

/** 스트림 재구독 백오프. 마지막 값을 상한으로 유지한다 — 수업 중 채널은 계속 살아 있어야 한다. */
export const STREAM_RECONNECT_DELAYS_MS = [1_000, 2_000, 4_000] as const;

/** 서버 ping(20초) 3회 무수신이면 죽은 연결로 판정한다. 서버 PING_INTERVAL 과 함께 바뀌어야 한다. */
export const STREAM_LIVENESS_TIMEOUT_MS = 60_000;

export type UseAudioClipRequestsOptions = {
  /** 테스트 주입점. 기본은 실제 API 함수. */
  readonly subscribe?: typeof subscribeAudioClipRequests;
  readonly upload?: typeof uploadAudioClip;
  readonly reportFailure?: typeof reportAudioClipFailure;
};

export type UseAudioClipRequestsResult = {
  readonly streamState: AudioClipStreamState;
};

const isAbortError = (error: unknown): boolean =>
  typeof error === "object" && error !== null && (error as { name?: unknown }).name === "AbortError";

/** 자격 상실·없는 세션·종료된 세션 — 재구독해도 회복되지 않는 상태. */
const isFatalStreamStatus = (status: number | null): boolean =>
  status === 401 || status === 403 || status === 404 || status === 409;

/** 진행 중인 클립 요청 처리 한 건. coalesce 시 통째로 중단된다. */
type ActiveClipRun = {
  readonly clipId: string;
  readonly controller: AbortController;
  retryTimer: ReturnType<typeof setTimeout> | undefined;
};

/**
 * 서버의 클립 요청 스트림을 구독하고, 요청이 오면 링버퍼 스냅샷을 판정해 업로드하거나
 * 실패 사유를 보고하는 오케스트레이션 훅.
 *
 * - 스트림: 1s/2s/4s 백오프 재구독(활동 시 리셋), ping 60초 무수신이면 끊고 즉시 재구독,
 *   401/403/404/409 는 회복 불가로 보고 중단한다.
 * - 업로드: 5xx·네트워크 오류만 1s/2s/4s 로 재시도(총 4회 시도)하고, 소진되면 UPLOAD_FAILED 를
 *   보고한다. 4xx 는 재시도하지 않으며 404/409(서버가 이미 종결)는 보고도 생략한다.
 * - 처리 중 새 요청이 오면 이전 처리를 중단하고 최신 요청만 진행한다(coalesce) —
 *   어차피 둘 다 "최근 300초"라 최신 요청이 이전 요청을 포함한다.
 */
export function useAudioClipRequests(
  sessionId: string | null,
  accessToken: string | null,
  buffer: InstructorAudioBufferHandle,
  options: UseAudioClipRequestsOptions = {},
): UseAudioClipRequestsResult {
  const [streamState, setStreamState] = useState<AudioClipStreamState>("idle");

  const subscribeFn = options.subscribe ?? subscribeAudioClipRequests;

  // buffer 핸들은 captureState 변화로 렌더마다 새 객체가 되므로 ref 로 최신만 읽는다
  // (effect 의존성에 넣으면 상태가 바뀔 때마다 스트림을 다시 연결하게 된다).
  const latestRef = useRef({
    buffer,
    upload: options.upload ?? uploadAudioClip,
    reportFailure: options.reportFailure ?? reportAudioClipFailure,
  });
  useEffect(() => {
    latestRef.current = {
      buffer,
      upload: options.upload ?? uploadAudioClip,
      reportFailure: options.reportFailure ?? reportAudioClipFailure,
    };
  });

  useEffect(() => {
    if (!sessionId || !accessToken) {
      return;
    }
    const session = sessionId;
    const token = accessToken;

    let disposed = false;
    let reconnectAttempt = 0;
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
    let livenessTimer: ReturnType<typeof setTimeout> | undefined;
    let livenessAborted = false;
    let streamController: AbortController | null = null;
    let active: ActiveClipRun | null = null;

    const clearLiveness = () => {
      if (livenessTimer !== undefined) {
        clearTimeout(livenessTimer);
        livenessTimer = undefined;
      }
    };

    const armLiveness = () => {
      clearLiveness();
      livenessTimer = setTimeout(() => {
        // ping 3회 무수신 — TCP 만 살아 있는 죽은 연결로 보고 끊은 뒤 즉시 재구독한다.
        livenessAborted = true;
        streamController?.abort();
      }, STREAM_LIVENESS_TIMEOUT_MS);
    };

    /** 이벤트·ping 수신 = 살아 있는 연결. 백오프를 리셋하고 사망 판정 시계를 되감는다. */
    const noteActivity = () => {
      reconnectAttempt = 0;
      armLiveness();
    };

    const cancelActive = () => {
      if (active !== null) {
        active.controller.abort();
        if (active.retryTimer !== undefined) {
          clearTimeout(active.retryTimer);
        }
        active = null;
      }
    };

    const finishRun = (run: ActiveClipRun) => {
      if (active === run) {
        active = null;
      }
    };

    const report = async (run: ActiveClipRun, reason: AudioClipFailureReason, availableMs: number) => {
      if (disposed || run.controller.signal.aborted) {
        return;
      }
      try {
        await latestRef.current.reportFailure(
            session, run.clipId, { reason, availableMs }, token, { signal: run.controller.signal });
      } catch {
        // 보고 실패는 치명적이지 않다 — 서버의 요청 만료 타이머가 대신 종결한다.
      }
      finishRun(run);
    };

    const attemptUpload = async (
      run: ActiveClipRun,
      clip: AudioClipSnapshot,
      expiresAtMs: number,
      retryIndex: number,
    ) => {
      if (disposed || run.controller.signal.aborted) {
        return;
      }
      if (Date.now() >= expiresAtMs) {
        // 만료 — 서버가 이미 스스로 종결하는 상태라 시도·보고 모두 생략한다.
        finishRun(run);
        return;
      }

      try {
        await latestRef.current.upload(session, run.clipId, clip, token, {
          signal: run.controller.signal,
        });
        finishRun(run);
      } catch (error) {
        if (disposed || run.controller.signal.aborted || isAbortError(error)) {
          return;
        }
        const status = error instanceof AudioClipApiError ? error.status : null;
        if (status !== null && status >= 400 && status < 500) {
          // 4xx 는 재시도해도 결과가 같다. 404/409 는 서버가 이미 종결한 상태라 보고도 생략한다.
          if (status === 404 || status === 409 || status === 401 || status === 403) {
            finishRun(run);
          } else {
            await report(run, "UPLOAD_FAILED", latestRef.current.buffer.availableMs());
          }
          return;
        }
        if (retryIndex >= CLIP_UPLOAD_RETRY_DELAYS_MS.length) {
          await report(run, "UPLOAD_FAILED", latestRef.current.buffer.availableMs());
          return;
        }
        run.retryTimer = setTimeout(() => {
          void attemptUpload(run, clip, expiresAtMs, retryIndex + 1);
        }, CLIP_UPLOAD_RETRY_DELAYS_MS[retryIndex]);
      }
    };

    const processRequest = async (run: ActiveClipRun, expiresAtMs: number) => {
      const { buffer: currentBuffer } = latestRef.current;
      const availableMs = currentBuffer.availableMs();

      // idle(마이크 미게시)·unavailable(캡처 불가) 모두 지금 올릴 오디오가 없다.
      if (currentBuffer.captureState === "idle" || currentBuffer.captureState === "unavailable") {
        await report(run, "CAPTURE_UNAVAILABLE", availableMs);
        return;
      }
      if (!canUploadClip(availableMs)) {
        const reason = availableMs === 0 && currentBuffer.captureState === "paused"
            ? "MICROPHONE_OFF"
            : "INSUFFICIENT_AUDIO";
        await report(run, reason, availableMs);
        return;
      }

      const clip = await currentBuffer.snapshot();
      if (disposed || run.controller.signal.aborted) {
        return;
      }
      if (clip === null) {
        await report(run, "CAPTURE_UNAVAILABLE", latestRef.current.buffer.availableMs());
        return;
      }
      await attemptUpload(run, clip, expiresAtMs, 0);
    };

    const handleRequest = (event: AudioClipRequestEvent) => {
      noteActivity();
      const expiresAtMs = Date.parse(event.expiresAt);
      if (!Number.isFinite(expiresAtMs) || expiresAtMs <= Date.now()) {
        // 리플레이로 온 만료 직전 요청 등 — 이미 늦었으니 무시한다.
        return;
      }
      // coalesce: 어차피 최신 요청의 "최근 300초"가 이전 요청을 포함하므로 최신 것만 처리한다.
      cancelActive();
      const run: ActiveClipRun = { clipId: event.clipId, controller: new AbortController(), retryTimer: undefined };
      active = run;
      void processRequest(run, expiresAtMs);
    };

    const scheduleReconnect = (immediate: boolean) => {
      if (disposed) {
        return;
      }
      clearLiveness();
      setStreamState("reconnecting");
      if (immediate) {
        // 죽은 연결 감지는 네트워크 실패가 아니다 — 백오프를 소비하지 않고 곧바로 다시 연결한다.
        connect();
        return;
      }
      const delay =
          STREAM_RECONNECT_DELAYS_MS[Math.min(reconnectAttempt, STREAM_RECONNECT_DELAYS_MS.length - 1)];
      reconnectAttempt += 1;
      reconnectTimer = setTimeout(connect, delay);
    };

    function connect() {
      if (disposed) {
        return;
      }
      streamController = new AbortController();
      livenessAborted = false;
      setStreamState("open");
      armLiveness();

      const handlers: SubscribeAudioClipHandlers = {
        onRequest: handleRequest,
        onPing: noteActivity,
      };
      subscribeFn(session, token, handlers, { signal: streamController.signal })
          .then(() => scheduleReconnect(false))
          .catch((error: unknown) => {
            if (disposed) {
              return;
            }
            if (isAbortError(error)) {
              if (livenessAborted) {
                scheduleReconnect(true);
              }
              return; // unmount 등 의도된 중단은 조용히 끝낸다.
            }
            const status = error instanceof AudioClipApiError ? error.status : null;
            if (isFatalStreamStatus(status)) {
              clearLiveness();
              setStreamState("stopped");
              return;
            }
            scheduleReconnect(false);
          });
    }

    connect();

    return () => {
      disposed = true;
      clearLiveness();
      if (reconnectTimer !== undefined) {
        clearTimeout(reconnectTimer);
      }
      streamController?.abort();
      cancelActive();
      setStreamState("idle");
    };
  }, [sessionId, accessToken, subscribeFn]);

  return { streamState };
}
