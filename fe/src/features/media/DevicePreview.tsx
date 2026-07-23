"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { Select, type SelectOption } from "@/shared/ui";
import {
  evaluateDeviceTest,
  MICROPHONE_LEVEL_THRESHOLD,
  normalizedInputLevel,
  type DevicePermissionState,
  type DeviceTestFailure,
  type DeviceTestResult,
} from "./deviceTest";

/** 부모(prejoin 페이지)에 보고하는 장치 테스트 스냅샷. */
export interface DevicePreviewState {
  readonly cameraDeviceId: string | null;
  readonly microphoneDeviceId: string | null;
  readonly result: DeviceTestResult;
}

/** 실패 코드별 사용자 안내 문구. 입장 버튼 비활성화 사유로 노출한다. */
export const DEVICE_FAILURE_MESSAGES: Record<DeviceTestFailure, string> = {
  CAMERA_PERMISSION_DENIED:
    "카메라 권한이 거부되어 있어요. 주소창 오른쪽의 카메라 아이콘에서 권한을 허용한 뒤 다시 시도해 주세요.",
  MICROPHONE_PERMISSION_DENIED:
    "마이크 권한이 거부되어 있어요. 주소창 오른쪽의 마이크 아이콘에서 권한을 허용한 뒤 다시 시도해 주세요.",
  CAMERA_NOT_SELECTED:
    "사용할 수 있는 카메라를 찾지 못했어요. 카메라 연결 상태를 확인하고 다시 시도해 주세요.",
  MICROPHONE_NOT_SELECTED:
    "사용할 수 있는 마이크를 찾지 못했어요. 마이크 연결 상태를 확인하고 다시 시도해 주세요.",
  CAMERA_STREAM_INVALID:
    "카메라 영상이 나오지 않아요. 다른 카메라를 선택하거나 다시 시도해 주세요.",
  MICROPHONE_LEVEL_TOO_LOW:
    "마이크 입력이 감지되지 않아요. 마이크에 가까이에서 소리를 내어 입력 레벨을 확인해 주세요.",
};

/** permissions API 로 현재 권한 상태를 읽는다. 조회 실패 시 prompt 로 간주한다. */
async function queryPermission(name: "camera" | "microphone"): Promise<DevicePermissionState> {
  try {
    const status = await navigator.permissions.query({ name: name as PermissionName });
    return status.state;
  } catch {
    return "prompt";
  }
}

/** getUserMedia 실패 사유를 권한 거부 여부로 구분한다. */
function isPermissionDenied(error: unknown): boolean {
  return error instanceof DOMException && error.name === "NotAllowedError";
}

/** 비디오 트랙이 유효한 영상 프레임을 내보내는 상태인지 판정한다. */
function trackHasVideoFrame(track: MediaStreamTrack): boolean {
  if (track.readyState !== "live") {
    return false;
  }
  const settings = track.getSettings();
  return (settings.width ?? 0) > 0;
}

/**
 * 입장 전 카메라·마이크 미리보기와 장치 선택, 오류 복구 UI.
 *
 * 카메라와 마이크를 각각 독립적으로 요청해 한쪽 장치가 실패해도 다른 쪽 테스트를
 * 이어갈 수 있게 한다. 판정 결과가 바뀔 때마다 onStateChange 로 부모에 보고한다.
 */
