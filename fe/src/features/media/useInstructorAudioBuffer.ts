"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { RoomEvent, Track, TrackEvent } from "livekit-client";
import type { Participant, Room, TrackPublication } from "livekit-client";

import { AudioRingBuffer, type AudioClipSnapshot } from "./audioRingBuffer";
import { splitFirstWebmChunk } from "./webmHeader";

/** MediaRecorder dataavailable 간격. 만료 정밀도(±1조각)와 조각 수(300개)를 결정한다. */
export const RECORDER_TIMESLICE_MS = 1_000;

/**
 * Opus 인코딩 비트레이트. 32kbps 면 음성 전사 품질에 충분하면서 300초가 약 1.2MB 로
 * 유지된다 — 클립 업로드가 진행 중인 강의 미디어와 업링크를 나눠 쓰기 때문에
 * 업로드 크기를 작게 유지하는 것이 설계 제약이다.
 */
export const RECORDER_AUDIO_BITS_PER_SECOND = 32_000;

/** requestData 후 dataavailable 이 오지 않는 비정상 상황에서 스냅샷이 매달리지 않게 하는 폴백. */
const SNAPSHOT_REQUEST_DATA_TIMEOUT_MS = 250;

const PREFERRED_MIME_TYPES = ["audio/webm;codecs=opus", "audio/webm"] as const;

/**
 * 캡처 상태.
 *
 * - `idle` — 아직 캡처하지 않는다(마이크 미게시, Room 미연결). 게시되면 회복된다.
 * - `recording` / `paused` — 캡처 중 / 음소거로 일시 중지.
 * - `unavailable` — 이 브라우저·세션에서 캡처가 불가능하다(MediaRecorder 미지원·코덱
 *   없음·recorder 오류). 상위는 이 신호로 코칭 기능을 끄고 수업은 계속 진행한다.
 *   `idle` 과 구분하는 이유는 후자가 곧 회복될 수 있는 정상 상태이기 때문이다.
 */
export type AudioCaptureState = "idle" | "recording" | "paused" | "unavailable";

export interface UseInstructorAudioBufferOptions {
  /** 첫 렌더에서만 읽는다. 이후 변경은 무시된다. */
  readonly windowMs?: number;
  /** 시간 소스 주입점(테스트용). 첫 렌더에서만 읽는다. */
  readonly now?: () => number;
}

export interface InstructorAudioBufferHandle {
  readonly captureState: AudioCaptureState;
  /**
   * 현재 버퍼 내용으로 클립을 만든다. 녹음 중이면 requestData 로 미완성 조각까지
   * 회수해 요청 시점 직전 오디오를 포함한다. 버퍼는 비우지 않는다.
   */
  readonly snapshot: () => Promise<AudioClipSnapshot | null>;
  /** 지금 업로드 가능한 실제 캡처 시간 합(ms). MIN_UPLOADABLE 판정 입력. */
  readonly availableMs: () => number;
}

function pickSupportedMimeType(): string | null {
  if (typeof MediaRecorder === "undefined" || typeof MediaRecorder.isTypeSupported !== "function") {
    return null;
  }
  return PREFERRED_MIME_TYPES.find((type) => MediaRecorder.isTypeSupported(type)) ?? null;
}

/**
 * LiveKit 에 publish 중인 강사 마이크 트랙을 MediaRecorder 로 병렬 캡처해
 * 최근 {@link AUDIO_CLIP_WINDOW_MS} 만큼을 메모리에만 유지한다.
 *
 * - 마이크 음소거(TrackMuted) 시 recorder 를 일시정지한다. LiveKit mute 는 트랙을
 *   끝내지 않고 무음을 계속 내보내므로(stopMicTrackOnMute 기본 false), 방치하면
 *   디지털 무음이 버퍼를 채우고 Whisper 전사에서 환각의 원인이 된다.
 * - publish 트랙의 mediaStreamTrack 을 복제(clone) 없이 그대로 쓴다. 복제본은
 *   LiveKit mute(enabled=false)와 분리돼, 일시정지 처리가 어긋나면 음소거 중 실제
 *   음성이 녹음되는 프라이버시 사고가 된다. 같은 트랙은 mute 시 무음이 되므로
 *   일시정지와 이중 방어가 된다.
 * - 트랙 종료(장치 분리)·마이크 unpublish 는 음소거와 같이 "수집 중지, 버퍼 유지"로
 *   다룬다. 그 사이 클립 요청이 와도 직전까지의 오디오를 그대로 쓸 수 있고, 오래된
 *   조각은 시간 기준으로 알아서 만료된다.
 * - 반대로 새 recorder 가 붙는 순간(장치 전환·재게시)에는 버퍼를 버린다. 인코더가
 *   바뀌면 초기화 구간(헤더)이 달라져 옛 조각과 한 파일로 이어붙일 수 없기 때문이다.
 */
