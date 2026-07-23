import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { DevicePreview, type DevicePreviewState } from './DevicePreview';

/** 시간영역 바이트 값. 128 은 무음, 128 에서 멀수록 큰 입력. */
let analyserByte = 128;

class FakeAudioContext {
  createAnalyser() {
    return {
      fftSize: 2048,
      getByteTimeDomainData: (bytes: Uint8Array) => bytes.fill(analyserByte),
    };
  }

  createMediaStreamSource() {
    return { connect: () => {} };
  }

  close() {
    return Promise.resolve();
  }
}

function fakeTrack(kind: 'video' | 'audio', deviceId: string): MediaStreamTrack {
  return {
    kind,
    readyState: 'live',
    getSettings: () => ({ deviceId, width: kind === 'video' ? 1280 : undefined }),
    stop: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  } as unknown as MediaStreamTrack;
}

function fakeStream(tracks: MediaStreamTrack[]): MediaStream {
  return {
    getTracks: () => tracks,
    getVideoTracks: () => tracks.filter((track) => track.kind === 'video'),
    getAudioTracks: () => tracks.filter((track) => track.kind === 'audio'),
  } as unknown as MediaStream;
}

function notAllowedError(): DOMException {
  return new DOMException('Permission denied', 'NotAllowedError');
}

function notFoundError(): DOMException {
  return new DOMException('Requested device not found', 'NotFoundError');
}

const DEFAULT_DEVICES = [
  { deviceId: 'cam-1', kind: 'videoinput', label: '테스트 카메라' },
  { deviceId: 'mic-1', kind: 'audioinput', label: '테스트 마이크' },
] as MediaDeviceInfo[];

let getUserMedia: ReturnType<typeof vi.fn>;

/** 카메라({video})·마이크({audio}) 독립 요청을 각각의 결과로 응답하는 목을 만든다. */
function stubMediaDevices({
  video,
  audio,
  devices = DEFAULT_DEVICES,
}: {
  video: () => Promise<MediaStream>;
  audio: () => Promise<MediaStream>;
  devices?: MediaDeviceInfo[];
}) {
  getUserMedia = vi.fn((constraints: MediaStreamConstraints) =>
    constraints.video ? video() : audio(),
  );
  Object.defineProperty(navigator, 'mediaDevices', {
    value: { getUserMedia, enumerateDevices: () => Promise.resolve(devices) },
    configurable: true,
  });
}

