import { expect, type Page, type Response } from '@playwright/test'

/** The shared dev password (R__01_identity.sql) comes only from the environment (frontend.md §10). */
export function e2ePassword(): string {
  const password = process.env.E2E_PASSWORD
  if (!password) throw new Error('E2E_PASSWORD is not set')
  return password
}

const MAX_ATTEMPTS = 2

/** The `POST /api/v1/auth/login` response the next form submit produces. */
export function waitForLoginResponse(page: Page): Promise<Response> {
  return page.waitForResponse(
    (response) => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/auth/login',
  )
}

/**
 * Fills and submits the login form already on screen (keeps the current `?reason=`/`returnTo=`). One login attempt
 * against the run's budget; no 429 handling (use `login()` for that).
 */
export async function submitLogin(page: Page, username: string, password: string = e2ePassword()): Promise<Response> {
  await page.getByLabel('Mobile number or employee ID').fill(username)
  await page.getByLabel('Password', { exact: true }).fill(password)
  const responsePromise = waitForLoginResponse(page)
  await page.getByRole('button', { name: 'Sign in' }).click()
  return responsePromise
}

/**
 * Signs in through the login page. On 429 it waits `Retry-After` seconds and tries once more (frontend.md §10); every
 * attempt counts against the run's login budget. Returns the final `POST /api/v1/auth/login` response.
 */
export async function login(page: Page, username: string, password: string = e2ePassword()): Promise<Response> {
  for (let attempt = 1; ; attempt++) {
    await page.goto('/login')
    const response = await submitLogin(page, username, password)
    if (response.status() !== 429 || attempt >= MAX_ATTEMPTS) return response
    const retryAfter = Number(response.headers()['retry-after'] ?? '60')
    await page.waitForTimeout((Number.isFinite(retryAfter) ? retryAfter : 60) * 1000 + 250)
  }
}

/** `login()` and expect success: 200 and the app has left `/login`. */
export async function loginOk(page: Page, username: string): Promise<void> {
  const response = await login(page, username)
  expect(response.status(), `login ${username}`).toBe(200)
  await expect(page).not.toHaveURL(/\/login/)
}
