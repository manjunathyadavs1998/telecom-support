import { expect, test, type Page } from '@playwright/test'
import { SEED_USERS, storageStateFor } from './helpers/users.ts'

// 02 frontend.md §7, §8 e2e `payment.spec.ts` (CUS-08, CUS-07.4, XC-07): customer 5 (+919800000005, R__02_customer_e2e
// seed: exactly 2 OPEN invoices, nothing else) pays through the same-origin mock checkout. Invoice A (INV-D-0501) → "Fail" →
// the gateway returns to `/customer/payments/PAY-…` (backend erratum E4) → FAILED; invoice B (INV-D-0502) → "Pay" (UPI) →
// Paid → receipt, whose print media hides the shell. Runs once, serially, in the `chromium-payment` project; it needs a
// fresh `dev,e2e` JAR (clean database). Setup storageState only: no logins here.

test.describe.configure({ mode: 'serial', retries: 0 })

const STATUS_URL = /\/customer\/payments\/(PAY-[0-9]+-[0-9]+)$/
// The status page polls every 3 s (frontend.md §3 Payment status); the webhook normally settles it before the return.
const SETTLE_TIMEOUT = 20_000

/** Pay bill → choose the invoice → Pay → the mock checkout page (same origin, `/mock-gateway/checkout/{session}`). */
async function openCheckout(page: Page, invoiceRef: string, amount: string) {
  await page.goto('/customer/payments/pay')
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Pay bill')
  const choice = page.getByRole('radio', { name: new RegExp(`^${invoiceRef} · `) })
  await choice.click()
  await expect(choice).toBeChecked()
  await expect(page.getByRole('region', { name: `Bill ${invoiceRef}` })).toBeVisible()
  await page.getByRole('button', { name: `Pay ₹${amount}` }).click()
  await expect(page).toHaveURL(/\/mock-gateway\/checkout\/[^/]+$/)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Mock checkout')
  await expect(page.getByText(`INR ${amount}`)).toBeVisible()
}

/** The gateway returned to the status page; returns the payment reference from the URL. */
async function statusReference(page: Page): Promise<string> {
  await expect(page).toHaveURL(STATUS_URL)
  const reference = STATUS_URL.exec(new URL(page.url()).pathname)?.[1]
  expect(reference).toBeTruthy()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(reference!)
  return reference!
}

test.describe(`${SEED_USERS.customer5} payment`, () => {
  test.use({ storageState: storageStateFor('customer5') })

  test('invoice A: the gateway fails → status page shows FAILED (CUS-08.3, CUS-08.4)', async ({ page }) => {
    await openCheckout(page, 'INV-D-0501', '299.00')
    await page.getByRole('button', { name: 'Fail', exact: true }).click()

    await statusReference(page)
    const main = page.getByRole('main')
    await expect(main.getByRole('heading', { level: 2, name: 'Payment failed' })).toBeVisible({ timeout: SETTLE_TIMEOUT })
    await expect(main.getByText('Failed', { exact: true })).toBeVisible()
    await expect(main.getByRole('link', { name: 'Try again' })).toBeVisible()
    await expect(main.getByRole('link', { name: 'View receipt' })).toHaveCount(0)
  })

  test('invoice B: pay with UPI → Paid → receipt, printable without the shell (CUS-08.5, CUS-07.4)', async ({ page }) => {
    await openCheckout(page, 'INV-D-0502', '349.00')
    await page.getByRole('radio', { name: 'UPI' }).check()
    await page.getByRole('button', { name: 'Pay', exact: true }).click()

    const reference = await statusReference(page)
    const main = page.getByRole('main')
    await expect(main.getByRole('heading', { level: 2, name: 'Payment received' })).toBeVisible({ timeout: SETTLE_TIMEOUT })
    await expect(main.getByText('Paid', { exact: true })).toBeVisible()
    await expect(main.getByText('INV-D-0502', { exact: true })).toBeVisible()

    await main.getByRole('link', { name: 'View receipt' }).click()
    await expect(page).toHaveURL(`/customer/payments/${reference}/receipt`)
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Receipt')
    const receipt = main.getByRole('heading', { level: 2, name: 'Payment receipt' })
    await expect(receipt).toBeVisible()
    await expect(main.getByText(reference, { exact: true })).toBeVisible()
    await expect(main.getByText('INV-D-0502', { exact: true })).toBeVisible()
    await expect(main.getByText('Kavya Nair', { exact: true })).toBeVisible()
    const print = page.getByRole('button', { name: 'Print or save as PDF' })
    await expect(print).toBeVisible()
    await expect(page.getByRole('banner')).toBeVisible()
    await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible()

    // Print media (frontend.md §3 Receipt): the sidebar, the header and the buttons are hidden; the receipt stays.
    await page.emulateMedia({ media: 'print' })
    await expect(page.getByRole('banner')).toBeHidden()
    await expect(page.getByRole('navigation', { name: 'Main' })).toBeHidden()
    await expect(print).toBeHidden()
    await expect(receipt).toBeVisible()
    await expect(main.getByText(reference, { exact: true })).toBeVisible()
    await page.emulateMedia({ media: 'screen' })
    await expect(page.getByRole('banner')).toBeVisible()

    // Back in the history, the payment offers its receipt (CUS-07.4).
    await page.goto('/customer/payments')
    await expect(page.getByRole('link', { name: `Receipt for ${reference}` }).filter({ visible: true })).toBeVisible()
  })
})
