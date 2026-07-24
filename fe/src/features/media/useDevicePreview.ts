"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { type SelectOption } from "@/shared/ui";
import {
  evaluateDeviceTest,
  normalizedInputLevel,
  type DevicePermissionState,
  type DeviceTestResult,
} from "./deviceTest";

/** 부모(prejoin 페이지)에 보고하는 장치 테스트 스냅샷. */
export interface DevicePreviewState {
  readonly cameraDeviceId: string | null;
  readonly microphoneDeviceId: string | null;
  readonly result: DeviceTestResult;
}

export interface UseDevicePreviewOptions {
  onStateChange?: (state: DevicePreviewState) => void;
  /** 마이크 입력 레벨 샘플 주기(ms). 테스트에서 짧게 조정할 수 있다. */
  levelSampleIntervalMs?: number;
}

/** DevicePreview 뷰가 렌더에 쓰는 상태·핸들러 묶음. */
export interface DevicePreviewController {
  readonly videoRef: React.RefObject<HTMLVideoElement | null>;
  readonly cameras: readonly SelectOption[];
  readonly microphones: readonly SelectOption[];
  readonly selectedCameraId: string | null;
  readonly selectedMicrophoneId: string | null;
  readonly activeCameraId: string | null;
  readonly activeMicrophoneId: string | null;
  readonly cameraEnabled: boolean;
  readonly microphoneEnabled: boolean;
  readonly cameraHasVideoFrame: boolean;
  readonly microphoneLevel: number;
  /** 카메라 스트림 요청이 진행 중인지 (미리보기 오버레이·실패 안내 유예용). */
  readonly cameraRequesting: boolean;
  /** 마이크 스트림 요청이 진행 중인지 (실패 안내 유예용). */
  readonly microphoneRequesting: boolean;
  readonly result: DeviceTestResult;
  /** 선택한 카메라를 열지 못해 다른 카메라로 대체했을 때의 안내. 없으면 null. */
  readonly cameraNotice: string | null;
  /** 선택한 마이크를 열지 못해 다른 마이크로 대체했을 때의 안내. 없으면 null. */
  readonly microphoneNotice: string | null;
  selectCamera(deviceId: string): void;
  selectMicrophone(deviceId: string): void;
  toggleCamera(): void;
  toggleMicrophone(): void;
  retry(): void;
}

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

/** 오류 객체에서 사용자 안내에 쓸 이름을 뽑는다. */
function errorName(error: unknown): string {
  if (typeof error === "object" && error !== null && "name" in error) {
    return String((error as { name: unknown }).name);
  }
  return "UnknownError";
}

/** openDeviceStream 결과. 지정 장치를 exact 로 열지 못했으면 그 원인 오류 이름을 담는다. */
interface OpenDeviceOutcome {
  readonly stream: MediaStream;
  readonly requestFailure: string | null;
}

/**
 * 한 종류(카메라/마이크)의 스트림을 연다.
 *
 * 지정 장치는 exact → 완화 → 기본 장치 순으로 폴백한다. 블루투스 이어폰처럼 장치가
 * 목록에는 있어도 여는 순간 실패하는 경우(NotReadableError, OverconstrainedError 등)에도
 * 작동하는 장치를 유지해 "연결 안됨"으로 떨어지지 않게 한다. 권한 거부는 폴백해도
 * 소용없고 프롬프트만 반복되므로 즉시 던진다.
 */
async function openDeviceStream(
  kind: "video" | "audio",
  deviceId: string | null,
): Promise<OpenDeviceOutcome> {
  const request = (constraint: MediaTrackConstraints | boolean) =>
    navigator.mediaDevices.getUserMedia(
      kind === "video" ? { video: constraint } : { audio: constraint },
    );

  if (!deviceId) {
    return { stream: await request(true), requestFailure: null };
  }

  try {
    return { stream: await request({ deviceId: { exact: deviceId } }), requestFailure: null };
  } catch (exactError) {
    if (isPermissionDenied(exactError)) {
      throw exactError;
    }
    // 원본 오류를 남겨 실제 장치에서의 실패 원인을 추적할 수 있게 한다.
    console.warn(
      `[prejoin] 지정한 ${kind === "video" ? "카메라" : "마이크"}를 열지 못해 대체 장치로 폴백합니다.`,
      exactError,
    );
    const requestFailure = errorName(exactError);
    try {
      return { stream: await request({ deviceId }), requestFailure };
    } catch (relaxedError) {
      if (isPermissionDenied(relaxedError)) {
        throw relaxedError;
      }
      return { stream: await request(true), requestFailure };
    }
  }
}

