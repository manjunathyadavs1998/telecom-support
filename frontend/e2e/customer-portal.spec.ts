import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { SEED_USERS, storageStateFor } from './helpers/users.ts'

// 02 frontend.md §7, §8 e2e `customer-portal.spec.ts` (CUS-01/03/05/06/07, XC-04, XC-08): customer 1 (+919800000001,
// R__02_customer + R__02_customer_e2e seed) in the desktop, tablet and mobile projects. Read-only apart from one request
// create per project, which is asserted by its new reference only. Setup storageState only: no logins here.

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

/** `YYYY-MM-DD` in Asia/Kolkata, `daysAgo` days before today (the date filters are IST dates, XC-05). */
function istDate(daysAgo = 0): string {
  const istNow = new Date(Date.now() + 330 * 60_000)
  istNow.setUTCDate(istNow.getUTCDate() - daysAgo)
  return istNow.toISOString().slice(0, 10)
}

async function openPage(page: Page, path: string, title: string) {
  await page.goto(path)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(title)
}

test.describe(`${SEED_USERS.customer} portal`, () => {
  test.use({ storageState: storageStateFor('customer') })

  test('dashboard: every card loads its own data (CUS-01)', async ({ page }) => {
    await openPage(page, '/customer', 'Home')
    const main = page.getByRole('main')
    for (const card of ['My account', 'My current plan', 'Quick links', 'Recent requests', 'Bills']) {
      await expect(main.getByRole('heading', { level: 2, name: card })).toBeVisible()
    }
    await expect(main.getByText('Last payment', { exact: true })).toBeVisible()
    // Every card finished loading without an error state.
    await expect(main.getByText(/^Loading /)).toHaveCount(0)
    await expect(main.getByText(/^We couldn't load/)).toHaveCount(0)
    await expectNoSeriousA11yViolations(page)
  })

  test('my plan: "View details" expands all benefits (CUS-03)', async ({ page }) => {
    await openPage(page, '/customer/plan', 'My plan')
    const toggle = page.getByRole('button', { name: 'View details' }).first()
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    const detailsId = await toggle.getAttribute('aria-controls')
    expect(detailsId).toBeTruthy()
    const details = page.locator(`[id="${detailsId}"]`)
    await expect(details).toBeHidden()
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await expect(details).toBeVisible()
    await expectNoSeriousA11yViolations(page)
  })

  test('payment history: page 2 keeps the custom date range (CUS-07, XC-04, XC-05)', async ({ page }) => {
    await openPage(page, '/customer/payments', 'Payment history')
    const from = istDate(60)
    const to = istDate(0)
    await page.getByRole('tab', { name: 'Custom range' }).click()
    await page.getByLabel('From').fill(from)
    await page.getByLabel('To').fill(to)
    await page.getByRole('button', { name: 'Apply' }).click()
    await expect(page).toHaveURL(new RegExp(`[?&]from=${from}(&|$)`))
    await expect(page).toHaveURL(new RegExp(`[?&]to=${to}(&|$)`))
    await expect(page.getByText(/^Showing 1–20 of \d+/)).toBeVisible()

    await page.getByRole('navigation', { name: 'Pagination' }).getByRole('button', { name: 'Next' }).click()
    await expect(page).toHaveURL(/[?&]page=2(&|$)/)
    await expect(page).toHaveURL(new RegExp(`[?&]from=${from}(&|$)`))
    await expect(page).toHaveURL(new RegExp(`[?&]to=${to}(&|$)`))
    await expect(page.getByText(/^Showing 21–/)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Page 2' })).toHaveAttribute('aria-current', 'page')
    // The filter UI still shows the applied range.
    await expect(page.getByRole('tab', { name: 'Custom range' })).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByLabel('From')).toHaveValue(from)
    await expect(page.getByLabel('To')).toHaveValue(to)
    await expect(page.getByRole('main').getByText(/^PAY-/).filter({ visible: true }).first()).toBeVisible()
    await expectNoSeriousA11yViolations(page)
  })

  test('create a request, then find it by its reference (CUS-06, CUS-05)', async ({ page }) => {
    await openPage(page, '/customer/requests/new', 'New request')
    await page.getByRole('combobox', { name: 'Category' }).click()
    await page.getByRole('option').first().click()
    const subcategory = page.getByRole('combobox', { name: 'Subcategory' })
    if (await subcategory.isVisible()) {
      await subcategory.click()
      await page.getByRole('option').first().click()
    }
    await page.getByLabel('Subject').fill('E2E portal check: please ignore')
    await page.getByLabel('Description').fill('Created by the customer portal e2e run to check request search.')
    await expectNoSeriousA11yViolations(page)
    await page.getByRole('button', { name: 'Submit request' }).click()

    const confirmation = page.getByRole('status').filter({ hasText: /^Request SR-\S+ submitted$/ })
    await expect(confirmation).toBeVisible()
    const reference = (await confirmation.textContent())?.match(/SR-\S+/)?.[0]
    expect(reference).toBeTruthy()

    await page.getByRole('link', { name: 'View my requests' }).click()
    await expect(page).toHaveURL('/customer/requests')
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Service requests')
    await page.getByRole('textbox', { name: 'Reference' }).fill(reference!)
    await page.getByRole('button', { name: 'Search', exact: true }).click()
    await expect(page).toHaveURL(new RegExp(`[?&]reference=${reference}(&|$)`))
    const found = page.getByRole('main').getByText(reference!, { exact: true }).filter({ visible: true })
    await expect(found).toHaveCount(1)
    await expect(page.getByText(/^Showing 1–1 of 1$/)).toBeVisible()
    await expectNoSeriousA11yViolations(page)
  })
})
