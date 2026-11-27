import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, useLocation } from 'react-router'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { axe } from 'vitest-axe'
import { createQueryClient, ME_QUERY_KEY } from '@/app/queryClient'
import { INVALID_LINK_NOTICE } from '@/components/data/useListParams'
import { defaultWindow } from '@/lib/dateWindow'
import {
  emptyPaymentPage,
  paymentExpired,
  paymentFailed,
  paymentPage,
  paymentPending,
  paymentReview,
  paymentSucceeded,
  problem,
} from '@/test/fixtures/customer'
import { meCustomer } from '@/test/fixtures/me'
import { problemResponse } from '@/test/msw/customerHandlers'
import { server } from '@/test/msw/server'
import type { Me, Payment, PaymentPage } from '@/types/api'
import { PaymentHistoryPage } from './PaymentHistoryPage'

const PAYMENTS_URL = '*/api/v1/customers/me/payments'
const window3m = defaultWindow()

let client: QueryClient
let queries: Record<string, string>[]

/** E14 with every request's query recorded; `reply` decides the page per request. */
function serve(reply: (query: Record<string, string>) => PaymentPage | Response | Promise<Response> = () => paymentPage()) {
  server.use(
    http.get(PAYMENTS_URL, async ({ request }) => {
      const query = Object.fromEntries(new URL(request.url).searchParams)
      queries.push(query)
      const result = await reply(query)
      return result instanceof Response ? result : HttpResponse.json(result)
    }),
  )
}

function LocationProbe() {
  const { search } = useLocation()
  return <span data-testid="url">{search}</span>
}