beforeEach(() => {
  analyserByte = 128;
  vi.stubGlobal('AudioContext', FakeAudioContext);
  Object.defineProperty(navigator, 'permissions', {
    value: { query: vi.fn().mockResolvedValue({ state: 'prompt' }) },
    configurable: true,
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  Reflect.deleteProperty(navigator, 'mediaDevices');
  Reflect.deleteProperty(navigator, 'permissions');
});

describe('DevicePreview', () => {
  it('카메라 영상과 마이크 입력이 모두 유효하면 통과를 보고한다', async () => {
    analyserByte = 200; // 유효한 입력 레벨
    stubMediaDevices({
      video: () => Promise.resolve(fakeStream([fakeTrack('video', 'cam-1')])),
      audio: () => Promise.resolve(fakeStream([fakeTrack('audio', 'mic-1')])),
    });

    const onStateChange = vi.fn<(state: DevicePreviewState) => void>();
    render(<DevicePreview onStateChange={onStateChange} levelSampleIntervalMs={10} />);

    await waitFor(() => {
      const last = onStateChange.mock.lastCall?.[0];
      expect(last?.result.passed).toBe(true);
      expect(last?.cameraDeviceId).toBe('cam-1');
      expect(last?.microphoneDeviceId).toBe('mic-1');
    });

    // 권한 허용 후 장치 목록 라벨이 채워진다.
    expect(await screen.findByText('테스트 카메라')).toBeInTheDocument();
    expect(await screen.findByText('테스트 마이크')).toBeInTheDocument();
  });

  it('권한이 거부되면 원인별 안내와 다시 시도 버튼을 보여준다', async () => {
    stubMediaDevices({
      video: () => Promise.reject(notAllowedError()),
      audio: () => Promise.reject(notAllowedError()),
    });

    const onStateChange = vi.fn<(state: DevicePreviewState) => void>();
    render(<DevicePreview onStateChange={onStateChange} levelSampleIntervalMs={10} />);

    expect(
      await screen.findByTestId('device-failure-CAMERA_PERMISSION_DENIED'),
    ).toBeInTheDocument();
    expect(
      await screen.findByTestId('device-failure-MICROPHONE_PERMISSION_DENIED'),
    ).toBeInTheDocument();
    expect(screen.getByTestId('device-retry-button')).toBeInTheDocument();
    expect(onStateChange.mock.lastCall?.[0].result.passed).toBe(false);
  });

  it('카메라가 없으면 카메라 실패 안내를 보여주되 마이크 테스트는 계속한다', async () => {
    analyserByte = 200;
    stubMediaDevices({
      video: () => Promise.reject(notFoundError()),
      audio: () => Promise.resolve(fakeStream([fakeTrack('audio', 'mic-1')])),
    });

    const onStateChange = vi.fn<(state: DevicePreviewState) => void>();
    render(<DevicePreview onStateChange={onStateChange} levelSampleIntervalMs={10} />);

    expect(await screen.findByTestId('device-failure-CAMERA_NOT_SELECTED')).toBeInTheDocument();
    await waitFor(() => {
      const last = onStateChange.mock.lastCall?.[0];
      expect(last?.result.passed).toBe(false);
      expect(last?.microphoneDeviceId).toBe('mic-1');
      expect(last?.result.failures).not.toContain('MICROPHONE_LEVEL_TOO_LOW');
    });
  });

  it('마이크 입력이 무음이면 입력 레벨 실패 안내를 보여준다', async () => {
    analyserByte = 128; // 무음
    stubMediaDevices({
      video: () => Promise.resolve(fakeStream([fakeTrack('video', 'cam-1')])),
      audio: () => Promise.resolve(fakeStream([fakeTrack('audio', 'mic-1')])),
    });

    const onStateChange = vi.fn<(state: DevicePreviewState) => void>();
    render(<DevicePreview onStateChange={onStateChange} levelSampleIntervalMs={10} />);

    expect(
      await screen.findByTestId('device-failure-MICROPHONE_LEVEL_TOO_LOW'),
    ).toBeInTheDocument();
    expect(screen.getByTestId('mic-level')).toHaveAttribute('data-level-passed', 'false');
    expect(onStateChange.mock.lastCall?.[0].result.passed).toBe(false);
  });

  it('카메라·마이크 토글을 끄면 꺼짐 안내가 보이고 통과가 깨진다', async () => {
    analyserByte = 200;
    stubMediaDevices({
      video: () => Promise.resolve(fakeStream([fakeTrack('video', 'cam-1')])),
      audio: () => Promise.resolve(fakeStream([fakeTrack('audio', 'mic-1')])),
    });

    const onStateChange = vi.fn<(state: DevicePreviewState) => void>();
    render(<DevicePreview onStateChange={onStateChange} levelSampleIntervalMs={10} />);
    await waitFor(() => expect(onStateChange.mock.lastCall?.[0].result.passed).toBe(true));

    fireEvent.click(screen.getByTestId('camera-toggle'));
    expect(await screen.findByTestId('device-failure-CAMERA_DISABLED')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('microphone-toggle'));
    expect(await screen.findByTestId('device-failure-MICROPHONE_DISABLED')).toBeInTheDocument();
    expect(onStateChange.mock.lastCall?.[0].result.passed).toBe(false);

    // 다시 켜면 통과 상태로 복구된다.
    fireEvent.click(screen.getByTestId('camera-toggle'));
    fireEvent.click(screen.getByTestId('microphone-toggle'));
    await waitFor(() => expect(onStateChange.mock.lastCall?.[0].result.passed).toBe(true));
  });

  it('다시 시도를 누르면 장치 요청을 다시 수행한다', async () => {
    stubMediaDevices({
      video: () => Promise.reject(notAllowedError()),
      audio: () => Promise.reject(notAllowedError()),
    });

    render(<DevicePreview levelSampleIntervalMs={10} />);
    const retryButton = await screen.findByTestId('device-retry-button');
    const callsBeforeRetry = getUserMedia.mock.calls.length;

    fireEvent.click(retryButton);

    await waitFor(() => {
      expect(getUserMedia.mock.calls.length).toBeGreaterThan(callsBeforeRetry);
    });
  });
});
