import { expect, test } from '@playwright/test'
import { login, loginOk, submitLogin } from './helpers/login.ts'
import { SEED_USERS } from './helpers/users.ts'

// frontend.md §10 e2e `session.spec.ts` (XC-02, NFR-SEC), §7 Logout / 401 handler. Login budget: 1 (wrong password) +
// 1 (logout) + 2 (expiry: fresh login + re-login) = 4 of the run's 12. Every test does its OWN fresh login and never
// touches a shared setup storageState (logout kills the server session). Serial, no retries: a failure never repeats a login.
test.describe.configure({ mode: 'serial', retries: 0 })

test('wrong password → generic error, still on /login', async ({ page }) => {
  // An unknown username, so no real user's L2 (username + IP) counter is touched.
  const response = await login(page, 'E2E-NOBODY', 'Wrong-password-1')
  expect(response.status()).toBe(401)
  await expect(page.getByText('Invalid username or password.')).toBeVisible()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByLabel('Password', { exact: true })).toHaveValue('')
})

test('logout → /login?reason=signed-out; Back and Forward show nothing protected', async ({ page }) => {
  await loginOk(page, SEED_USERS.outbound)
  await expect(page).toHaveURL('/outbound')
  const nav = page.getByRole('navigation', { name: 'Main' })
  await nav.getByRole('link', { name: 'Bookmarks', exact: true }).click()
  await expect(page).toHaveURL('/outbound/bookmarks')

  await page.getByRole('button', { name: 'Logout' }).click()
  await expect(page).toHaveURL('/login?reason=signed-out')
  await expect(page.getByText('You signed out.')).toBeVisible()

  // Back to the protected page: the guard finds no session and sends the user to login; no shell, no nav.
  await page.goBack()
  await expect(page).toHaveURL(/\/login\?returnTo=/)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Sign in')
  await expect(page.getByRole('navigation', { name: 'Main' })).toHaveCount(0)

  await page.goForward()
  await expect(page).toHaveURL(/\/login/)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Sign in')
  await expect(page.getByRole('navigation', { name: 'Main' })).toHaveCount(0)
})

test('expiry → /login?reason=expired&returnTo=… → re-login returns to that route', async ({ page, context }) => {
  await page.clock.install()
  await loginOk(page, SEED_USERS.customer)
  await expect(page).toHaveURL('/customer')

  // The server session is gone; the cached /me is older than its 60 s staleTime.
  await context.clearCookies({ name: 'SESSION' })
  await page.clock.fastForward('01:01')

  // The loader re-checks /me → 401 → the 401 handler sends the user to login with the target route.
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Profile', exact: true }).click()
  await expect(page).toHaveURL('/login?reason=expired&returnTo=%2Fcustomer%2Fprofile')
  await expect(page.getByText('Your session expired. Sign in again.')).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'Main' })).toHaveCount(0)

  const response = await submitLogin(page, SEED_USERS.customer)
  expect(response.status()).toBe(200)
  await expect(page).toHaveURL('/customer/profile')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expect(
    page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Profile', exact: true }),
  ).toHaveAttribute('aria-current', 'page')
})
