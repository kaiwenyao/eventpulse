import { defineConfig, devices } from '@playwright/test'

/**
 * Minimal browser smoke-test config. These E2E tests run against the Vite dev
 * server alone (no backend) and verify the SPA actually boots and renders its
 * core flows in a real browser — catching bundle/dependency/router breakage
 * that unit tests miss. Backend-dependent journeys are covered by the
 * backend Testcontainers integration suite plus the compose smoke test.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? 'http://127.0.0.1:5173',
    locale: 'zh-CN',
    trace: 'on-first-retry',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5173',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
})
