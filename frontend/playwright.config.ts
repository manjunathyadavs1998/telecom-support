import { defineConfig, devices } from '@playwright/test'

// frontend.md §9/§10: e2e runs only against the packaged JAR (profiles `dev,e2e`, compose MySQL), never against Vite.
// Run it with `bash scripts/check-frontend.sh e2e` (Playwright container, E2E_BASE_URL + E2E_PASSWORD). The `e2e` profile
// starts every JAR from a fresh database (02 frontend.md §7, §10 D2), so start a fresh JAR before every run.
const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:8080'

/** The three test viewports (frontend.md §8, §10). */
const VIEWPORTS = {
  desktop: { width: 1280, height: 800 },
  tablet: { width: 768, height: 1024 },
  mobile: { width: 375, height: 812 },
} as const

// Every spec runs at desktop except the payment spec; the viewport journeys (01 responsive-a11y, 02 customer-portal) also
// run at tablet and mobile (02 frontend.md §7).
const RESPONSIVE = [/responsive-a11y\.spec\.ts/, /customer-portal\.spec\.ts/]
// The payment spec changes customer 5's bills, so it runs once, serially, in its own project (02 frontend.md §7).
const PAYMENT = /payment\.spec\.ts/

export default defineConfig({
  testDir: './e2e',
  // Login budget (02 frontend.md §7, 13 per run): no retries anywhere, so a failing login is never repeated.
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
      testIgnore: PAYMENT,
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
    {
      name: 'chromium-payment',
      use: { ...devices['Desktop Chrome'], viewport: VIEWPORTS.desktop },
      dependencies: ['setup'],
      testMatch: PAYMENT,
      fullyParallel: false,
    },
  ],
})
