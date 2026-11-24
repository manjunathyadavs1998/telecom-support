import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Locator, type Page } from '@playwright/test'
import { waitForLoginResponse, e2ePassword } from './helpers/login.ts'
import { SEED_USERS, storageStateFor } from './helpers/users.ts'

// frontend.md §8, §10 e2e `responsive-a11y.spec.ts` (XC-08, NFR-A11Y, NFR-RESP): runs in the desktop (1280×800), tablet
// (768×1024) and mobile (375×812) projects. Keyboard-only navigation, the drawer below md, axe with no serious/critical
// violations on the login page and the shell. Login budget: 1 (the keyboard-only login, mobile project only); everything
// else reuses the setup storageState read-only.

const isMobile = (page: Page) => (page.viewportSize()?.width ?? 0) < 768

/** axe (WCAG 2.x A/AA) on the current page: no serious or critical violations. */
async function expectNoSeriousA11yViolations(page: Page) {
  const { violations } = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze()
  const serious = violations
    .filter((violation) => violation.impact === 'serious' || violation.impact === 'critical')
    .map((violation) => ({ id: violation.id, impact: violation.impact, targets: violation.nodes.map((node) => node.target) }))
  expect(serious).toEqual([])
}

/** Presses Tab until `target` has focus (keyboard only); fails after `max` presses. */
async function tabTo(page: Page, target: Locator, max = 20) {
  for (let presses = 0; presses < max; presses++) {
    await page.keyboard.press('Tab')
    if (await target.evaluate((element) => element === document.activeElement)) return
  }
  throw new Error(`Tab never reached ${target.toString()}`)
}

test('login page: axe has no serious/critical violations', async ({ page }) => {
  await page.goto('/login')
  await expect(page.getByRole('heading', { level: 1, name: 'Sign in' })).toBeVisible()
  await expectNoSeriousA11yViolations(page)
})

test.describe(`${SEED_USERS.outbound} shell`, () => {
  test.use({ storageState: storageStateFor('outbound') })

  test.beforeEach(async ({ page }) => {
    await page.goto('/outbound')
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  })

  test('axe has no serious/critical violations', async ({ page }) => {
    await expectNoSeriousA11yViolations(page)
  })

  test('keyboard only: the skip link is the first tab stop and moves focus to main', async ({ page }) => {
    await page.keyboard.press('Tab')
    const skip = page.getByRole('link', { name: 'Skip to main content' })
    await expect(skip).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(page.locator('#main')).toBeFocused()
  })

  test('keyboard only: open a nav item; focus moves to the new page heading', async ({ page }) => {
    if (isMobile(page)) {
      const menuButton = page.getByRole('button', { name: 'Open navigation menu' })
      await tabTo(page, menuButton)
      await page.keyboard.press('Enter')
      await expect(page.getByRole('dialog', { name: 'Navigation' })).toBeVisible()
    }
    const bookmarks = page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Bookmarks', exact: true })
    await tabTo(page, bookmarks)
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL('/outbound/bookmarks')
    await expect(page.getByRole('dialog', { name: 'Navigation' })).toHaveCount(0)
    await expect(page.getByRole('heading', { level: 1 })).toBeFocused()
  })

  test('navigation layout per breakpoint; the drawer below md opens and closes', async ({ page }) => {
    const menuButton = page.getByRole('button', { name: 'Open navigation menu' })
    if (!isMobile(page)) {
      // md and up: the sidebar (icon rail below lg) is always visible; no drawer button.
      await expect(menuButton).toBeHidden()
      await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible()
      return
    }
    await expect(page.getByRole('navigation', { name: 'Main' })).toBeHidden()
    await menuButton.click()
    const drawer = page.getByRole('dialog', { name: 'Navigation' })
    await expect(drawer).toBeVisible()
    // The modal drawer hides the rest of the page from assistive technology (so no role query for the button here).
    await expect(page.locator('button[aria-haspopup="dialog"]')).toHaveAttribute('aria-expanded', 'true')
    await expect(drawer.getByRole('navigation', { name: 'Main' }).getByRole('link')).toHaveText([
      'Case queue',
      'Bookmarks',
      'Analytics',
    ])
    await expectNoSeriousA11yViolations(page)

    // Esc closes it and focus returns to the menu button.
    await page.keyboard.press('Escape')
    await expect(drawer).toBeHidden()
    await expect(menuButton).toBeFocused()

    // The close button closes it too.
    await menuButton.click()
    await drawer.getByRole('button', { name: 'Close navigation menu' }).click()
    await expect(drawer).toBeHidden()
  })
})

test('keyboard-only login at 375×812', async ({ page }) => {
  test.skip(!isMobile(page), 'mobile project only (frontend.md §10 login budget: 1 login)')
  await page.goto('/login')
  const username = page.getByLabel('Mobile number or employee ID')
  await page.keyboard.press('Tab')
  await expect(username).toBeFocused()
  await page.keyboard.type(SEED_USERS.analyst)
  await page.keyboard.press('Tab')
  await expect(page.getByLabel('Password', { exact: true })).toBeFocused()
  await page.keyboard.type(e2ePassword())
  await tabTo(page, page.getByRole('button', { name: 'Sign in' }), 3)
  const response = waitForLoginResponse(page)
  await page.keyboard.press('Enter')
  expect((await response).status()).toBe(200)
  await expect(page).toHaveURL('/analytics')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
})
