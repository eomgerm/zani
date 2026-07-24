/**
 * 입장 전 브라우저 지원 여부 판정.
 *
 * 요구사항: Chrome이 아니거나 미디어 관련 브라우저 API가 없으면 입장할 수 없고,
 * 원인별 안내를 노출할 수 있도록 실패 사유를 코드로 반환한다. 비지원 브라우저는
 * 서버의 prejoin 검증에서 400으로 거절된다.
 */

export type BrowserSupportFailure =
  | 'NOT_CHROME'
  | 'MEDIA_DEVICES_UNSUPPORTED'
  | 'GET_USER_MEDIA_UNSUPPORTED'
  | 'PERMISSIONS_API_UNSUPPORTED';

export interface BrowserSupportResult {
  readonly supported: boolean;
  readonly failures: readonly BrowserSupportFailure[];
}

/** 판정에 필요한 브라우저 정보의 최소 형태. 테스트에서 주입할 수 있도록 좁게 정의한다. */
export interface BrowserEnvironment {
  readonly userAgent: string;
  /** navigator.userAgentData.brands 의 brand 값 목록(있을 때만). */
  readonly brands?: readonly string[];
  readonly hasMediaDevices: boolean;
  readonly hasGetUserMedia: boolean;
  readonly hasPermissions: boolean;
}

/** Chromium 기반이지만 Chrome 이 아닌 브라우저를 걸러내기 위한 UA 토큰. */
const CHROMIUM_NON_CHROME_TOKENS = ['Edg', 'OPR', 'SamsungBrowser', 'Whale', 'Brave'];

/**
 * 브라우저가 Google Chrome 인지 판정한다.
 * userAgentData.brands 가 있으면 그것을 우선 사용하고(더 정확), 없으면 UA 문자열로 추정한다.
 */
export function isChrome(env: Pick<BrowserEnvironment, 'userAgent' | 'brands'>): boolean {
  if (env.brands && env.brands.length > 0) {
    return env.brands.some((brand) => brand === 'Google Chrome');
  }

  const ua = env.userAgent;
  const looksLikeChrome = ua.includes('Chrome/') || ua.includes('CriOS/');
  const isOtherChromium = CHROMIUM_NON_CHROME_TOKENS.some((token) => ua.includes(token));
  return looksLikeChrome && !isOtherChromium;
}

/** 브라우저 지원 여부를 종합 판정한다. 실패 사유가 하나도 없으면 supported=true. */
export function detectBrowserSupport(env: BrowserEnvironment): BrowserSupportResult {
  const failures: BrowserSupportFailure[] = [];

  if (!isChrome(env)) {
    failures.push('NOT_CHROME');
  }
  if (!env.hasMediaDevices) {
    failures.push('MEDIA_DEVICES_UNSUPPORTED');
  }
  if (!env.hasGetUserMedia) {
    failures.push('GET_USER_MEDIA_UNSUPPORTED');
  }
  if (!env.hasPermissions) {
    failures.push('PERMISSIONS_API_UNSUPPORTED');
  }

  return { supported: failures.length === 0, failures };
}

interface NavigatorWithUserAgentData extends Navigator {
  readonly userAgentData?: { readonly brands?: ReadonlyArray<{ readonly brand: string }> };
}

/** 실제 브라우저 navigator 에서 판정 입력을 읽어온다. */
export function readBrowserEnvironment(
  nav: NavigatorWithUserAgentData = navigator as NavigatorWithUserAgentData,
): BrowserEnvironment {
  return {
    userAgent: nav.userAgent,
    brands: nav.userAgentData?.brands?.map((entry) => entry.brand),
    hasMediaDevices: typeof nav.mediaDevices !== 'undefined',
    hasGetUserMedia: typeof nav.mediaDevices?.getUserMedia === 'function',
    hasPermissions: typeof nav.permissions?.query === 'function',
  };
}