export function useInstructorAudioBuffer(
  room: Room | null,
  options: UseInstructorAudioBufferOptions = {},
): InstructorAudioBufferHandle {
  const [captureState, setCaptureState] = useState<AudioCaptureState>("idle");

  // 버퍼·시계는 첫 렌더에 한 번 만들어 recorder 재생성·리렌더와 무관하게 유지한다.
  // setter 를 쓰지 않는 lazy useState 라 인스턴스가 평생 안정적이다.
  const [{ buffer, now }] = useState(() => {
    const nowFn = options.now ?? (() => Date.now());
    return {
      buffer: new AudioRingBuffer({ windowMs: options.windowMs, now: nowFn }),
      now: nowFn,
    };
  });

  const recorderRef = useRef<MediaRecorder | null>(null);
  /** 직전 조각 경계의 벽시계 시각. 다음 dataavailable 조각의 startMs 가 된다. */
  const boundaryRef = useRef(0);
  /** snapshot() 이 "다음 조각 처리 완료"를 기다리는 resolver 목록. */
  const waitersRef = useRef<Array<() => void>>([]);

  useEffect(() => {
    // room 이 없을 때 상태를 따로 만지지 않는다 — 초기값이 idle 이고,
    // room 이 사라지는 전환은 직전 effect 의 cleanup 이 idle 로 되돌린다.
    if (!room) {
      return;
    }

    let recorder: MediaRecorder | null = null;
    let headerReady = false;
    // recorder 세대. 교체·정리 후 도착한 이전 세대의 비동기 작업이 새 버퍼를 오염시키지 않게 한다.
    let generation = 0;
    // 조각 처리(비동기 헤더 분리 포함)를 도착 순서대로 직렬화하는 체인.
    let queue: Promise<void> = Promise.resolve();

    const getMicrophoneTrack = () =>
      room.localParticipant.getTrackPublication(Track.Source.Microphone)?.track ?? null;
    let observedTrack: ReturnType<typeof getMicrophoneTrack> = null;

    const drainWaiters = () => {
      waitersRef.current.splice(0).forEach((resolve) => resolve());
    };

    const enqueueChunk = (
      chunk: Blob,
      startMs: number,
      endMs: number,
      chunkGeneration: number,
    ) => {
      queue = queue
        .then(async () => {
          if (chunkGeneration !== generation || chunk.size === 0) {
            return;
          }
          if (headerReady) {
            buffer.append(chunk, startMs, endMs);
            return;
          }
          const { header, body } = await splitFirstWebmChunk(chunk);
          if (chunkGeneration !== generation) {
            return;
          }
          buffer.setHeader(header);
          headerReady = true;
          if (body !== null) {
            buffer.append(body, startMs, endMs);
          }
        })
        .finally(drainWaiters);
    };

    /**
     * recorder 를 정지한다.
     *
     * `discardBuffer` 는 이후 새 recorder 가 붙을 때만 true 다 — 인코더가 바뀌면 초기화
     * 구간(헤더)이 달라져 옛 조각과 한 파일로 이어붙일 수 없기 때문이다. 반대로 장치가
     * 빠지거나 마이크를 내린 경우에는 유지한다. 그 사이 클립 요청이 와도 직전까지의
     * 오디오는 그대로 쓸 수 있고, 오래된 조각은 시간 기준으로 알아서 만료된다.
     */
    function stopRecorder(discardBuffer: boolean) {
      generation += 1;
      if (observedTrack !== null) {
        observedTrack.off(TrackEvent.Restarted, handleTrackRestarted);
        observedTrack.off(TrackEvent.Ended, handleTrackEnded);
        observedTrack = null;
      }
      if (recorder !== null && recorder.state !== "inactive") {
        try {
          recorder.stop();
        } catch {
          // 이미 정지된 recorder 는 무시한다.
        }
      }
      recorder = null;
      recorderRef.current = null;
      if (discardBuffer) {
        headerReady = false;
        buffer.reset();
      }
      // recorder 가 사라지면 dataavailable 도 오지 않으므로 스냅샷 대기자를 깨워 준다.
      drainWaiters();
    }

    function startRecorder() {
      // 새 recorder 는 새 헤더를 만든다. 옛 조각과 섞이지 않도록 여기서만 버퍼를 버린다.
      stopRecorder(true);

      const publication = room?.localParticipant.getTrackPublication(Track.Source.Microphone);
      const track = getMicrophoneTrack();
      const mediaStreamTrack = track?.mediaStreamTrack;
      if (
        publication === undefined ||
        track === null ||
        mediaStreamTrack === undefined ||
        mediaStreamTrack.readyState === "ended"
      ) {
        setCaptureState("idle");
        return;
      }

      const mimeType = pickSupportedMimeType();
      if (mimeType === null || typeof MediaStream === "undefined") {
        setCaptureState("unavailable");
        return;
      }

      let created: MediaRecorder;
      try {
        created = new MediaRecorder(new MediaStream([mediaStreamTrack]), {
          mimeType,
          audioBitsPerSecond: RECORDER_AUDIO_BITS_PER_SECOND,
        });
      } catch (error) {
        // 캡처 실패가 수업을 중단시켜서는 안 된다. 코칭만 끄고 계속 진행한다.
        console.warn("[audio-clip] 오디오 버퍼를 시작할 수 없어 코칭을 비활성화합니다.", error);
        setCaptureState("unavailable");
        return;
      }

      const chunkGeneration = generation;
      created.ondataavailable = (event: BlobEvent) => {
        // 교체된 recorder가 stop 직후 내보내는 잔여 조각이 새 recorder 의 경계를 흔들지 않게 한다.
        if (chunkGeneration !== generation) {
          return;
        }
        const endMs = now();
        const startMs = boundaryRef.current;
        boundaryRef.current = endMs;
        enqueueChunk(event.data, startMs, endMs, chunkGeneration);
      };
      created.onerror = (event) => {
        if (chunkGeneration !== generation) {
          return;
        }
        // 인코더가 깨진 뒤의 조각은 컨테이너 정합성을 보장할 수 없어 버퍼째 버린다.
        console.warn("[audio-clip] recorder 오류로 코칭을 비활성화합니다.", event);
        stopRecorder(true);
        setCaptureState("unavailable");
      };

      observedTrack = track;
      track.on(TrackEvent.Restarted, handleTrackRestarted);
      track.on(TrackEvent.Ended, handleTrackEnded);

      recorder = created;
      recorderRef.current = created;
      boundaryRef.current = now();
      created.start(RECORDER_TIMESLICE_MS);

      if (publication.isMuted) {
        // 음소거 상태로 시작하면 즉시 일시정지해 무음이 버퍼를 채우지 않게 한다.
        created.pause();
        setCaptureState("paused");
      } else {
        setCaptureState("recording");
      }
    }

    function handleTrackRestarted() {
      startRecorder();
    }

    function handleTrackEnded() {
      // 장치 분리·연결 끊김: 수집만 멈추고 직전까지의 오디오는 남긴다.
      stopRecorder(false);
      setCaptureState("paused");
    }

    const isLocalMicrophone = (publication: TrackPublication, participant: Participant) =>
      participant === room.localParticipant && publication.source === Track.Source.Microphone;

    const handleLocalTrackPublished = (publication: TrackPublication) => {
      if (publication.source === Track.Source.Microphone) {
        startRecorder();
      }
    };

    const handleLocalTrackUnpublished = (publication: TrackPublication) => {
      if (publication.source === Track.Source.Microphone) {
        // 마이크를 내린 것도 "수집 중지, 버퍼 유지"다(음소거와 같은 취급).
        stopRecorder(false);
        setCaptureState("paused");
      }
    };

    const handleTrackMuted = (publication: TrackPublication, participant: Participant) => {
      if (!isLocalMicrophone(publication, participant)) {
        return;
      }
      if (recorder === null || recorder.state !== "recording") {
        return;
      }
      try {
        // 일시정지 전에 미완성 조각을 회수해 음소거 직전 오디오까지 버퍼에 남긴다.
        recorder.requestData();
        recorder.pause();
      } catch {
        return;
      }
      setCaptureState("paused");
    };

    const handleTrackUnmuted = (publication: TrackPublication, participant: Participant) => {
      if (!isLocalMicrophone(publication, participant)) {
        return;
      }
      if (recorder === null || recorder.state !== "paused") {
        return;
      }
      try {
        recorder.resume();
      } catch {
        return;
      }
      // 음소거 공백이 다음 조각 길이에 포함되지 않게 경계를 재개 시점으로 옮긴다.
      boundaryRef.current = now();
      setCaptureState("recording");
    };

    room.on(RoomEvent.LocalTrackPublished, handleLocalTrackPublished);
    room.on(RoomEvent.LocalTrackUnpublished, handleLocalTrackUnpublished);
    room.on(RoomEvent.TrackMuted, handleTrackMuted);
    room.on(RoomEvent.TrackUnmuted, handleTrackUnmuted);

    startRecorder();

    return () => {
      room.off(RoomEvent.LocalTrackPublished, handleLocalTrackPublished);
      room.off(RoomEvent.LocalTrackUnpublished, handleLocalTrackUnpublished);
      room.off(RoomEvent.TrackMuted, handleTrackMuted);
      room.off(RoomEvent.TrackUnmuted, handleTrackUnmuted);
      // Room 교체·언마운트: 이 세션의 오디오는 더 쓰이지 않으므로 메모리를 즉시 반납한다.
      stopRecorder(true);
      setCaptureState("idle");
    };
  }, [room, buffer, now]);

  const snapshot = useCallback(async (): Promise<AudioClipSnapshot | null> => {
    const recorder = recorderRef.current;
    if (recorder !== null && recorder.state === "recording") {
      const processed = new Promise<void>((resolve) => {
        waitersRef.current.push(resolve);
      });
      try {
        recorder.requestData();
        await Promise.race([
          processed,
          new Promise<void>((resolve) => {
            setTimeout(resolve, SNAPSHOT_REQUEST_DATA_TIMEOUT_MS);
          }),
        ]);
      } catch {
        // requestData 가 상태 경합으로 거부되면 이미 쌓인 조각만으로 스냅샷한다.
      }
    }
    return buffer.snapshot();
  }, [buffer]);

  const availableMs = useCallback(() => buffer.availableMs(), [buffer]);

  return { captureState, snapshot, availableMs };
}
