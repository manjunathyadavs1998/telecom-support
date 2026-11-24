import { expect, test, type Page } from '@playwright/test'
import { SEED_USERS, storageStateFor, type SeedUserKey } from './helpers/users.ts'

// frontend.md §10 e2e `forbidden.spec.ts` (XC-02, BR-10): a direct URL to a route the role lacks renders the 403 page at
// that URL (no redirect), also after a reload; an unknown route renders the 404 page inside the shell. Setup storageState
// only (read-only): no logins here.

const CASES: { user: SeedUserKey; path: string }[] = [
  { user: 'customer', path: '/outbound' },
  { user: 'inbound', path: '/analytics' },
  { user: 'inbound', path: '/customer/plan' },
]

async function expectSystemPage(page: Page, path: string, title: string) {
  await expect(page).toHaveURL(path)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(title)
  await expect(page).toHaveTitle(`${title} · Telecom Support`)
  // Inside the shell: the user's own nav is still there, and the page offers the way back to the landing route.
  await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible()
  await expect(page.getByRole('main').getByRole('link')).toBeVisible()
}

for (const { user, path } of CASES) {
  test.describe(`${SEED_USERS[user]}`, () => {
    test.use({ storageState: storageStateFor(user) })

    test(`direct URL ${path} → 403 page, also after a reload`, async ({ page }) => {
      const response = await page.goto(path)
      expect(response?.status()).toBe(200)
      await expectSystemPage(page, path, 'Access denied')

      await page.reload()
      await expectSystemPage(page, path, 'Access denied')
    })
  })
}

test.describe(`${SEED_USERS.inbound} unknown route`, () => {
  test.use({ storageState: storageStateFor('inbound') })

  test('/nope → 404 page inside the shell', async ({ page }) => {
    await page.goto('/nope')
    await expectSystemPage(page, '/nope', 'Page not found')
  })
})
