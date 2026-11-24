import { defineConfig, devices } from '@playwright/test';

/**
 * SG-91: regenerates the README screenshots and demo video from a running production stack with fresh data.
 * Not part of the test suite or CI:
 *   E2E_BASE_URL=http://localhost:8088 npx playwright test --config docs.config.ts
 * Output: PNG files in ../docs/images, and a WebM video under test-results/video (converted to GIF, see e2e/README.md).
 */
export default defineConfig({
  testDir: './docs',
  timeout: 180_000,
  workers: 1,
  reporter: 'list',
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8088',
    locale: 'en-GB',
    timezoneId: 'Asia/Ho_Chi_Minh',
    viewport: { width: 1280, height: 800 },
    deviceScaleFactor: 1,
  },
});
