import { Suspense } from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { DevicePreviewState } from '@/features/media/DevicePreview';
import Page from './page';

const { pushMock, deviceStateEmitter } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  deviceStateEmitter: {
    emit: null as ((state: DevicePreviewState) => void) | null,
  },
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: pushMock }),
}));

// 장치 테스트 자체는 DevicePreview.test.tsx 에서 검증한다.
// 페이지 테스트에서는 판정 결과 보고만 흉내 내는 스텁으로 대체한다.
vi.mock('@/features/media/DevicePreview', () => ({
  DevicePreview: ({ onStateChange }: { onStateChange?: (state: DevicePreviewState) => void }) => {
    deviceStateEmitter.emit = onStateChange ?? null;
    return <div data-testid="device-preview-stub" />;
  },
}));

const CHROME_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

const originalUserAgent = navigator.userAgent;

/** navigator 를 Chrome + 미디어 API 지원 상태로 스텁한다. */
function stubSupportedBrowser() {
  Object.defineProperty(navigator, 'userAgent', { value: CHROME_UA, configurable: true });
  Object.defineProperty(navigator, 'mediaDevices', {
    value: { getUserMedia: () => Promise.resolve() },
    configurable: true,
  });
  Object.defineProperty(navigator, 'permissions', {
    value: { query: () => Promise.resolve({ state: 'prompt' }) },
    configurable: true,
  });
}

function passedState(): DevicePreviewState {
  return {
    cameraDeviceId: 'cam-1',
    microphoneDeviceId: 'mic-1',
    result: { passed: true, failures: [] },
  };
}

function failedState(): DevicePreviewState {
  return {
    cameraDeviceId: 'cam-1',
    microphoneDeviceId: 'mic-1',
    result: { passed: false, failures: ['MICROPHONE_LEVEL_TOO_LOW'] },
  };
}

async function renderPage(inviteCode = 'ABC123') {
  // use(params) 서스펜션이 풀릴 때까지 비동기 act 안에서 렌더링한다.
  await act(async () => {
    render(
      <Suspense fallback={null}>
        <Page params={Promise.resolve({ inviteCode })} />
      </Suspense>,
    );
  });
}

beforeEach(() => {
  sessionStorage.clear();
});

afterEach(() => {
  vi.clearAllMocks();
  deviceStateEmitter.emit = null;
  Object.defineProperty(navigator, 'userAgent', {
    value: originalUserAgent,
    configurable: true,
  });
  Reflect.deleteProperty(navigator, 'mediaDevices');
  Reflect.deleteProperty(navigator, 'permissions');
});

describe('Prejoin Page', () => {
  it('비지원 브라우저면 입장 버튼을 비활성화하고 원인별 안내를 보여준다', async () => {
    // jsdom 기본 UA(비 Chrome) + mediaDevices 없음
    await renderPage();

    expect(await screen.findByTestId('browser-failure-NOT_CHROME')).toBeInTheDocument();
    expect(screen.getByTestId('browser-unsupported-panel')).toBeInTheDocument();
    expect(screen.getByTestId('prejoin-enter-button')).toBeDisabled();
  });

  it('장치 테스트가 실패하면 입장 버튼이 비활성화된다', async () => {
    stubSupportedBrowser();
    await renderPage();

    await waitFor(() => expect(deviceStateEmitter.emit).not.toBeNull());
    act(() => deviceStateEmitter.emit?.(failedState()));

    await waitFor(() => {
      expect(screen.getByTestId('prejoin-enter-button')).toBeDisabled();
    });
  });

  it('모두 통과하면 장치 ID 와 통과 시각을 저장하고 강의실로 이동한다', async () => {
    stubSupportedBrowser();
    await renderPage('ABC123');

    await waitFor(() => expect(deviceStateEmitter.emit).not.toBeNull());
    act(() => deviceStateEmitter.emit?.(passedState()));

    const enterButton = screen.getByTestId('prejoin-enter-button');
    await waitFor(() => expect(enterButton).toBeEnabled());

    fireEvent.click(enterButton);

    const stored = sessionStorage.getItem('zani:prejoin:ABC123');
    expect(stored).not.toBeNull();
    const payload = JSON.parse(stored ?? '{}');
    expect(payload.cameraDeviceId).toBe('cam-1');
    expect(payload.microphoneDeviceId).toBe('mic-1');
    expect(new Date(payload.testedAt).getTime()).not.toBeNaN();
    expect(pushMock).toHaveBeenCalledWith('/room/ABC123');
  });

  it('통과가 깨지면 입장 버튼이 다시 비활성화된다', async () => {
    stubSupportedBrowser();
    await renderPage();

    await waitFor(() => expect(deviceStateEmitter.emit).not.toBeNull());
    act(() => deviceStateEmitter.emit?.(passedState()));
    await waitFor(() => expect(screen.getByTestId('prejoin-enter-button')).toBeEnabled());

    act(() => deviceStateEmitter.emit?.(failedState()));
    await waitFor(() => expect(screen.getByTestId('prejoin-enter-button')).toBeDisabled());
  });
});
