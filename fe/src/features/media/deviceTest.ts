/**
 * 카메라·마이크 장치 테스트 판정.
 *
 * 권한 상태, 장치 선택 여부, 카메라 영상 유효성, 마이크 입력 레벨을 종합해
 * 입장 가능(통과) 여부와 실패 사유를 반환한다. 하나라도 실패하면 입장 버튼을
 * 비활성화하고 사유별 안내를 노출한다. 서버는 prejoin 검증 실패를 400으로 거절한다.
 */

export type DevicePermissionState = 'granted' | 'denied' | 'prompt';

export type DeviceTestFailure =
  | 'CAMERA_PERMISSION_DENIED'
  | 'MICROPHONE_PERMISSION_DENIED'
  | 'CAMERA_NOT_SELECTED'
  | 'MICROPHONE_NOT_SELECTED'
  | 'CAMERA_DISABLED'
  | 'MICROPHONE_DISABLED'
  | 'CAMERA_STREAM_INVALID'
  | 'MICROPHONE_LEVEL_TOO_LOW';

export interface DeviceTestInput {
  readonly cameraDeviceId: string | null;
  readonly microphoneDeviceId: string | null;
  readonly cameraPermission: DevicePermissionState;
  readonly microphonePermission: DevicePermissionState;
  /** 사용자가 카메라를 켜 둔 상태인지 (미리보기 토글). */
  readonly cameraEnabled: boolean;
  /** 사용자가 마이크를 켜 둔 상태인지 (미리보기 토글). */
  readonly microphoneEnabled: boolean;
  /** 카메라가 유효한 영상 프레임을 내보내는지 (예: 트랙 활성 + 해상도 > 0). */
  readonly cameraHasVideoFrame: boolean;
  /** 측정된 마이크 입력 레벨 (0~1 로 정규화된 피크). */
  readonly microphoneLevel: number;
}

export interface DeviceTestResult {
  readonly passed: boolean;
  readonly failures: readonly DeviceTestFailure[];
}

/** 유효한 마이크 입력으로 인정하는 최소 정규화 레벨(0~1). 이 값 미만이면 무음으로 본다. */
export const MICROPHONE_LEVEL_THRESHOLD = 0.02;

/**
 * 장치 테스트 결과를 판정한다.
 *
 * 권한이 거부된 경우에는 스트림/레벨 실패를 중복으로 보고하지 않고(근본 원인은 권한),
 * 사용자가 장치를 꺼 둔 경우에도 스트림/레벨 실패 대신 꺼짐만 보고한다(근본 원인은 토글).
 */
export function evaluateDeviceTest(input: DeviceTestInput): DeviceTestResult {
  const failures: DeviceTestFailure[] = [];

  const cameraDenied = input.cameraPermission === 'denied';
  const microphoneDenied = input.microphonePermission === 'denied';

  if (cameraDenied) {
    failures.push('CAMERA_PERMISSION_DENIED');
  }
  if (microphoneDenied) {
    failures.push('MICROPHONE_PERMISSION_DENIED');
  }

  if (!input.cameraDeviceId) {
    failures.push('CAMERA_NOT_SELECTED');
  }
  if (!input.microphoneDeviceId) {
    failures.push('MICROPHONE_NOT_SELECTED');
  }

  if (!cameraDenied && input.cameraDeviceId && !input.cameraEnabled) {
    failures.push('CAMERA_DISABLED');
  }
  if (!microphoneDenied && input.microphoneDeviceId && !input.microphoneEnabled) {
    failures.push('MICROPHONE_DISABLED');
  }

  if (
    !cameraDenied &&
    input.cameraDeviceId &&
    input.cameraEnabled &&
    !input.cameraHasVideoFrame
  ) {
    failures.push('CAMERA_STREAM_INVALID');
  }

  if (
    !microphoneDenied &&
    input.microphoneDeviceId &&
    input.microphoneEnabled &&
    input.microphoneLevel < MICROPHONE_LEVEL_THRESHOLD
  ) {
    failures.push('MICROPHONE_LEVEL_TOO_LOW');
  }

  return { passed: failures.length === 0, failures };
}

/**
 * AnalyserNode 의 시간영역 바이트 데이터(0~255, 무음은 128 중심)에서
 * 정규화된 피크 입력 레벨(0~1)을 계산한다. 마이크 레벨 판정의 입력으로 쓴다.
 */
export function normalizedInputLevel(timeDomainBytes: ArrayLike<number>): number {
  let peak = 0;
  for (let i = 0; i < timeDomainBytes.length; i += 1) {
    const centered = Math.abs(timeDomainBytes[i] - 128) / 128;
    if (centered > peak) {
      peak = centered;
    }
  }
  return peak;
}
