import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { DevicePreview, type DevicePreviewState } from './DevicePreview';

/** 시간영역 바이트 값. 128 은 무음, 128 에서 멀수록 큰 입력. */
let analyserByte = 128;
/** resume() 호출 횟수. 실제 브라우저처럼 suspended 로 시작하므로 재개돼야 입력이 흐른다. */
let resumeCalls = 0;

class FakeAudioContext {
  state: 'suspended' | 'running' = 'suspended';

  resume() {
    this.state = 'running';
    resumeCalls += 1;
    return Promise.resolve();
  }

  createAnalyser() {
    return {
      fftSize: 2048,
      // suspended 인 동안은 무음(128)만 읽힌다. resume 후에야 실제 입력이 흐른다.
      getByteTimeDomainData: (bytes: Uint8Array) =>
        bytes.fill(this.state === 'running' ? analyserByte : 128),
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
  // EventTarget 을 상속해 실제 addEventListener/dispatchEvent('ended') 가 동작하게 한다
  // (장치 분리 시 트랙 ended 처리를 테스트에서 재현하기 위함).
  return Object.assign(new EventTarget(), {
    kind,
    readyState: 'live',
    getSettings: () => ({ deviceId, width: kind === 'video' ? 1280 : undefined }),
    stop: vi.fn(),
  }) as unknown as MediaStreamTrack;
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
  resumeCalls = 0;
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

    // 권한 허용 후 화살표 메뉴를 열면 장치 목록 라벨이 채워져 있다.
    const cameraSelect = screen.getByTestId('camera-select');
    await waitFor(() => expect(cameraSelect).toBeEnabled());
    fireEvent.click(cameraSelect);
    expect(await screen.findByText('테스트 카메라')).toBeInTheDocument();

    const microphoneSelect = screen.getByTestId('microphone-select');
    fireEvent.click(microphoneSelect);
    expect(await screen.findByText('테스트 마이크')).toBeInTheDocument();
  });

  it('suspended 로 시작한 AudioContext 를 재개해 마이크 입력을 인식한다', async () => {
    // 장치 전환 시 권한 프롬프트가 없어 AudioContext 가 suspended 로 시작하는 상황을 재현한다.
    // resume 하지 않으면 analyser 가 무음만 읽어 레벨이 0 에 멈춘다(이어폰 마이크 미인식 버그).
    analyserByte = 200;
    stubMediaDevices({
      video: () => Promise.resolve(fakeStream([fakeTrack('video', 'cam-1')])),
      audio: () => Promise.resolve(fakeStream([fakeTrack('audio', 'mic-1')])),
    });

    const onStateChange = vi.fn<(state: DevicePreviewState) => void>();
    render(<DevicePreview onStateChange={onStateChange} levelSampleIntervalMs={10} />);

    await waitFor(() => {
      expect(onStateChange.mock.lastCall?.[0].result.passed).toBe(true);
    });
    expect(resumeCalls).toBeGreaterThan(0);
    expect(screen.getByTestId('mic-level')).toHaveAttribute('data-level-passed', 'true');
  });