export function DevicePreview({
  onStateChange,
  levelSampleIntervalMs = 200,
}: {
  onStateChange?: (state: DevicePreviewState) => void;
  /** 마이크 입력 레벨 샘플 주기(ms). 테스트에서 짧게 조정할 수 있다. */
  levelSampleIntervalMs?: number;
}) {
  const videoRef = useRef<HTMLVideoElement | null>(null);

  const [cameras, setCameras] = useState<readonly SelectOption[]>([]);
  const [microphones, setMicrophones] = useState<readonly SelectOption[]>([]);

  // 사용자가 고른 장치. null 이면 브라우저 기본 장치를 쓴다.
  const [selectedCameraId, setSelectedCameraId] = useState<string | null>(null);
  const [selectedMicrophoneId, setSelectedMicrophoneId] = useState<string | null>(null);

  // 실제로 열린 트랙의 장치 ID. 입장 요청에 포함되는 값이다.
  const [activeCameraId, setActiveCameraId] = useState<string | null>(null);
  const [activeMicrophoneId, setActiveMicrophoneId] = useState<string | null>(null);

  const [cameraPermission, setCameraPermission] = useState<DevicePermissionState>("prompt");
  const [microphonePermission, setMicrophonePermission] =
    useState<DevicePermissionState>("prompt");
  const [cameraHasVideoFrame, setCameraHasVideoFrame] = useState(false);
  const [microphoneLevel, setMicrophoneLevel] = useState(0);
  // 장치 접근이 가능한 환경에서만 요청 중 상태로 시작한다.
  const [requesting, setRequesting] = useState(
    () => typeof navigator !== "undefined" && !!navigator.mediaDevices?.getUserMedia,
  );
  const [retryToken, setRetryToken] = useState(0);

  const handleRetry = useCallback(() => {
    setRetryToken((token) => token + 1);
  }, []);

  // 장치 스트림 열기: 카메라·마이크를 독립 요청해 한쪽 실패가 다른 쪽을 막지 않게 한다.
  useEffect(() => {
    if (typeof navigator === "undefined" || !navigator.mediaDevices?.getUserMedia) {
      return;
    }

    // 이 시점에는 렌더링이 끝나 미리보기 요소가 존재한다. 정리 함수에서도 같은 요소를 쓴다.
    const video = videoRef.current;
    let cancelled = false;
    const streams: MediaStream[] = [];
    let audioContext: AudioContext | null = null;
    let levelTimer: ReturnType<typeof setInterval> | null = null;

    async function start() {
      setRequesting(true);

      const [videoOutcome, audioOutcome] = await Promise.allSettled([
        navigator.mediaDevices.getUserMedia({
          video: selectedCameraId ? { deviceId: { exact: selectedCameraId } } : true,
        }),
        navigator.mediaDevices.getUserMedia({
          audio: selectedMicrophoneId ? { deviceId: { exact: selectedMicrophoneId } } : true,
        }),
      ]);

      if (cancelled) {
        for (const outcome of [videoOutcome, audioOutcome]) {
          if (outcome.status === "fulfilled") {
            outcome.value.getTracks().forEach((track) => track.stop());
          }
        }
        return;
      }

      // 카메라: 미리보기 연결 + 영상 프레임 유효성 판정
      if (videoOutcome.status === "fulfilled") {
        const stream = videoOutcome.value;
        streams.push(stream);
        setCameraPermission("granted");

        const track = stream.getVideoTracks()[0] ?? null;
        setActiveCameraId(track?.getSettings().deviceId ?? null);
        setCameraHasVideoFrame(track ? trackHasVideoFrame(track) : false);
        track?.addEventListener("ended", () => setCameraHasVideoFrame(false));

        if (video) {
          try {
            video.srcObject = stream;
            void video.play()?.catch(() => {});
          } catch {
            // jsdom 등 srcObject 미지원 환경에서는 미리보기만 생략한다.
          }
        }
      } else {
        setActiveCameraId(null);
        setCameraHasVideoFrame(false);
        setCameraPermission(
          isPermissionDenied(videoOutcome.reason) ? "denied" : await queryPermission("camera"),
        );
      }

      // 마이크: AnalyserNode 로 입력 레벨을 주기 측정한다.
      if (audioOutcome.status === "fulfilled") {
        const stream = audioOutcome.value;
        streams.push(stream);
        setMicrophonePermission("granted");

        const track = stream.getAudioTracks()[0] ?? null;
        setActiveMicrophoneId(track?.getSettings().deviceId ?? null);

        const AudioContextCtor =
          typeof window !== "undefined"
            ? (window.AudioContext ??
              (window as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext)
            : undefined;
        if (AudioContextCtor) {
          audioContext = new AudioContextCtor();
          const analyser = audioContext.createAnalyser();
          analyser.fftSize = 2048;
          audioContext.createMediaStreamSource(stream).connect(analyser);
          const bytes = new Uint8Array(analyser.fftSize);
          levelTimer = setInterval(() => {
            analyser.getByteTimeDomainData(bytes);
            const peak = normalizedInputLevel(bytes);
            // 말소리가 끊겨도 잠시 유지되도록 완만하게 감쇠시킨다.
            setMicrophoneLevel((prev) => Math.max(peak, prev * 0.85));
          }, levelSampleIntervalMs);
        }
      } else {
        setActiveMicrophoneId(null);
        setMicrophoneLevel(0);
        setMicrophonePermission(
          isPermissionDenied(audioOutcome.reason) ? "denied" : await queryPermission("microphone"),
        );
      }

      // 권한 허용 후에야 장치 라벨이 채워지므로 이 시점에 목록을 갱신한다.
      try {
        const devices = await navigator.mediaDevices.enumerateDevices();
        if (!cancelled) {
          setCameras(
            devices
              .filter((device) => device.kind === "videoinput")
              .map((device, index) => ({
                value: device.deviceId,
                label: device.label || `카메라 ${index + 1}`,
              })),
          );
          setMicrophones(
            devices
              .filter((device) => device.kind === "audioinput")
              .map((device, index) => ({
                value: device.deviceId,
                label: device.label || `마이크 ${index + 1}`,
              })),
          );
        }
      } catch {
        // 목록 조회 실패는 판정에 영향을 주지 않는다.
      }

      if (!cancelled) {
        setRequesting(false);
      }
    }

    void start();

    return () => {
      cancelled = true;
      if (levelTimer !== null) {
        clearInterval(levelTimer);
      }
      void audioContext?.close().catch(() => {});
      streams.forEach((stream) => stream.getTracks().forEach((track) => track.stop()));
      if (video) {
        try {
          video.srcObject = null;
        } catch {
          // 미리보기 해제 실패는 무시한다.
        }
      }
    };
  }, [selectedCameraId, selectedMicrophoneId, retryToken, levelSampleIntervalMs]);

  const result = evaluateDeviceTest({
    cameraDeviceId: activeCameraId,
    microphoneDeviceId: activeMicrophoneId,
    cameraPermission,
    microphonePermission,
    cameraHasVideoFrame,
    microphoneLevel,
  });

  // 판정 결과가 실제로 바뀔 때만 부모에 보고한다(레벨 샘플링 주기마다 반복 호출 방지).
  const lastReportedRef = useRef<string | null>(null);
  useEffect(() => {
    const snapshot: DevicePreviewState = {
      cameraDeviceId: activeCameraId,
      microphoneDeviceId: activeMicrophoneId,
      result,
    };
    const serialized = JSON.stringify(snapshot);
    if (serialized !== lastReportedRef.current) {
      lastReportedRef.current = serialized;
      onStateChange?.(snapshot);
    }
  });

  const levelPercent = Math.min(100, Math.round(microphoneLevel * 100));
  const levelPassed = microphoneLevel >= MICROPHONE_LEVEL_THRESHOLD;

  return (
    <div className="flex flex-col gap-4">
      {/* 카메라 미리보기 */}
      <div className="relative min-h-[420px] overflow-hidden rounded-[22px] bg-[#1a1d30]">
        <video
          ref={videoRef}
          data-testid="camera-preview"
          autoPlay
          muted
          playsInline
          className="absolute inset-0 size-full -scale-x-100 object-cover"
        />
        {!cameraHasVideoFrame && (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 px-[30px] text-center">
            <span className="text-4xl">🎥</span>
            <div className="text-lg font-extrabold text-white [text-shadow:0_2px_10px_#0008]">
              {requesting ? "카메라 권한을 확인하고 있어요" : "카메라 영상이 보이지 않아요"}
            </div>
            <div className="text-[13.5px] leading-[1.55] text-white/80">
              {requesting
                ? "브라우저가 권한을 요청하면 허용을 눌러 주세요"
                : "카메라 연결과 권한을 확인한 뒤 다시 시도해 주세요"}
            </div>
          </div>
        )}
        <div className="absolute bottom-[18px] left-[18px] z-stage-chip font-bold">
          🎥 카메라 미리보기
        </div>
      </div>

      {/* 장치 선택 */}
      <div className="z-card-lg flex flex-col gap-2 px-[22px] py-[18px]">
        <label htmlFor="camera-select" className="text-[13px] font-bold text-ink-faint">
          카메라
        </label>
        <Select
          id="camera-select"
          data-testid="camera-select"
          options={cameras}
          value={selectedCameraId ?? activeCameraId}
          onChange={setSelectedCameraId}
          placeholder="카메라 없음"
          disabled={cameras.length === 0}
        />

        <label htmlFor="microphone-select" className="mt-1 text-[13px] font-bold text-ink-faint">
          마이크
        </label>
        <Select
          id="microphone-select"
          data-testid="microphone-select"
          options={microphones}
          value={selectedMicrophoneId ?? activeMicrophoneId}
          onChange={setSelectedMicrophoneId}
          placeholder="마이크 없음"
          disabled={microphones.length === 0}
        />

        {/* 마이크 입력 레벨 */}
        <div className="mt-2 flex items-center gap-[11px]">
          <span className="flex-none text-[13px] font-bold text-ink-faint">입력 레벨</span>
          <div
            data-testid="mic-level"
            data-level-passed={levelPassed}
            className="h-2.5 flex-1 overflow-hidden rounded-[5px] bg-faint"
          >
            <div
              className={`h-full rounded-[5px] transition-[width] duration-150 ${
                levelPassed ? "bg-primary" : "bg-line-soft"
              }`}
              style={{ width: `${levelPercent}%` }}
            />
          </div>
        </div>
      </div>

      {/* 원인별 안내 + 복구 */}
      {result.failures.length > 0 && (
        <div className="flex flex-col gap-2 rounded-[20px] border border-line-mint bg-canvas px-5 py-[18px]">
          <ul className="flex flex-col gap-[9px]">
            {result.failures.map((failure) => (
              <li
                key={failure}
                data-testid={`device-failure-${failure}`}
                className="flex gap-2 text-[12.5px] leading-[1.6] text-ink-faint"
              >
                <span className="shrink-0 text-primary">!</span>
                {DEVICE_FAILURE_MESSAGES[failure]}
              </li>
            ))}
          </ul>
          <button
            type="button"
            data-testid="device-retry-button"
            onClick={handleRetry}
            className="z-btn z-btn-outline z-btn-md mt-1 self-start"
          >
            다시 시도
          </button>
        </div>
      )}
    </div>
  );
}
