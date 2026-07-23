import { expect, test, type Page } from '@playwright/test';

/**
 * 입장 전 장치 검증(prejoin) e2e.
 *
 * Chromium fake media 플래그가 카메라 영상과 마이크 톤을 공급한다.
 * 성공, 권한 거부, 카메라 없음, 마이크 무입력, 비지원 브라우저를 검증한다.
 */

const INVITE_CODE = 'TEST01';
const PREJOIN_URL = `/prejoin/${INVITE_CODE}`;

const FIREFOX_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0';

/**
 * Playwright Chromium 의 userAgentData.brands 에는 Google Chrome 이 없어
 * 브라우저 판정에서 NOT_CHROME 이 된다. 성공 경로 테스트는 Chrome 으로 위장한다.
 */
async function fakeChromeBrand(page: Page) {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'userAgentData', {
      value: { brands: [{ brand: 'Google Chrome', version: '120' }] },
      configurable: true,
    });
  });
}

test('카메라·마이크가 모두 유효하면 입장이 활성화되고 장치 ID 와 통과 시각을 저장한다', async ({
  page,
}) => {
  await fakeChromeBrand(page);
  await page.goto(PREJOIN_URL);

  const enterButton = page.getByTestId('prejoin-enter-button');
  await expect(enterButton).toBeEnabled({ timeout: 20_000 });
  await enterButton.click();

  await page.waitForURL(`**/room/${INVITE_CODE}`);

  const stored = await page.evaluate(
    (code) => sessionStorage.getItem(`zani:prejoin:${code}`),
    INVITE_CODE,
  );
  expect(stored).not.toBeNull();
  const payload = JSON.parse(stored ?? '{}') as {
    cameraDeviceId: unknown;
    microphoneDeviceId: unknown;
    testedAt: unknown;
  };
  expect(typeof payload.cameraDeviceId).toBe('string');
  expect(typeof payload.microphoneDeviceId).toBe('string');
  expect(Number.isNaN(Date.parse(String(payload.testedAt)))).toBe(false);
});

test('권한이 거부되면 입장이 비활성화되고 권한 안내가 보인다', async ({ page }) => {
  await fakeChromeBrand(page);
  await page.addInitScript(() => {
    navigator.mediaDevices.getUserMedia = () =>
      Promise.reject(new DOMException('Permission denied', 'NotAllowedError'));
  });
  await page.goto(PREJOIN_URL);

  await expect(page.getByTestId('device-failure-CAMERA_PERMISSION_DENIED')).toBeVisible();
  await expect(page.getByTestId('device-failure-MICROPHONE_PERMISSION_DENIED')).toBeVisible();
  await expect(page.getByTestId('device-retry-button')).toBeVisible();
  await expect(page.getByTestId('prejoin-enter-button')).toBeDisabled();
});

test('카메라가 없으면 카메라 안내가 보이고 마이크 테스트는 계속된다', async ({ page }) => {
  await fakeChromeBrand(page);
  await page.addInitScript(() => {
    const original = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
    navigator.mediaDevices.getUserMedia = (constraints) =>
      constraints?.video
        ? Promise.reject(new DOMException('Requested device not found', 'NotFoundError'))
        : original(constraints);
  });
  await page.goto(PREJOIN_URL);

  await expect(page.getByTestId('device-failure-CAMERA_NOT_SELECTED')).toBeVisible();
  await expect(page.getByTestId('prejoin-enter-button')).toBeDisabled();

  // 마이크는 fake 톤 입력으로 정상 판정되어 레벨 실패 안내가 없어야 한다.
  await expect(page.getByTestId('mic-level')).toHaveAttribute('data-level-passed', 'true', {
    timeout: 20_000,
  });
  await expect(page.getByTestId('device-failure-MICROPHONE_LEVEL_TOO_LOW')).toHaveCount(0);
});

test('마이크 입력이 무음이면 입력 레벨 안내가 보이고 입장이 비활성화된다', async ({ page }) => {
  await fakeChromeBrand(page);
  await page.addInitScript(() => {
    // 장치는 정상 연결하되 분석기에 항상 무음(128)을 채워 무입력을 재현한다.
    AnalyserNode.prototype.getByteTimeDomainData = function fillSilence(array: Uint8Array) {
      array.fill(128);
    };
  });
  await page.goto(PREJOIN_URL);

  await expect(page.getByTestId('device-failure-MICROPHONE_LEVEL_TOO_LOW')).toBeVisible({
    timeout: 20_000,
  });
  await expect(page.getByTestId('mic-level')).toHaveAttribute('data-level-passed', 'false');
  await expect(page.getByTestId('prejoin-enter-button')).toBeDisabled();
});

test('비지원 브라우저면 입장이 비활성화되고 Chrome 안내가 보인다', async ({ page }) => {
  await page.addInitScript((firefoxUa) => {
    Object.defineProperty(navigator, 'userAgent', { value: firefoxUa, configurable: true });
    Object.defineProperty(navigator, 'userAgentData', { value: undefined, configurable: true });
  }, FIREFOX_UA);
  await page.goto(PREJOIN_URL);

  await expect(page.getByTestId('browser-failure-NOT_CHROME')).toBeVisible();
  await expect(page.getByTestId('prejoin-enter-button')).toBeDisabled();
});
