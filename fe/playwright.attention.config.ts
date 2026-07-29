import { defineConfig, devices } from '@playwright/test';

const PORT = 3101;
const externalBaseUrl = process.env.ATTENTION_SMOKE_BASE_URL;

export default defineConfig({
  testDir: './e2e',
  testMatch: 'attention-model.spec.ts',
  timeout: 60_000,
  reporter: 'list',
  use: {
    baseURL: externalBaseUrl ?? `http://localhost:${PORT}`,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: externalBaseUrl
    ? undefined
    : {
        command: 'npm run start:attention-smoke',
        url: `http://localhost:${PORT}`,
        env: { PORT: String(PORT), HOSTNAME: '127.0.0.1' },
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
      },
});
