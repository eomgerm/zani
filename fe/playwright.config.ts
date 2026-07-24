import { defineConfig, devices } from '@playwright/test';

const PORT = 3100;

/**
 * prejoin 장치 검증 e2e 설정.
 *
 * Chromium 을 fake media 플래그로 실행해 실제 장치 없이
 * 카메라 영상과 마이크 입력(톤)을 공급받는다.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  fullyParallel: true,
  reporter: 'list',
  use: {
    baseURL: `http://localhost:${PORT}`,
    permissions: ['camera', 'microphone'],
    launchOptions: {
      args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'],
    },
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `npm run dev -- -p ${PORT}`,
    url: `http://localhost:${PORT}`,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
