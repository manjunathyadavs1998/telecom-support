import { defineConfig, devices } from '@playwright/test'

// frontend.md §9/§10: e2e runs only against the packaged JAR (profiles `dev,e2e`, compose MySQL), never against Vite.
// Run it with `bash scripts/check-frontend.sh e2e` (Playwright container, E2E_BASE_URL + E2E_PASSWORD).
const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:8080'

/** The three test viewports (frontend.md §8, §10). */
const VIEWPORTS = {
  desktop: { width: 1280, height: 800 },
  tablet: { width: 768, height: 1024 },
  mobile: { width: 375, height: 812 },
} as const

// Every spec runs at desktop; the viewport journey (responsive-a11y) also runs at tablet and mobile.
const RESPONSIVE = /responsive-a11y\.spec\.ts/

export default defineConfig({
  testDir: './e2e',
  // Login budget (§10, ≤ 12 per run): no retries anywhere, so a failing login is never repeated.
  retries: 0,
  forbidOnly: !!process.env.CI,
  reporter: [['list']],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'setup', testMatch: /auth\.setup\.ts/ },
    {
      name: 'chromium-desktop',
      use: { ...devices['Desktop Chrome'], viewport: VIEWPORTS.desktop },
      dependencies: ['setup'],
    },
    {
      name: 'chromium-tablet',
      use: { ...devices['Desktop Chrome'], viewport: VIEWPORTS.tablet },
      dependencies: ['setup'],
      testMatch: RESPONSIVE,
    },
    {
      name: 'chromium-mobile',
      use: { ...devices['Desktop Chrome'], viewport: VIEWPORTS.mobile },
      dependencies: ['setup'],
      testMatch: RESPONSIVE,
    },
  ],
})