  it('목록에서 다른 마이크(이어폰)를 선택하면 exact 제약 실패 없이 전환된다', async () => {
    analyserByte = 200;
    const devices = [
      { deviceId: 'cam-1', kind: 'videoinput', label: '테스트 카메라' },
      { deviceId: 'mic-1', kind: 'audioinput', label: '내장 마이크' },
      { deviceId: 'mic-2', kind: 'audioinput', label: '이어폰 마이크' },
    ] as MediaDeviceInfo[];

    // 이어폰을 exact 로 열려고 하면 거부(블루투스 프로파일 전환 등)하지만,
    // 완화된 제약으로는 열린다. 실제 장치에서 흔한 OverconstrainedError 를 재현한다.
    getUserMedia = vi.fn((constraints: MediaStreamConstraints) => {
      if (constraints.video) {
        return Promise.resolve(fakeStream([fakeTrack('video', 'cam-1')]));
      }
      const req = typeof constraints.audio === 'object' ? constraints.audio.deviceId : undefined;
      if (req && typeof req === 'object' && 'exact' in req && req.exact === 'mic-2') {
        return Promise.reject(new DOMException('over-constrained', 'OverconstrainedError'));
      }
      const opened = req === 'mic-2' ? 'mic-2' : 'mic-1';
      return Promise.resolve(fakeStream([fakeTrack('audio', opened)]));
    });
    Object.defineProperty(navigator, 'mediaDevices', {
      value: { getUserMedia, enumerateDevices: () => Promise.resolve(devices) },
      configurable: true,
    });

    const onStateChange = vi.fn<(state: DevicePreviewState) => void>();
    render(<DevicePreview onStateChange={onStateChange} levelSampleIntervalMs={10} />);

    const micSelect = await screen.findByTestId('microphone-select');
    await waitFor(() => expect(micSelect).toBeEnabled());

    fireEvent.click(micSelect);
    fireEvent.click(await screen.findByText('이어폰 마이크'));

    // 이어폰으로 전환되면 '연결 안됨'(NOT_SELECTED) 이 뜨지 않고 통과 상태가 유지된다.
    await waitFor(() => {
      expect(onStateChange.mock.lastCall?.[0].microphoneDeviceId).toBe('mic-2');
    });
    // 요청한 장치가 그대로 열렸으므로 대체 안내는 뜨지 않는다.
    expect(screen.queryByTestId('microphone-fallback-notice')).toBeNull();
    expect(onStateChange.mock.lastCall?.[0].result.passed).toBe(true);
  });

  it('이어폰 마이크를 열 수 없으면(NotReadableError) 기본 마이크로 폴백하고 안내를 보여준다', async () => {
    analyserByte = 200;
    const devices = [
      { deviceId: 'cam-1', kind: 'videoinput', label: '테스트 카메라' },
      { deviceId: 'mic-1', kind: 'audioinput', label: '내장 마이크' },
      { deviceId: 'mic-2', kind: 'audioinput', label: '이어폰 마이크' },
    ] as MediaDeviceInfo[];

    // 블루투스 이어폰이 목록에는 있어도 여는 순간 실패하는 상황(HFP 캡처 전환 실패·점유)을 재현한다.
    // exact/완화 어느 형태로 요청해도 mic-2 는 NotReadableError 로 거부된다.
    getUserMedia = vi.fn((constraints: MediaStreamConstraints) => {
      if (constraints.video) {
        return Promise.resolve(fakeStream([fakeTrack('video', 'cam-1')]));
      }
      const req = typeof constraints.audio === 'object' ? constraints.audio.deviceId : undefined;
      const wantsEarphone =
        req === 'mic-2' || (typeof req === 'object' && req !== null && 'exact' in req && req.exact === 'mic-2');
      if (wantsEarphone) {
        return Promise.reject(new DOMException('Could not start audio source', 'NotReadableError'));
      }
      return Promise.resolve(fakeStream([fakeTrack('audio', 'mic-1')]));
    });
    Object.defineProperty(navigator, 'mediaDevices', {
      value: { getUserMedia, enumerateDevices: () => Promise.resolve(devices) },
      configurable: true,
    });

    const onStateChange = vi.fn<(state: DevicePreviewState) => void>();
    render(<DevicePreview onStateChange={onStateChange} levelSampleIntervalMs={10} />);

    const micSelect = await screen.findByTestId('microphone-select');
    await waitFor(() => expect(micSelect).toBeEnabled());

    fireEvent.click(micSelect);
    fireEvent.click(await screen.findByText('이어폰 마이크'));

    // 기본 마이크로 폴백해 테스트는 계속되고, '연결 안됨' 대신 대체 안내가 보인다.
    const notice = await screen.findByTestId('microphone-fallback-notice');
    expect(notice).toHaveTextContent('NotReadableError');
    await waitFor(() => {
      const last = onStateChange.mock.lastCall?.[0];
      expect(last?.microphoneDeviceId).toBe('mic-1');
      expect(last?.result.passed).toBe(true);
    });

    // 같은 이어폰을 다시 고르면(연결 상태를 고친 뒤 재시도) exact 요청을 다시 수행한다.
    const exactAttempts = () =>
      getUserMedia.mock.calls.filter((call) => {
        const c = call[0] as MediaStreamConstraints;
        const req = typeof c.audio === 'object' ? c.audio.deviceId : undefined;
        return typeof req === 'object' && req !== null && 'exact' in req && req.exact === 'mic-2';
      }).length;
    const attemptsBefore = exactAttempts();

    fireEvent.click(micSelect);
    fireEvent.click(await screen.findByText('이어폰 마이크'));

    await waitFor(() => {
      expect(exactAttempts()).toBeGreaterThan(attemptsBefore);
    });
  });

