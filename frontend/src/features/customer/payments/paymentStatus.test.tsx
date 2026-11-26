import { focusManager, QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { act, cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { axe } from 'vitest-axe'
import { registerNavigator } from '@/app/navigation'
import { createQueryClient, ME_QUERY_KEY, queryClient } from '@/app/queryClient'
import { idempotencyStorageKey, type IdempotencyRecord } from '@/services/api/idempotency'
import { paymentExpired, paymentFailed, paymentPending, paymentReview, paymentSucceeded, problem } from '@/test/fixtures/customer'
import { meCustomer } from '@/test/fixtures/me'
import { problemResponse } from '@/test/msw/customerHandlers'
import { server } from '@/test/msw/server'
import type { Payment } from '@/types/api'
import { POLL_INTERVAL_MS, POLL_WINDOW_MS } from './paymentPolling'
import {
  CONFIRMING_MESSAGE,
  EXPIRED_MESSAGE,
  MONEY_TAKEN_NOTE,
  PaymentStatusPage,
  POLL_FAILED_NOTICE,
  REVIEW_MESSAGE,
  STILL_WAITING_MESSAGE,
} from './PaymentStatusPage'
import { INVOICES_QUERY_KEY, PAYMENTS_QUERY_KEY } from './queries'

const ME = '*/api/v1/customers/me'
const REF = paymentPending.reference
const STATUS_PATH = `/customer/payments/${REF}`
const withRef = (payment: Payment): Payment => ({ ...payment, reference: REF })
const succeeded = withRef(paymentSucceeded)

/** A reply: a payment, or an HTTP error status (a ProblemDetail). */
type Reply = Payment | number
let client: QueryClient | undefined
let gets: string[]

/** E16: one reply per GET, in order; the last one repeats. */
function serve(...replies: Reply[]) {
  server.use(
    http.get(`${ME}/payments/:reference`, ({ params }) => {
      gets.push(String(params.reference))
      const reply = replies.length > 1 ? replies.shift()! : replies[0]!
      return typeof reply === 'number'
        ? problemResponse(problem(reply, reply === 401 ? 'unauthenticated' : 'internal', 'Something went wrong.'))
        : HttpResponse.json(reply)
    }),
  )
}

function renderPage(path = STATUS_PATH) {
  client = createQueryClient()
  client.setDefaultOptions({ queries: { retry: false } })
  client.setQueryData(ME_QUERY_KEY, meCustomer)
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/customer/payments/:reference" element={<PaymentStatusPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Advances the poll timers (fake `setInterval`/`Date`); fetches and React's scheduling run on real timers. */
async function tick(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

/** Real time for in-flight requests to settle. */
const settle = (ms = 30) => act(() => new Promise<void>((resolve) => setTimeout(resolve, ms)))

/** Waits (real time, max ~2 s) until `n` E16 requests were made, then asserts exactly `n`. */
async function untilGets(n: number) {
  for (let i = 0; i < 100 && gets.length < n; i++) await settle(20)
  await settle()
  expect(gets).toHaveLength(n)
}

const liveRegion = () => screen.getByRole('status')
const recordKey = (invoiceId: number) => idempotencyStorageKey('PAYMENT_INITIATE', '/customers/me/payments', invoiceId)
function storeRecord(invoiceId: number, reference: string) {
  const record: IdempotencyRecord = { key: `key-${invoiceId}`, bodyHash: 'hash', reference, savedAt: Date.now() }
  sessionStorage.setItem(recordKey(invoiceId), JSON.stringify(record))
}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
beforeEach(() => {
  gets = []
  sessionStorage.clear()
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
  focusManager.setFocused(undefined)
  server.resetHandlers()
  client?.clear()
  client = undefined
})
afterAll(() => server.close())

describe('Payment status polling (frontend.md §3 Payment status; CUS-08.3, XC-07)', () => {
  it('polls E16 every 3 s while PENDING and shows success only when the server says SUCCEEDED; then stops', async () => {
    serve(paymentPending, paymentPending, succeeded)
    renderPage()

    expect(await screen.findByText(CONFIRMING_MESSAGE)).toBeInTheDocument()
    expect(liveRegion()).toHaveTextContent(CONFIRMING_MESSAGE)
    expect(screen.getByText('Processing')).toBeInTheDocument()
    expect(screen.getByText('₹589.82')).toBeInTheDocument()
    await untilGets(1)

    await tick(POLL_INTERVAL_MS - 1)
    await untilGets(1)
    await tick(1)
    await untilGets(2)
    expect(screen.queryByText('Payment received')).not.toBeInTheDocument()

    await tick(POLL_INTERVAL_MS)
    const heading = await screen.findByRole('heading', { level: 2, name: 'Payment received' })
    expect(gets).toEqual([REF, REF, REF])
    // Terminal: focus moves to the result heading; the live region is not updated with the result.
    expect(heading).toHaveFocus()
    expect(liveRegion()).toHaveTextContent('')
    expect(screen.getByText('Paid')).toBeInTheDocument()
    expect(screen.getByText('UPI')).toBeInTheDocument()
    expect(screen.getByText('25 Nov 2026, 3:32 pm')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View receipt' })).toHaveAttribute('href', `/customer/payments/${REF}/receipt`)
    expect(screen.getByRole('link', { name: 'Back to home' })).toHaveAttribute('href', '/customer')

    await tick(POLL_INTERVAL_MS * 3)
    await untilGets(3)
  })

  it('the browser return and its query string are ignored: PENDING from the server stays PENDING', async () => {
    serve(paymentPending)
    renderPage(`${STATUS_PATH}?status=SUCCEEDED&result=success`)
    expect(await screen.findByText(CONFIRMING_MESSAGE)).toBeInTheDocument()
    expect(screen.queryByText('Payment received')).not.toBeInTheDocument()
    expect(gets).toEqual([REF])
  })

  it('PENDING → 500 → PENDING → SUCCEEDED keeps the last status with a notice, never FAILED, and keeps polling', async () => {
    serve(paymentPending, 500, paymentPending, succeeded)
    renderPage()
    await screen.findByText(CONFIRMING_MESSAGE)

    await tick(POLL_INTERVAL_MS)
    expect(await screen.findByText(POLL_FAILED_NOTICE)).toBeInTheDocument()
    expect(screen.getByText(CONFIRMING_MESSAGE)).toBeInTheDocument()
    expect(screen.getByText('Processing')).toBeInTheDocument()
    expect(screen.queryByText(/failed/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    await tick(POLL_INTERVAL_MS)
    await untilGets(3)
    expect(screen.queryByText(POLL_FAILED_NOTICE)).not.toBeInTheDocument()
    expect(screen.getByText(CONFIRMING_MESSAGE)).toBeInTheDocument()

    await tick(POLL_INTERVAL_MS)
    expect(await screen.findByRole('heading', { level: 2, name: 'Payment received' })).toBeInTheDocument()
  })

  it('after 2 min of PENDING: "Still waiting", polling stops; "Check again" fetches now and opens a new 2 min window', async () => {
    const user = userEvent.setup()
    serve(paymentPending)
    renderPage()
    await screen.findByText(CONFIRMING_MESSAGE)

    await tick(POLL_WINDOW_MS)
    expect(await screen.findByText(STILL_WAITING_MESSAGE)).toBeInTheDocument()
    expect(liveRegion()).toHaveTextContent(STILL_WAITING_MESSAGE)
    expect(screen.getByRole('link', { name: 'Payment history' })).toHaveAttribute('href', '/customer/payments')
    await settle()
    const afterWindow = gets.length

    await tick(POLL_INTERVAL_MS * 5)
    await untilGets(afterWindow)

    await user.click(screen.getByRole('button', { name: 'Check again' }))
    expect(await screen.findByText(CONFIRMING_MESSAGE)).toBeInTheDocument()
    await untilGets(afterWindow + 1)
    await tick(POLL_INTERVAL_MS)
    await untilGets(afterWindow + 2)
  })

  it('stops polling on unmount', async () => {
    serve(paymentPending)
    const { unmount } = renderPage()
    await screen.findByText(CONFIRMING_MESSAGE)
    await untilGets(1)
    unmount()
    await tick(POLL_INTERVAL_MS * 5)
    await untilGets(1)
  })

  it('pauses while the tab is hidden and refetches when it is visible again', async () => {
    serve(paymentPending, paymentPending, succeeded)
    renderPage()
    await screen.findByText(CONFIRMING_MESSAGE)
    await untilGets(1)

    act(() => focusManager.setFocused(false))
    await tick(POLL_INTERVAL_MS * 3)
    await untilGets(1)

    act(() => focusManager.setFocused(true))
    await untilGets(2)
    await tick(POLL_INTERVAL_MS)
    expect(await screen.findByRole('heading', { level: 2, name: 'Payment received' })).toBeInTheDocument()
  })
})

describe('Payment status results (CUS-08.4, CUS-08.5)', () => {
  it('SUCCEEDED drops the stored E15 record of this payment only and refreshes payments and invoices', async () => {
    storeRecord(3, REF)
    storeRecord(4, 'PAY-20261125-000099')
    serve(paymentPending, succeeded)
    renderPage()
    await screen.findByText(CONFIRMING_MESSAGE)
    const invalidate = vi.spyOn(client!, 'invalidateQueries')
    expect(sessionStorage.getItem(recordKey(3))).not.toBeNull()

    await tick(POLL_INTERVAL_MS)
    await screen.findByRole('heading', { level: 2, name: 'Payment received' })
    expect(sessionStorage.getItem(recordKey(3))).toBeNull()
    expect(sessionStorage.getItem(recordKey(4))).not.toBeNull()
    expect(invalidate).toHaveBeenCalledWith({ queryKey: PAYMENTS_QUERY_KEY })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: INVOICES_QUERY_KEY })
  })

  it('FAILED (gateway_unavailable): reason, the 24 h note, "Try again" → pay bill; the stored record is dropped', async () => {
    storeRecord(3, REF)
    serve(withRef(paymentFailed))
    renderPage()
    expect(await screen.findByRole('heading', { level: 2, name: 'Payment failed' })).toBeInTheDocument()
    expect(screen.getByText('The payment provider was unavailable.')).toBeInTheDocument()
    expect(screen.getByText(MONEY_TAKEN_NOTE)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Try again' })).toHaveAttribute('href', '/customer/payments/pay')
    expect(screen.getByText('Failed')).toBeInTheDocument()
    expect(sessionStorage.getItem(recordKey(3))).toBeNull()
  })

  it('FAILED with another reason stays generic', async () => {
    serve(withRef({ ...paymentFailed, failureReason: 'card_declined_by_issuer_x' }))
    renderPage()
    expect(await screen.findByText('The payment was not completed.')).toBeInTheDocument()
    expect(screen.queryByText(/card_declined/)).not.toBeInTheDocument()
  })

  it('EXPIRED: expiry copy, the 24 h note and "Try again"; no polling', async () => {
    storeRecord(3, REF)
    serve(withRef(paymentExpired))
    renderPage()
    expect(await screen.findByText(EXPIRED_MESSAGE)).toBeInTheDocument()
    expect(screen.getByText(MONEY_TAKEN_NOTE)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Try again' })).toHaveAttribute('href', '/customer/payments/pay')
    expect(screen.getByText('Expired')).toBeInTheDocument()
    expect(sessionStorage.getItem(recordKey(3))).toBeNull()
    await tick(POLL_INTERVAL_MS * 3)
    await untilGets(1)
  })

  it('REVIEW: honest copy, payment history and contact support; not polled', async () => {
    serve(withRef(paymentReview))
    renderPage()
    expect(await screen.findByText(REVIEW_MESSAGE)).toBeInTheDocument()
    expect(screen.getByText('Under review')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Payment history' })).toHaveAttribute('href', '/customer/payments')
    expect(screen.getByRole('link', { name: 'Contact support' })).toHaveAttribute('href', '/customer/requests/new')
    expect(screen.queryByText('Payment received')).not.toBeInTheDocument()
    await tick(POLL_INTERVAL_MS * 3)
    await untilGets(1)
  })

  it('a first load that fails shows ErrorState with retry', async () => {
    const user = userEvent.setup()
    serve(500, paymentPending)
    renderPage()
    expect(await screen.findByRole('heading', { name: "We couldn't load this payment" })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText(CONFIRMING_MESSAGE)).toBeInTheDocument()
  })

  it('an unknown or other customer\'s payment (404) shows Not found', async () => {
    serve(404)
    renderPage()
    expect(await screen.findByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument()
  })

  it('an expired session (401) goes to login with returnTo', async () => {
    const navigate = vi.fn<(to: string) => unknown>()
    const unregister = registerNavigator({
      navigate,
      location: () => ({ pathname: STATUS_PATH, search: '?status=ok', hash: '' }),
    })
    queryClient.setQueryData(ME_QUERY_KEY, meCustomer)
    try {
      serve(401)
      renderPage()
      await untilGets(1)
      expect(navigate).toHaveBeenCalledWith(`/login?reason=expired&returnTo=${encodeURIComponent(`${STATUS_PATH}?status=ok`)}`)
    } finally {
      unregister()
      queryClient.clear()
    }
  })

  it.each([
    ['PENDING', paymentPending],
    ['SUCCEEDED', succeeded],
    ['FAILED', withRef(paymentFailed)],
  ])('%s is axe clean', async (_status, payment) => {
    serve(payment)
    const { container } = renderPage()
    await screen.findByText(payment.status === 'PENDING' ? CONFIRMING_MESSAGE : /Payment (received|failed)/)
    expect(await axe(container)).toHaveNoViolations()
  })
})