/** 비디오 트랙이 유효한 영상 프레임을 내보내는 상태인지 판정한다. */
function trackHasVideoFrame(track: MediaStreamTrack): boolean {
  if (track.readyState !== "live") {
    return false;
  }
  const settings = track.getSettings();
  return (settings.width ?? 0) > 0;
}

function hasMediaDevices(): boolean {
  return typeof navigator !== "undefined" && !!navigator.mediaDevices?.getUserMedia;
}

/**
 * 입장 전 카메라·마이크 미리보기의 미디어 획득·판정 로직.
 *
 * 카메라와 마이크를 각각 독립된 effect 로 열어, 한쪽을 바꿔도 다른 쪽 스트림을
 * 다시 요청하지 않는다(전환 시 미리보기 깜빡임·불필요한 재요청 방지). 판정 결과가
 * 바뀔 때마다 onStateChange 로 부모에 보고한다. 렌더링은 DevicePreview 가 담당한다.
 */
export function useDevicePreview({
  onStateChange,
  levelSampleIntervalMs = 200,
}: UseDevicePreviewOptions): DevicePreviewController {
  const videoRef = useRef<HTMLVideoElement | null>(null);

  const [cameras, setCameras] = useState<readonly SelectOption[]>([]);
  const [microphones, setMicrophones] = useState<readonly SelectOption[]>([]);

  // 사용자가 고른 장치. null 이면 브라우저 기본 장치를 쓴다.
  const [selectedCameraId, setSelectedCameraId] = useState<string | null>(null);
  const [selectedMicrophoneId, setSelectedMicrophoneId] = useState<string | null>(null);

  // 실제로 열린 트랙의 장치 ID. 입장 요청에 포함되는 값이다.
  const [activeCameraId, setActiveCameraId] = useState<string | null>(null);
  const [activeMicrophoneId, setActiveMicrophoneId] = useState<string | null>(null);

  // 선택 장치를 못 열어 다른 장치로 대체했을 때의 사용자 안내.
  const [cameraNotice, setCameraNotice] = useState<string | null>(null);
  const [microphoneNotice, setMicrophoneNotice] = useState<string | null>(null);

  // 미리보기 온오프 토글. 꺼 두면 입장할 수 없고 원인 안내를 보여준다.
  const [cameraEnabled, setCameraEnabled] = useState(true);
  const [microphoneEnabled, setMicrophoneEnabled] = useState(true);
  // 새 스트림을 열 때 현재 토글 상태를 트랙에 적용하기 위한 미러 ref.
  const enabledRef = useRef({ camera: true, microphone: true });
  const videoTrackRef = useRef<MediaStreamTrack | null>(null);
  const audioTrackRef = useRef<MediaStreamTrack | null>(null);

  const [cameraPermission, setCameraPermission] = useState<DevicePermissionState>("prompt");
  const [microphonePermission, setMicrophonePermission] =
    useState<DevicePermissionState>("prompt");
  const [cameraHasVideoFrame, setCameraHasVideoFrame] = useState(false);
  const [microphoneLevel, setMicrophoneLevel] = useState(0);
  // 장치 접근이 가능한 환경에서만 요청 중 상태로 시작한다.
  // 요청 진행 중에는 실패로 판정하지 않고 직전 상태를 유지한다(전환 시 실패 안내 깜빡임 방지).
  const [cameraRequesting, setCameraRequesting] = useState(() => hasMediaDevices());
  const [microphoneRequesting, setMicrophoneRequesting] = useState(() => hasMediaDevices());
  const [retryToken, setRetryToken] = useState(0);

  const retry = useCallback(() => {
    setCameraNotice(null);
    setMicrophoneNotice(null);
    setRetryToken((token) => token + 1);
  }, []);

  // 같은 장치를 다시 고르면(폴백 뒤 재시도 등) 상태가 같아 effect 가 안 돌므로 재시도로 처리한다.
  const selectCamera = useCallback(
    (deviceId: string) => {
      setCameraNotice(null);
      if (deviceId === selectedCameraId) {
        setRetryToken((token) => token + 1);
      } else {
        setSelectedCameraId(deviceId);
      }
    },
    [selectedCameraId],
  );

  const selectMicrophone = useCallback(
    (deviceId: string) => {
      setMicrophoneNotice(null);
      if (deviceId === selectedMicrophoneId) {
        setRetryToken((token) => token + 1);
      } else {
        setSelectedMicrophoneId(deviceId);
      }
    },
    [selectedMicrophoneId],
  );

  const toggleCamera = useCallback(() => {
    setCameraEnabled((enabled) => {
      const next = !enabled;
      enabledRef.current.camera = next;
      if (videoTrackRef.current) {
        videoTrackRef.current.enabled = next;
      }
      return next;
    });
  }, []);

  const toggleMicrophone = useCallback(() => {
    setMicrophoneEnabled((enabled) => {
      const next = !enabled;
      enabledRef.current.microphone = next;
      if (audioTrackRef.current) {
        audioTrackRef.current.enabled = next;
      }
      return next;
    });
  }, []);

  // 권한 허용 후에야 장치 라벨이 채워지므로, 스트림을 연 뒤와 devicechange 때 목록을 갱신한다.
  const refreshDevices = useCallback(async () => {
    if (!hasMediaDevices()) {
      return;
    }
    try {
      const devices = await navigator.mediaDevices.enumerateDevices();
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
    } catch {
      // 목록 조회 실패는 판정에 영향을 주지 않는다.
    }
  }, []);

  // 카메라 스트림 열기. selectedCameraId(사용자 선택)·retryToken 이 바뀔 때만 재요청한다.
  useEffect(() => {
    if (!hasMediaDevices()) {
      return;
    }
    // 이 시점에는 렌더링이 끝나 미리보기 요소가 존재한다. 정리 함수에서도 같은 요소를 쓴다.
    const video = videoRef.current;
    let cancelled = false;
    let stream: MediaStream | null = null;

    async function openCamera() {
      setCameraRequesting(true);
      try {
        const { stream: opened, requestFailure } = await openDeviceStream(
          "video",
          selectedCameraId,
        );
        if (cancelled) {
          opened.getTracks().forEach((track) => track.stop());
          return;
        }
        stream = opened;
        setCameraPermission("granted");

        const track = opened.getVideoTracks()[0] ?? null;
        videoTrackRef.current = track;
        if (track) {
          track.enabled = enabledRef.current.camera;
        }
        const openedId = track?.getSettings().deviceId ?? null;
        setActiveCameraId(openedId);
        setCameraHasVideoFrame(track ? trackHasVideoFrame(track) : false);
        track?.addEventListener("ended", () => setCameraHasVideoFrame(false));

        // 선택한 장치가 아닌 다른 장치가 열렸으면 사용자에게 알린다.
        setCameraNotice(
          selectedCameraId && openedId !== selectedCameraId
            ? `선택한 카메라를 열 수 없어 다른 카메라를 사용하고 있어요. 연결 상태를 확인한 뒤 다시 선택해 주세요.${requestFailure ? ` (오류: ${requestFailure})` : ""}`
            : null,
        );

        if (video) {
          try {
            video.srcObject = opened;
            void video.play()?.catch(() => {});
          } catch {
            // jsdom 등 srcObject 미지원 환경에서는 미리보기만 생략한다.
          }
        }
      } catch (error) {
        if (cancelled) {
          return;
        }
        setActiveCameraId(null);
        setCameraHasVideoFrame(false);
        setCameraNotice(null);
        setCameraPermission(
          isPermissionDenied(error) ? "denied" : await queryPermission("camera"),
        );
      }

      if (!cancelled) {
        setCameraRequesting(false);
        await refreshDevices();
      }
    }

    void openCamera();

    return () => {
      cancelled = true;
      stream?.getTracks().forEach((track) => track.stop());
      videoTrackRef.current = null;
      if (video) {
        try {
          video.srcObject = null;
        } catch {
          // 미리보기 해제 실패는 무시한다.
        }
      }
    };
  }, [selectedCameraId, retryToken, refreshDevices]);

  // 마이크 스트림 열기 + 입력 레벨 측정. selectedMicrophoneId·retryToken·샘플주기에만 반응한다.
  useEffect(() => {
    if (!hasMediaDevices()) {
      return;
    }
    let cancelled = false;
    let stream: MediaStream | null = null;
    let audioContext: AudioContext | null = null;
    let levelTimer: ReturnType<typeof setInterval> | null = null;

    async function openMicrophone() {
      // 전환 요청 중에는 직전 판정(레벨·활성 장치)을 그대로 유지한다("일단 되는 걸로").
      // 새 장치가 열리면 레벨은 감쇠(0.85)로 자연히 새 입력 기준으로 수렴하고,
      // 열기가 실패하면 그때 catch 에서 실패 상태로 전환한다.
      setMicrophoneRequesting(true);
      try {
        const { stream: opened, requestFailure } = await openDeviceStream(
          "audio",
          selectedMicrophoneId,
        );
        if (cancelled) {
          opened.getTracks().forEach((track) => track.stop());
          return;
        }
        stream = opened;
        setMicrophonePermission("granted");

        const track = opened.getAudioTracks()[0] ?? null;
        audioTrackRef.current = track;
        if (track) {
          track.enabled = enabledRef.current.microphone;
        }
        const openedId = track?.getSettings().deviceId ?? null;
        setActiveMicrophoneId(openedId);

        // 선택한 장치가 아닌 다른 장치가 열렸으면 사용자에게 알린다(이어폰 마이크 실패 등).
        setMicrophoneNotice(
          selectedMicrophoneId && openedId !== selectedMicrophoneId
            ? `선택한 마이크를 열 수 없어 다른 마이크를 사용하고 있어요. 연결 상태를 확인한 뒤 다시 선택해 주세요.${requestFailure ? ` (오류: ${requestFailure})` : ""}`
            : null,
        );

        const AudioContextCtor =
          typeof window !== "undefined"
            ? (window.AudioContext ??
              (window as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext)
            : undefined;
        if (AudioContextCtor) {
          audioContext = new AudioContextCtor();
          // 장치 전환 시에는 권한 프롬프트가 없어 AudioContext 가 suspended 로 시작할 수 있다.
          // 이 경우 analyser 가 무음만 읽어 레벨이 0 에 멈추므로 명시적으로 재개한다.
          if (audioContext.state === "suspended") {
            void audioContext.resume?.().catch(() => {});
          }
          const analyser = audioContext.createAnalyser();
          analyser.fftSize = 2048;
          audioContext.createMediaStreamSource(opened).connect(analyser);
          const bytes = new Uint8Array(analyser.fftSize);
          levelTimer = setInterval(() => {
            analyser.getByteTimeDomainData(bytes);
            const peak = normalizedInputLevel(bytes);
            // 말소리가 끊겨도 잠시 유지되도록 완만하게 감쇠시킨다.
            setMicrophoneLevel((prev) => Math.max(peak, prev * 0.85));
          }, levelSampleIntervalMs);
        }
      } catch (error) {
        if (cancelled) {
          return;
        }
        setActiveMicrophoneId(null);
        setMicrophoneLevel(0);
        setMicrophoneNotice(null);
        setMicrophonePermission(
          isPermissionDenied(error) ? "denied" : await queryPermission("microphone"),
        );
      }

      if (!cancelled) {
        setMicrophoneRequesting(false);
        await refreshDevices();
      }
    }

    void openMicrophone();

    return () => {
      cancelled = true;
      if (levelTimer !== null) {
        clearInterval(levelTimer);
      }
      void audioContext?.close().catch(() => {});
      stream?.getTracks().forEach((track) => track.stop());
      audioTrackRef.current = null;
    };
  }, [selectedMicrophoneId, retryToken, levelSampleIntervalMs, refreshDevices]);

  // 장치 연결/해제(이어폰 꽂기 등)에 반응해 목록을 갱신한다.
  useEffect(() => {
    const media = typeof navigator !== "undefined" ? navigator.mediaDevices : undefined;
    if (!media || typeof media.addEventListener !== "function") {
      return;
    }
    const onDeviceChange = () => {
      void refreshDevices();
    };
    media.addEventListener("devicechange", onDeviceChange);
    return () => media.removeEventListener("devicechange", onDeviceChange);
  }, [refreshDevices]);

  const result = evaluateDeviceTest({
    cameraDeviceId: activeCameraId,
    microphoneDeviceId: activeMicrophoneId,
    cameraPermission,
    microphonePermission,
    cameraEnabled,
    microphoneEnabled,
    cameraHasVideoFrame,
    microphoneLevel,
  });

  // 판정 결과가 실제로 바뀔 때만 부모에 보고한다(레벨 샘플링 주기마다 반복 호출 방지).
  // serialized 를 의존성으로 삼아, 스냅샷 내용이 달라진 렌더에서만 effect 가 실행된다.
  const snapshot: DevicePreviewState = {
    cameraDeviceId: activeCameraId,
    microphoneDeviceId: activeMicrophoneId,
    result,
  };
  const serialized = JSON.stringify(snapshot);
  // 최신 스냅샷·콜백은 매 커밋마다 ref 에 반영하고(렌더 중 ref 갱신 금지),
  // 보고 effect 는 serialized 가 바뀐 커밋에서만 실행한다.
  const latestRef = useRef({ snapshot, onStateChange });
  useEffect(() => {
    latestRef.current = { snapshot, onStateChange };
  });
  useEffect(() => {
    latestRef.current.onStateChange?.(latestRef.current.snapshot);
  }, [serialized]);

  return {
    videoRef,
    cameras,
    microphones,
    selectedCameraId,
    selectedMicrophoneId,
    activeCameraId,
    activeMicrophoneId,
    cameraEnabled,
    microphoneEnabled,
    cameraHasVideoFrame,
    microphoneLevel,
    cameraRequesting,
    microphoneRequesting,
    result,
    cameraNotice,
    microphoneNotice,
    selectCamera,
    selectMicrophone,
    toggleCamera,
    toggleMicrophone,
    retry,
  };
}