  it('로드 후 이어폰을 꽂으면 devicechange 로 목록이 갱신돼 새 마이크가 보인다', async () => {
    analyserByte = 200;
    let devices = [
      { deviceId: 'cam-1', kind: 'videoinput', label: '테스트 카메라' },
      { deviceId: 'mic-1', kind: 'audioinput', label: '내장 마이크' },
    ] as MediaDeviceInfo[];
    const listeners = new Set<() => void>();

    getUserMedia = vi.fn((constraints: MediaStreamConstraints) =>
      constraints.video
        ? Promise.resolve(fakeStream([fakeTrack('video', 'cam-1')]))
        : Promise.resolve(fakeStream([fakeTrack('audio', 'mic-1')])),
    );
    Object.defineProperty(navigator, 'mediaDevices', {
      value: {
        getUserMedia,
        enumerateDevices: () => Promise.resolve(devices),
        addEventListener: (_type: string, cb: () => void) => listeners.add(cb),
        removeEventListener: (_type: string, cb: () => void) => listeners.delete(cb),
      },
      configurable: true,
    });

    render(<DevicePreview levelSampleIntervalMs={10} />);
    const micSelect = await screen.findByTestId('microphone-select');
    await waitFor(() => expect(micSelect).toBeEnabled());

    // 아직 이어폰은 목록에 없다.
    fireEvent.click(micSelect);
    expect(screen.queryByText('이어폰 마이크')).toBeNull();
    fireEvent.click(micSelect);

    // 이어폰을 꽂으면 devicechange 가 발생하고, 목록이 다시 읽혀 새 마이크가 노출된다.
    devices = [
      ...devices,
      { deviceId: 'mic-2', kind: 'audioinput', label: '이어폰 마이크' } as MediaDeviceInfo,
    ];
    listeners.forEach((cb) => cb());

    fireEvent.click(micSelect);
    expect(await screen.findByText('이어폰 마이크')).toBeInTheDocument();
  });

  it('권한이 거부되면 카메라·마이크 권한 실패를 보고한다', async () => {
    stubMediaDevices({
      video: () => Promise.reject(notAllowedError()),
      audio: () => Promise.reject(notAllowedError()),
    });

    const onStateChange = vi.fn<(state: DevicePreviewState) => void>();
    render(<DevicePreview onStateChange={onStateChange} levelSampleIntervalMs={10} />);

    await waitFor(() => {
      const failures = onStateChange.mock.lastCall?.[0].result.failures ?? [];
      expect(failures).toContain('CAMERA_PERMISSION_DENIED');
      expect(failures).toContain('MICROPHONE_PERMISSION_DENIED');
    });
    expect(onStateChange.mock.lastCall?.[0].result.passed).toBe(false);
    // 마이크 권한 문제는 레벨 위 말풍선으로 알린다(카메라는 미리보기 오버레이·체크리스트로 노출).
    expect(await screen.findByTestId('mic-hint-bubble')).toHaveTextContent(
      '마이크 권한이 거부되어 있어요',
    );
  });

  it('카메라가 없으면 카메라 실패를 보고하되 마이크 테스트는 계속한다', async () => {
    analyserByte = 200;
    stubMediaDevices({
      video: () => Promise.reject(notFoundError()),
      audio: () => Promise.resolve(fakeStream([fakeTrack('audio', 'mic-1')])),
    });

    const onStateChange = vi.fn<(state: DevicePreviewState) => void>();
    render(<DevicePreview onStateChange={onStateChange} levelSampleIntervalMs={10} />);

    await waitFor(() => {
      const last = onStateChange.mock.lastCall?.[0];
      expect(last?.result.failures).toContain('CAMERA_NOT_SELECTED');
      expect(last?.result.passed).toBe(false);
      // 카메라가 없어도 마이크는 정상적으로 열린다.
      expect(last?.microphoneDeviceId).toBe('mic-1');
    });
  });

