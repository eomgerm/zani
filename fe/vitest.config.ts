import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  resolve: {
    tsconfigPaths: true,
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.ts'],
    // Playwright e2e(e2e/*.spec.ts)는 Vitest 대상에서 제외한다.
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
  },
});
