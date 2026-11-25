import { expect, test } from '@playwright/test'
import { SEED_USERS, storageStateFor, type SeedUserKey } from './helpers/users.ts'

type Group = { heading: string | null; items: { label: string; path: string }[] }
type Expected = { landing: string; nav: Group[] }

// The expected landing and sidebar per role combination, copied from the design table (frontend.md §2 "Resulting nav and
// landing per role combination", backend.md §3.1), not derived from navConfig.ts. Headings show only for > 1 section.
const CUSTOMER: Expected = {
  landing: '/customer',
  nav: [
    {
      heading: null,
      items: [
        { label: 'Home', path: '/customer' },
        { label: 'Profile', path: '/customer/profile' },
        { label: 'My plan', path: '/customer/plan' },
        { label: 'Promotions', path: '/customer/promotions' },
        { label: 'Service requests', path: '/customer/requests' },
        { label: 'Payment history', path: '/customer/payments' },
      ],
    },
  ],
}
const INBOUND: Expected = {
  landing: '/inbound',
  nav: [{ heading: null, items: [{ label: 'Customer search', path: '/inbound' }] }],
}
const OUTBOUND: Expected = {
  landing: '/outbound',
  nav: [
    {
      heading: 'Outbound',
      items: [
        { label: 'Case queue', path: '/outbound' },
        { label: 'Bookmarks', path: '/outbound/bookmarks' },
      ],
    },
    { heading: 'Operations', items: [{ label: 'Analytics', path: '/analytics' }] },
  ],
}
const BUSINESS: Expected = {
  landing: '/analytics',
  nav: [
    {
      heading: null,
      items: [
        { label: 'Analytics', path: '/analytics' },
        { label: 'Audit & export', path: '/audit' },
      ],
    },
  ],
}

const EXPECTED: Record<SeedUserKey, Expected> = {
  customer: CUSTOMER,
  inbound: INBOUND,
  inboundApprover: INBOUND,
  outbound: OUTBOUND,
  supervisor: OUTBOUND,
  analyst: BUSINESS,
  auditor: BUSINESS,
}

for (const [user, expected] of Object.entries(EXPECTED) as [SeedUserKey, Expected][]) {
  test.describe(`${SEED_USERS[user]}`, () => {
    test.use({ storageState: storageStateFor(user) })

    test(`lands on ${expected.landing} and sees exactly its nav`, async ({ page }) => {
      await page.goto('/')
      await expect(page).toHaveURL(expected.landing)

      const nav = page.getByRole('navigation', { name: 'Main' })
      const allItems = expected.nav.flatMap((group) => group.items)
      await expect(nav.getByRole('link')).toHaveText(allItems.map((item) => item.label))

      const groups = await nav.locator(':scope > div').evaluateAll((sections) =>
        sections.map((section) => ({
          heading: section.querySelector(':scope > p')?.textContent?.trim() ?? null,
          items: Array.from(section.querySelectorAll('a')).map((link) => ({
            label: link.textContent?.trim() ?? '',
            path: new URL(link.href).pathname,
          })),
        })),
      )
      expect(groups).toEqual(expected.nav)
    })

    test('a direct deep link survives a reload (SPA forward)', async ({ page }) => {
      const target = expected.nav.at(-1)!.items.at(-1)!
      const first = await page.goto(target.path)
      expect(first?.status()).toBe(200)
      const reloaded = await page.reload()
      expect(reloaded?.status()).toBe(200)
      await expect(page).toHaveURL(target.path)
      const nav = page.getByRole('navigation', { name: 'Main' })
      await expect(nav.getByRole('link', { name: target.label, exact: true })).toHaveAttribute('aria-current', 'page')
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    })
  })
}