  it('마이크가 연결돼 있으면 무음이어도 아무 안내 없이 통과한다', async () => {
    analyserByte = 128; // 무음(연결은 정상)
    stubMediaDevices({
      video: () => Promise.resolve(fakeStream([fakeTrack('video', 'cam-1')])),
      audio: () => Promise.resolve(fakeStream([fakeTrack('audio', 'mic-1')])),
    });

    const onStateChange = vi.fn<(state: DevicePreviewState) => void>();
    render(<DevicePreview onStateChange={onStateChange} levelSampleIntervalMs={10} />);

    // 무음이어도 통과: 연결만 되어 있으면 입장을 막지 않는다.
    await waitFor(() => {
      expect(onStateChange.mock.lastCall?.[0].result.passed).toBe(true);
    });
    // 무음은 오류가 아니므로 실패로 보고하지 않고, 말풍선도 띄우지 않는다.
    expect(onStateChange.mock.lastCall?.[0].result.failures).toEqual([]);
    expect(screen.queryByTestId('mic-hint-bubble')).toBeNull();
    expect(screen.getByTestId('mic-level')).toHaveAttribute('data-level-passed', 'false');
  });

  it('마이크 트랙이 끊기면(장치 분리) 연결 안됨으로 보고한다', async () => {
    analyserByte = 200;
    const micTrack = fakeTrack('audio', 'mic-1');
    stubMediaDevices({
      video: () => Promise.resolve(fakeStream([fakeTrack('video', 'cam-1')])),
      audio: () => Promise.resolve(fakeStream([micTrack])),
    });

    const onStateChange = vi.fn<(state: DevicePreviewState) => void>();
    render(<DevicePreview onStateChange={onStateChange} levelSampleIntervalMs={10} />);
    await waitFor(() => expect(onStateChange.mock.lastCall?.[0].result.passed).toBe(true));

    // 장치 분리로 트랙이 ended 되면 미선택(연결 안됨)으로 되돌아가고, 말풍선으로 안내한다.
    act(() => {
      micTrack.dispatchEvent(new Event('ended'));
    });
    await waitFor(() => {
      const last = onStateChange.mock.lastCall?.[0];
      expect(last?.result.failures).toContain('MICROPHONE_NOT_SELECTED');
      expect(last?.result.passed).toBe(false);
    });
    expect(await screen.findByTestId('mic-hint-bubble')).toHaveTextContent(
      '마이크가 연결되어 있지 않아요',
    );
  });

  it('카메라·마이크 토글을 끄면 꺼짐을 보고하고 통과가 깨진다', async () => {
    analyserByte = 200;
    stubMediaDevices({
      video: () => Promise.resolve(fakeStream([fakeTrack('video', 'cam-1')])),
      audio: () => Promise.resolve(fakeStream([fakeTrack('audio', 'mic-1')])),
    });

    const onStateChange = vi.fn<(state: DevicePreviewState) => void>();
    render(<DevicePreview onStateChange={onStateChange} levelSampleIntervalMs={10} />);
    await waitFor(() => expect(onStateChange.mock.lastCall?.[0].result.passed).toBe(true));

    fireEvent.click(screen.getByTestId('camera-toggle'));
    await waitFor(() =>
      expect(onStateChange.mock.lastCall?.[0].result.failures).toContain('CAMERA_DISABLED'),
    );

    fireEvent.click(screen.getByTestId('microphone-toggle'));
    await waitFor(() =>
      expect(onStateChange.mock.lastCall?.[0].result.failures).toContain('MICROPHONE_DISABLED'),
    );
    expect(onStateChange.mock.lastCall?.[0].result.passed).toBe(false);
    // 마이크를 끄면 레벨 위 말풍선으로 알린다.
    expect(await screen.findByTestId('mic-hint-bubble')).toHaveTextContent('마이크가 꺼져 있어요');

    // 다시 켜면 통과 상태로 복구되고 말풍선도 사라진다.
    fireEvent.click(screen.getByTestId('camera-toggle'));
    fireEvent.click(screen.getByTestId('microphone-toggle'));
    await waitFor(() => expect(onStateChange.mock.lastCall?.[0].result.passed).toBe(true));
    expect(screen.queryByTestId('mic-hint-bubble')).toBeNull();
  });

