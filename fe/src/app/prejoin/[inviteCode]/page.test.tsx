import { Suspense } from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { JoinSessionRequestError } from '@/domains/lecture/infrastructure/joinSessionApi';
import type { DevicePreviewState } from '@/features/media/DevicePreview';
import Page from './page';

const { pushMock, deviceStateEmitter, authState } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  deviceStateEmitter: {
    emit: null as ((state: DevicePreviewState) => void) | null,
  },
  authState: { accessToken: 'access-token' as string | null },
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: pushMock }),
}));

// 입장은 로그인 사용자만 가능하다. 인증 컨텍스트 전체를 띄우지 않고 토큰만 흉내 낸다.
vi.mock('@/domains/auth/presentation/AuthProvider', () => ({
  useAuth: () => authState,
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
    result: { passed: false, failures: ['MICROPHONE_DISABLED'] },
  };
}

/** 기본 성공 응답. 세션 ID 는 초대 코드와 다른 값이어야 라우팅 검증이 의미가 있다. */
const joinedSession = (sessionId = "4242") => ({
  sessionId,
  inviteCode: 'ABC123',
  status: 'LIVE',
  role: 'STUDENT',
});

async function renderPage(
  inviteCode = 'ABC123',
  joinSession = vi.fn().mockResolvedValue(joinedSession()),
) {
  // use(params) 서스펜션이 풀릴 때까지 비동기 act 안에서 렌더링한다.
  await act(async () => {
    render(
      <Suspense fallback={null}>
        <Page params={Promise.resolve({ inviteCode })} joinSession={joinSession} />
      </Suspense>,
    );
  });
  return joinSession;
}

/** 장치 점검을 통과시키고 활성화된 입장 버튼을 돌려준다. */
async function passDeviceChecks() {
  await waitFor(() => expect(deviceStateEmitter.emit).not.toBeNull());
  act(() => deviceStateEmitter.emit?.(passedState()));
  const enterButton = screen.getByTestId('prejoin-enter-button');
  await waitFor(() => expect(enterButton).toBeEnabled());
  return enterButton;
}

beforeEach(() => {
  sessionStorage.clear();
  authState.accessToken = 'access-token';
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

  it('모두 통과하면 서버 입장을 거친 뒤 장치 ID 를 저장하고 강의실로 이동한다', async () => {
    stubSupportedBrowser();
    const joinSession = await renderPage('ABC123');

    fireEvent.click(await passDeviceChecks());

    await waitFor(() => expect(joinSession).toHaveBeenCalledWith('ABC123', 'access-token'));

    const stored = sessionStorage.getItem('zani:prejoin:ABC123');
    expect(stored).not.toBeNull();
    const payload = JSON.parse(stored ?? '{}');
    expect(payload.cameraDeviceId).toBe('cam-1');
    expect(payload.microphoneDeviceId).toBe('mic-1');
    expect(new Date(payload.testedAt).getTime()).not.toBeNaN();
    // 초대 코드가 아니라 서버가 확정한 세션 ID 로 이동해야 한다. 코드를 그대로 쓰면 미디어 토큰 발급이 실패한다.
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith('/room/4242'));
  });

  it('장치를 통과했어도 서버가 입장을 거절하면 방으로 이동하지 않고 원인을 보여준다', async () => {
    stubSupportedBrowser();
    const joinSession = vi
      .fn()
      .mockRejectedValue(
        new JoinSessionRequestError('capacity', 409, 'SESSION_APP_007'),
      );
    await renderPage('ABC123', joinSession);

    fireEvent.click(await passDeviceChecks());

    expect(await screen.findByTestId('prejoin-join-error')).toHaveTextContent('정원이 가득 찼어요');
    expect(pushMock).not.toHaveBeenCalled();
    // 실패했으므로 장치 결과도 남기지 않는다.
    expect(sessionStorage.getItem('zani:prejoin:ABC123')).toBeNull();
  });

  it('아직 시작하지 않은 수업이면 다시 시도할 수 있게 안내하고 버튼을 되살린다', async () => {
    stubSupportedBrowser();
    const joinSession = vi
      .fn()
      .mockRejectedValue(new JoinSessionRequestError('not joinable', 409, 'SESSION_APP_008'));
    await renderPage('ABC123', joinSession);

    const enterButton = await passDeviceChecks();
    fireEvent.click(enterButton);

    expect(await screen.findByTestId('prejoin-join-error')).toHaveTextContent(
      '아직 시작하지 않았거나 이미 끝난 수업이에요',
    );
    await waitFor(() => expect(enterButton).toBeEnabled());
  });

  it('로그인 토큰이 없으면 서버를 호출하지 않고 로그인을 안내한다', async () => {
    stubSupportedBrowser();
    authState.accessToken = null;
    const joinSession = vi.fn();
    await renderPage('ABC123', joinSession);

    fireEvent.click(await passDeviceChecks());

    expect(await screen.findByTestId('prejoin-join-error')).toHaveTextContent('로그인이 필요해요');
    expect(joinSession).not.toHaveBeenCalled();
    expect(pushMock).not.toHaveBeenCalled();
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
