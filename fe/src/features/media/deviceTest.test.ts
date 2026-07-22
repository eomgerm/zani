import { describe, expect, it } from 'vitest';

import {
  type DeviceTestInput,
  MICROPHONE_LEVEL_THRESHOLD,
  evaluateDeviceTest,
  normalizedInputLevel,
} from './deviceTest';

function validInput(overrides: Partial<DeviceTestInput> = {}): DeviceTestInput {
  return {
    cameraDeviceId: 'camera-1',
    microphoneDeviceId: 'mic-1',
    cameraPermission: 'granted',
    microphonePermission: 'granted',
    cameraHasVideoFrame: true,
    microphoneLevel: 0.5,
    ...overrides,
  };
}

describe('evaluateDeviceTest', () => {
  it('영상과 마이크 입력이 모두 유효하면 통과한다', () => {
    const result = evaluateDeviceTest(validInput());
    expect(result.passed).toBe(true);
    expect(result.failures).toEqual([]);
  });

  it('카메라 권한 거부를 감지한다', () => {
    const result = evaluateDeviceTest(validInput({ cameraPermission: 'denied' }));
    expect(result.passed).toBe(false);
    expect(result.failures).toContain('CAMERA_PERMISSION_DENIED');
  });

  it('마이크 권한 거부를 감지한다', () => {
    const result = evaluateDeviceTest(validInput({ microphonePermission: 'denied' }));
    expect(result.failures).toContain('MICROPHONE_PERMISSION_DENIED');
  });

  it('카메라 미선택을 감지한다', () => {
    const result = evaluateDeviceTest(validInput({ cameraDeviceId: null }));
    expect(result.failures).toContain('CAMERA_NOT_SELECTED');
  });

  it('마이크 미선택을 감지한다', () => {
    const result = evaluateDeviceTest(validInput({ microphoneDeviceId: null }));
    expect(result.failures).toContain('MICROPHONE_NOT_SELECTED');
  });

  it('권한·장치가 정상인데 카메라 영상이 없으면 STREAM_INVALID', () => {
    const result = evaluateDeviceTest(validInput({ cameraHasVideoFrame: false }));
    expect(result.failures).toContain('CAMERA_STREAM_INVALID');
  });

  it('마이크 입력 레벨이 임계 미만이면 LEVEL_TOO_LOW', () => {
    const result = evaluateDeviceTest(
      validInput({ microphoneLevel: MICROPHONE_LEVEL_THRESHOLD - 0.001 }),
    );
    expect(result.failures).toContain('MICROPHONE_LEVEL_TOO_LOW');
  });

  it('임계값과 같은 레벨은 유효로 본다(경계값)', () => {
    const result = evaluateDeviceTest(validInput({ microphoneLevel: MICROPHONE_LEVEL_THRESHOLD }));
    expect(result.failures).not.toContain('MICROPHONE_LEVEL_TOO_LOW');
  });

  it('권한이 거부되면 같은 장치의 STREAM/LEVEL 실패는 중복 보고하지 않는다', () => {
    const result = evaluateDeviceTest(
      validInput({
        cameraPermission: 'denied',
        cameraHasVideoFrame: false,
        microphonePermission: 'denied',
        microphoneLevel: 0,
      }),
    );
    expect(result.failures).toContain('CAMERA_PERMISSION_DENIED');
    expect(result.failures).toContain('MICROPHONE_PERMISSION_DENIED');
    expect(result.failures).not.toContain('CAMERA_STREAM_INVALID');
    expect(result.failures).not.toContain('MICROPHONE_LEVEL_TOO_LOW');
  });

  it('prompt 상태에서도 장치가 정상이면 통과한다', () => {
    const result = evaluateDeviceTest(
      validInput({ cameraPermission: 'prompt', microphonePermission: 'prompt' }),
    );
    expect(result.passed).toBe(true);
  });
});

describe('normalizedInputLevel', () => {
  it('완전한 무음(모두 128)은 0 이다', () => {
    const silence = new Uint8Array(16).fill(128);
    expect(normalizedInputLevel(silence)).toBe(0);
  });

  it('최대 진폭(0 또는 255)은 1 에 수렴한다', () => {
    expect(normalizedInputLevel([0])).toBeCloseTo(1, 5);
    expect(normalizedInputLevel([255])).toBeCloseTo(0.9922, 3);
  });

  it('여러 샘플 중 피크를 취한다', () => {
    expect(normalizedInputLevel([128, 128, 160, 128])).toBeCloseTo(0.25, 5);
  });

  it('빈 입력은 0 이다', () => {
    expect(normalizedInputLevel([])).toBe(0);
  });
});