  it('마이크 전환 중에는 기존 통과 상태를 유지하고 실패가 확정될 때만 안내로 바꾼다', async () => {
    analyserByte = 200;
    const devices = [
      { deviceId: 'cam-1', kind: 'videoinput', label: '테스트 카메라' },
      { deviceId: 'mic-1', kind: 'audioinput', label: '내장 마이크' },
      { deviceId: 'mic-2', kind: 'audioinput', label: '이어폰 마이크' },
    ] as MediaDeviceInfo[];

    // 이어폰(mic-2)의 첫 요청은 대기 상태로 붙잡아 "전환 중" 구간을 재현하고,
    // 테스트가 원하는 시점에 실패시켜 그 뒤의 폴백·안내 전환을 검증한다.
    let rejectEarphone: ((error: unknown) => void) | null = null;
    getUserMedia = vi.fn((constraints: MediaStreamConstraints) => {
      if (constraints.video) {
        return Promise.resolve(fakeStream([fakeTrack('video', 'cam-1')]));
      }
      const req = typeof constraints.audio === 'object' ? constraints.audio.deviceId : undefined;
      const wantsEarphone =
        req === 'mic-2' || (typeof req === 'object' && req !== null && 'exact' in req && req.exact === 'mic-2');
      if (wantsEarphone) {
        if (!rejectEarphone) {
          return new Promise<MediaStream>((_, reject) => {
            rejectEarphone = reject;
          });
        }
        return Promise.reject(new DOMException('Could not start audio source', 'NotReadableError'));
      }
      return Promise.resolve(fakeStream([fakeTrack('audio', 'mic-1')]));
    });
    Object.defineProperty(navigator, 'mediaDevices', {
      value: { getUserMedia, enumerateDevices: () => Promise.resolve(devices) },
      configurable: true,
    });

    const onStateChange = vi.fn<(state: DevicePreviewState) => void>();
    render(<DevicePreview onStateChange={onStateChange} levelSampleIntervalMs={10} />);

    const micSelect = await screen.findByTestId('microphone-select');
    await waitFor(() => expect(micSelect).toBeEnabled());
    await waitFor(() => expect(onStateChange.mock.lastCall?.[0].result.passed).toBe(true));

    fireEvent.click(micSelect);
    fireEvent.click(await screen.findByText('이어폰 마이크'));

    // 전환 요청이 걸려 있는 동안: 실패로 보고하지 않고 직전 통과 상태가 유지된다.
    await waitFor(() => expect(rejectEarphone).not.toBeNull());
    expect(screen.getByTestId('mic-level')).toHaveAttribute('data-level-passed', 'true');
    expect(onStateChange.mock.lastCall?.[0].result.passed).toBe(true);

    // 실패가 확정되면 폴백이 완료된 뒤 대체 안내가 표시된다.
    rejectEarphone!(new DOMException('Could not start audio source', 'NotReadableError'));
    expect(await screen.findByTestId('microphone-fallback-notice')).toBeInTheDocument();
    await waitFor(() => {
      const last = onStateChange.mock.lastCall?.[0];
      expect(last?.microphoneDeviceId).toBe('mic-1');
      expect(last?.result.passed).toBe(true);
    });
  });

  it('장치를 처음 여는 동안에는 통과로 보고하지 않는다(요청 미해결)', async () => {
    // 권한 프롬프트가 떠 있는 동안(요청 미해결) 성급하게 통과로 보고해 입장이 열리면 안 된다.
    stubMediaDevices({
      video: () => new Promise<MediaStream>(() => {}),
      audio: () => new Promise<MediaStream>(() => {}),
    });

    const onStateChange = vi.fn<(state: DevicePreviewState) => void>();
    render(<DevicePreview onStateChange={onStateChange} levelSampleIntervalMs={10} />);
    // 요청이 계속 진행 중인 상태에서 잠시 기다려도 통과로 보고되지 않고, 대체 안내도 없다.
    await new Promise((resolve) => setTimeout(resolve, 50));
    const reportedPassed = onStateChange.mock.calls.map((call) => call[0].result.passed);
    expect(reportedPassed).not.toContain(true);
    expect(screen.queryByTestId('camera-fallback-notice')).toBeNull();
    expect(screen.queryByTestId('microphone-fallback-notice')).toBeNull();
  });
});