function renderHistory(path = '/customer/payments', me: Me = meCustomer) {
  client = createQueryClient()
  client.setDefaultOptions({ queries: { retry: false } })
  client.setQueryData(ME_QUERY_KEY, me)
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <PaymentHistoryPage />
        <LocationProbe />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const url = () => screen.getByTestId('url').textContent ?? ''
const wide = () => within(screen.getByTestId('data-table-wide'))
const findInTable = async (text: string) =>
  within(await screen.findByTestId('data-table-wide')).findByText(text, { selector: '.font-mono' })
const rowOf = (reference: string) => wide().getByText(reference, { selector: '.font-mono' }).closest('tr')!

function manyPayments(count: number, offset = 0): Payment[] {
  return Array.from({ length: count }, (_, i) => ({
    ...paymentFailed,
    reference: `PAY-20261125-${String(offset + i + 1).padStart(6, '0')}`,
  }))
}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
beforeEach(() => {
  queries = []
})
afterEach(() => {
  cleanup()
  server.resetHandlers()
  client.clear()
})
afterAll(() => server.close())

describe('Payment history (frontend.md §3 Payment history; CUS-07, BR-05/06, XC-04)', () => {
  it('default: last 3 months (no dates sent), page 0 size 20; the CUS-07.1 columns; Pay bill action; axe clean', async () => {
    serve()
    const { container } = renderHistory()
    expect(screen.getByText('Loading payments')).toBeInTheDocument()
    await findInTable(paymentSucceeded.reference)
    expect(queries).toEqual([{ page: '0', size: '20' }])
    expect(wide().getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'Reference',
      'Invoice',
      'Date/time (IST)',
      'Amount',
      'Method',
      'Status',
      'Action',
    ])
    const cells = within(rowOf(paymentSucceeded.reference))
      .getAllByRole('cell')
      .map((c) => c.textContent)
    expect(cells).toEqual([
      'PAY-20261125-000008',
      'INV-202610-000001',
      '25 Nov 2026, 3:30 pm',
      '₹589.82',
      'UPI',
      'Paid',
      'Receipt for PAY-20261125-000008',
    ])
    expect(within(rowOf(paymentPending.reference)).getAllByRole('cell')[4]).toHaveTextContent('—')
    expect(screen.getByRole('tab', { name: 'Last 3 months' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('link', { name: 'Pay bill' })).toHaveAttribute('href', '/customer/payments/pay')
    expect(document.title).toBe('Payment history · Telecom Support')
    expect(await axe(container)).toHaveNoViolations()
  })

  it('Receipt only when the server says one is available; View (status page) for PENDING and REVIEW; nothing otherwise', async () => {
    serve(() => paymentPage([paymentSucceeded, paymentPending, paymentReview, paymentFailed, paymentExpired]))
    renderHistory()
    await findInTable(paymentSucceeded.reference)
    expect(within(rowOf(paymentSucceeded.reference)).getByRole('link', { name: /^Receipt/ })).toHaveAttribute(
      'href',
      `/customer/payments/${paymentSucceeded.reference}/receipt`,
    )
    expect(within(rowOf(paymentPending.reference)).getByRole('link', { name: /^View/ })).toHaveAttribute(
      'href',
      `/customer/payments/${paymentPending.reference}`,
    )
    expect(within(rowOf(paymentPending.reference)).getByText('Processing')).toBeInTheDocument()
    expect(within(rowOf(paymentReview.reference)).getByRole('link', { name: /^View/ })).toHaveAttribute(
      'href',
      `/customer/payments/${paymentReview.reference}`,
    )
    expect(within(rowOf(paymentReview.reference)).getByText('Under review')).toBeInTheDocument()
    for (const payment of [paymentFailed, paymentExpired]) {
      expect(within(rowOf(payment.reference)).queryByRole('link')).not.toBeInTheDocument()
    }
    expect(wide().getAllByRole('link', { name: /^Receipt/ })).toHaveLength(1)
  })

  it('a SUCCEEDED payment the server marks without receipt shows no Receipt link', async () => {
    serve(() => paymentPage([{ ...paymentSucceeded, receiptAvailable: false }]))
    renderHistory()
    await findInTable(paymentSucceeded.reference)
    expect(within(rowOf(paymentSucceeded.reference)).queryByRole('link')).not.toBeInTheDocument()
  })

  it('custom range: Apply sends from/to and keeps them in the URL', async () => {
    serve()
    const user = userEvent.setup()
    renderHistory()
    await findInTable(paymentSucceeded.reference)
    await user.click(screen.getByRole('tab', { name: 'Custom range' }))
    fireEvent.change(screen.getByLabelText('From'), { target: { value: window3m.from } })
    fireEvent.change(screen.getByLabelText('To'), { target: { value: window3m.to } })
    await user.click(screen.getByRole('button', { name: 'Apply' }))
    await waitFor(() => expect(queries.at(-1)).toEqual({ ...window3m, page: '0', size: '20' }))
    expect(url()).toBe(`?from=${window3m.from}&to=${window3m.to}`)
  })

  it('paging: 1-based URL page → 0-based request, "Showing 21–40 of 45", dates kept', async () => {
    serve(({ page }) => paymentPage(manyPayments(20, Number(page) * 20), Number(page), 20, 45))
    const user = userEvent.setup()
    renderHistory(`/customer/payments?from=${window3m.from}&to=${window3m.to}`)
    await screen.findByText('Showing 1–20 of 45')
    await user.click(screen.getByRole('button', { name: 'Page 2' }))
    expect(await findInTable('PAY-20261125-000021')).toBeInTheDocument()
    expect(queries.at(-1)).toEqual({ ...window3m, page: '1', size: '20' })
    expect(screen.getByText('Showing 21–40 of 45')).toBeInTheDocument()
    expect(url()).toBe(`?from=${window3m.from}&to=${window3m.to}&page=2`)
  })

  it('invalid link params (incl. a reference: no search on this list) are dropped before any request, with a notice', async () => {
    serve()
    renderHistory('/customer/payments?from=2026-13-01&to=2026-11-01&reference=PAY-1&page=0')
    expect(await screen.findByText(INVALID_LINK_NOTICE)).toBeInTheDocument()
    await findInTable(paymentSucceeded.reference)
    expect(queries).toEqual([{ page: '0', size: '20' }])
  })

  it.each([
    ['/customer/payments', 'No payments in the last 3 months'],
    ['/customer/payments?from=2026-09-01&to=2026-09-30', 'No payments between 1 Sep 2026 and 30 Sep 2026'],
  ])('empty %s → "%s" with Change dates', async (path, title) => {
    serve(() => emptyPaymentPage)
    const user = userEvent.setup()
    renderHistory(path)
    const heading = await screen.findByRole('heading', { level: 2, name: title })
    await user.click(within(heading.parentElement!).getByRole('button', { name: 'Change dates' }))
    await waitFor(() => expect(screen.getByLabelText('From')).toHaveFocus())
  })

  it('a 400 on a filter change shows the message under the filters and keeps the last good page', async () => {
    serve(({ from }) => (from ? problemResponse(problem(400, 'validation', 'The date range is not allowed.')) : paymentPage()))
    const user = userEvent.setup()
    renderHistory()
    await findInTable(paymentSucceeded.reference)
    await user.click(screen.getByRole('tab', { name: 'Custom range' }))
    fireEvent.change(screen.getByLabelText('From'), { target: { value: window3m.from } })
    fireEvent.change(screen.getByLabelText('To'), { target: { value: window3m.to } })
    await user.click(screen.getByRole('button', { name: 'Apply' }))
    expect(await screen.findByText('The date range is not allowed.')).toBeInTheDocument()
    expect(await findInTable(paymentSucceeded.reference)).toBeInTheDocument()
  })

  it('a 500 shows ErrorState with retry; retry loads the list', async () => {
    let fail = true
    serve(() => (fail ? problemResponse(problem(500, 'internal', 'Unexpected error.')) : paymentPage()))
    const user = userEvent.setup()
    renderHistory()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent("We couldn't load your payments")
    fail = false
    await user.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await findInTable(paymentSucceeded.reference)).toBeInTheDocument()
  })

  it('a 403 on the list renders the forbidden page', async () => {
    serve(() => problemResponse(problem(403, 'forbidden', 'Access denied.')))
    renderHistory()
    expect(await screen.findByRole('heading', { level: 1, name: 'Access denied' })).toBeInTheDocument()
  })

  it('without payment.initiate there is no Pay bill action', async () => {
    serve(() => emptyPaymentPage)
    renderHistory('/customer/payments', {
      ...meCustomer,
      permissions: meCustomer.permissions.filter((p) => p !== 'payment.initiate'),
    })
    await screen.findByRole('heading', { level: 2, name: 'No payments in the last 3 months' })
    expect(screen.queryByRole('link', { name: 'Pay bill' })).not.toBeInTheDocument()
  })
})
