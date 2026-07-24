import { describe, expect, it } from 'vitest';

import {
  type BrowserEnvironment,
  detectBrowserSupport,
  isChrome,
  readBrowserEnvironment,
} from './browserSupport';

const CHROME_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
const EDGE_UA = `${CHROME_UA} Edg/120.0.0.0`;
const FIREFOX_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0';

function supportedEnv(overrides: Partial<BrowserEnvironment> = {}): BrowserEnvironment {
  return {
    userAgent: CHROME_UA,
    hasMediaDevices: true,
    hasGetUserMedia: true,
    hasPermissions: true,
    ...overrides,
  };
}

describe('isChrome', () => {
  it('userAgent 로 Chrome 을 인식한다', () => {
    expect(isChrome({ userAgent: CHROME_UA })).toBe(true);
  });

  it('Edge(Edg 토큰)는 Chrome 이 아니다', () => {
    expect(isChrome({ userAgent: EDGE_UA })).toBe(false);
  });

  it('Firefox 는 Chrome 이 아니다', () => {
    expect(isChrome({ userAgent: FIREFOX_UA })).toBe(false);
  });

  it('userAgentData.brands 에 Google Chrome 이 있으면 Chrome 이다', () => {
    expect(isChrome({ userAgent: EDGE_UA, brands: ['Chromium', 'Google Chrome'] })).toBe(true);
  });

  it('brands 가 있지만 Google Chrome 이 없으면 Chrome 이 아니다', () => {
    expect(isChrome({ userAgent: CHROME_UA, brands: ['Chromium', 'Microsoft Edge'] })).toBe(false);
  });
});

describe('detectBrowserSupport', () => {
  it('Chrome + 모든 API 지원이면 통과한다', () => {
    const result = detectBrowserSupport(supportedEnv());
    expect(result.supported).toBe(true);
    expect(result.failures).toEqual([]);
  });

  it('Chrome 이 아니면 NOT_CHROME 을 반환한다', () => {
    const result = detectBrowserSupport(supportedEnv({ userAgent: FIREFOX_UA }));
    expect(result.supported).toBe(false);
    expect(result.failures).toContain('NOT_CHROME');
  });

  it('mediaDevices 미지원을 감지한다', () => {
    const result = detectBrowserSupport(supportedEnv({ hasMediaDevices: false }));
    expect(result.failures).toContain('MEDIA_DEVICES_UNSUPPORTED');
  });

  it('getUserMedia 미지원을 감지한다', () => {
    const result = detectBrowserSupport(supportedEnv({ hasGetUserMedia: false }));
    expect(result.failures).toContain('GET_USER_MEDIA_UNSUPPORTED');
  });

  it('permissions API 미지원을 감지한다', () => {
    const result = detectBrowserSupport(supportedEnv({ hasPermissions: false }));
    expect(result.failures).toContain('PERMISSIONS_API_UNSUPPORTED');
  });

  it('여러 실패 사유를 모두 누적한다', () => {
    const result = detectBrowserSupport({
      userAgent: FIREFOX_UA,
      hasMediaDevices: false,
      hasGetUserMedia: false,
      hasPermissions: false,
    });
    expect(result.supported).toBe(false);
    expect(result.failures).toEqual([
      'NOT_CHROME',
      'MEDIA_DEVICES_UNSUPPORTED',
      'GET_USER_MEDIA_UNSUPPORTED',
      'PERMISSIONS_API_UNSUPPORTED',
    ]);
  });
});

describe('readBrowserEnvironment', () => {
  it('navigator 에서 판정 입력을 읽는다', () => {
    const fakeNavigator = {
      userAgent: CHROME_UA,
      userAgentData: { brands: [{ brand: 'Google Chrome' }] },
      mediaDevices: { getUserMedia: () => Promise.resolve({}) },
      permissions: { query: () => Promise.resolve({}) },
    } as unknown as Navigator;

    const env = readBrowserEnvironment(fakeNavigator);
    expect(env).toEqual({
      userAgent: CHROME_UA,
      brands: ['Google Chrome'],
      hasMediaDevices: true,
      hasGetUserMedia: true,
      hasPermissions: true,
    });
  });

  it('mediaDevices/permissions 가 없으면 미지원으로 읽는다', () => {
    const fakeNavigator = { userAgent: FIREFOX_UA } as unknown as Navigator;
    const env = readBrowserEnvironment(fakeNavigator);
    expect(env.hasMediaDevices).toBe(false);
    expect(env.hasGetUserMedia).toBe(false);
    expect(env.hasPermissions).toBe(false);
    expect(env.brands).toBeUndefined();
  });
});
