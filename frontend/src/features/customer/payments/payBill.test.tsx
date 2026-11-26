import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest'
import { axe } from 'vitest-axe'
import { createQueryClient, ME_QUERY_KEY } from '@/app/queryClient'
import { hashBody, idempotencyStorageKey, type IdempotencyRecord } from '@/services/api/idempotency'
import {
  invoiceOpenNewer,
  invoiceOpenOlder,
  paymentFailed,
  paymentInitiated,
  paymentPending,
  paymentReview,
  problem,
} from '@/test/fixtures/customer'
import { meCustomer } from '@/test/fixtures/me'
import { problemResponse } from '@/test/msw/customerHandlers'
import { server } from '@/test/msw/server'
import type { Invoice, Payment, PaymentInitiated, PaymentInitiateRequest } from '@/types/api'
import {
  AMOUNT_CHANGED_MESSAGE,
  BILL_GONE_MESSAGE,
  PAYMENT_METHODS_INFO,
  PayBillPage,
  PROVIDER_UNAVAILABLE_MESSAGE,
  REDIRECT_REFUSED_TITLE,
} from './PayBillPage'
import { browserNavigation, resolveRedirect } from './redirect'

const ME = '*/api/v1/customers/me'
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const STORED_KEY = '11111111-2222-4333-8444-555555555555'
const recordKey = (invoiceId: number) => idempotencyStorageKey('PAYMENT_INITIATE', '/customers/me/payments', invoiceId)
const bodyFor = (invoice: Invoice): PaymentInitiateRequest => ({ invoiceId: invoice.id, expectedAmount: invoice.outstanding })

type Post = { key: string | null; body: PaymentInitiateRequest }

let client: QueryClient | undefined
let invoiceGets: number
let paymentGets: string[]
let posts: Post[]
let assign: MockInstance<(url: string) => void>

/** E13, E15, E16 with every request recorded. `replies`: one per POST, in order; after them, 201 with `initiated`. */
function serve({
  invoices = [invoiceOpenOlder],
  replies = [],
  initiated = paymentInitiated,
  payments = [paymentPending, paymentFailed, paymentReview],
}: {
  invoices?: Invoice[] | (() => Invoice[] | Response)
  replies?: (() => Response | Promise<Response>)[]
  initiated?: PaymentInitiated
  payments?: Payment[]
} = {}) {
  server.use(
    http.get(`${ME}/invoices`, ({ request }) => {
      invoiceGets += 1
      expect(new URL(request.url).searchParams.get('status')).toBe('OPEN')
      const result = typeof invoices === 'function' ? invoices() : invoices
      return result instanceof Response ? result : HttpResponse.json(result)
    }),
    http.get(`${ME}/payments/:reference`, ({ params }) => {
      paymentGets.push(String(params.reference))
      const payment = payments.find((p) => p.reference === params.reference)
      return payment ? HttpResponse.json(payment) : problemResponse(problem(404, 'not-found', 'Payment not found.'))
    }),
    http.post(`${ME}/payments`, async ({ request }) => {
      posts.push({ key: request.headers.get('Idempotency-Key'), body: (await request.json()) as PaymentInitiateRequest })
      const reply = replies.shift()
      if (reply) return reply()
      return HttpResponse.json(initiated, { status: 201 })
    }),
  )
}

function store(invoice: Invoice, record: Partial<IdempotencyRecord> = {}) {
  const full: IdempotencyRecord = { key: STORED_KEY, bodyHash: hashBody(bodyFor(invoice)), savedAt: Date.now(), ...record }
  sessionStorage.setItem(recordKey(invoice.id), JSON.stringify(full))
}
const stored = (invoice: Invoice): IdempotencyRecord | null => {
  const raw = sessionStorage.getItem(recordKey(invoice.id))
  return raw === null ? null : (JSON.parse(raw) as IdempotencyRecord)
}

function renderPage() {
  client = createQueryClient()
  client.setDefaultOptions({ queries: { retry: false } })
  client.setQueryData(ME_QUERY_KEY, meCustomer)
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/customer/payments/pay']}>
        <PayBillPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const payButton = (amount = '₹589.82') => screen.findByRole('button', { name: `Pay ${amount}` })

function pageShow(persisted: boolean) {
  const event = new Event('pageshow')
  Object.defineProperty(event, 'persisted', { value: persisted })
  act(() => {
    window.dispatchEvent(event)
  })
}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
beforeEach(() => {
  invoiceGets = 0
  paymentGets = []
  posts = []
  sessionStorage.clear()
  assign = vi.spyOn(browserNavigation, 'assign').mockImplementation(() => undefined)
})
afterEach(() => {
  cleanup()
  server.resetHandlers()
  client?.clear()
  client = undefined
  vi.restoreAllMocks()
})
afterAll(() => server.close())

describe('resolveRedirect (frontend.md §4 redirectTo, F19, NFR-SEC)', () => {
  const origin = 'https://portal.example'
  it.each([
    ['/mock-gateway/checkout/PAY-1', 'https://portal.example/mock-gateway/checkout/PAY-1'],
    ['https://portal.example/x', 'https://portal.example/x'],
  ])('same-origin %s → %s', (url, expected) => {
    expect(resolveRedirect(url, origin, [])).toBe(expected)
  })
  it('an allowlisted gateway origin is accepted', () => {
    expect(resolveRedirect('https://pay.gateway.example/c/1', origin, ['https://pay.gateway.example'])).toBe(
      'https://pay.gateway.example/c/1',
    )
  })
  it.each([
    'https://evil.example/x',
    '//evil.example/x',
    'javascript:alert(1)',
    'data:text/html,hi',
    'http://portal.example/x', // another scheme is another origin
    'http://[bad',
  ])('%s is refused', (url) => {
    expect(resolveRedirect(url, origin, [])).toBeNull()
  })
})

describe('Pay bill (frontend.md §3 Pay bill; CUS-08.1/08.2/08.4, NFR-REL)', () => {
  it('one open invoice: details with the server outstanding, payment methods info, Pay button; axe clean', async () => {
    serve()
    const { container } = renderPage()
    expect(screen.getByText('Loading your bills')).toBeInTheDocument()
    await payButton()
    const bill = screen.getByRole('region', { name: `Bill ${invoiceOpenOlder.invoiceRef}` })
    expect(within(bill).getByText('Outstanding').nextElementSibling).toHaveTextContent('₹589.82')
    expect(within(bill).getByText('Due').nextElementSibling).toHaveTextContent('15 Nov 2026')
    expect(within(bill).getByText('Bill period').nextElementSibling).toHaveTextContent('1 Oct 2026 – 31 Oct 2026')
    expect(screen.getByText(PAYMENT_METHODS_INFO)).toBeInTheDocument()
    expect(screen.queryByRole('radiogroup')).not.toBeInTheDocument()
    expect(document.title).toBe('Pay bill · Telecom Support')
    expect(await axe(container)).toHaveNoViolations()
  })

  it('several invoices: oldest due first and preselected; choosing another pays its outstanding string verbatim', async () => {
    serve({ invoices: [invoiceOpenNewer, invoiceOpenOlder] })
    const user = userEvent.setup()
    renderPage()
    await payButton()
    const radios = within(screen.getByRole('radiogroup', { name: 'Which bill?' })).getAllByRole('radio')
    expect(radios.map((r) => r.getAttribute('aria-checked'))).toEqual(['true', 'false'])
    expect(radios[0]).toHaveAccessibleName(/INV-202610-000001/)
    await user.click(radios[1]!)
    await user.click(await payButton('₹1,000.00'))
    await waitFor(() => expect(posts).toHaveLength(1))
    expect(posts[0]!.body).toEqual({ invoiceId: 802, expectedAmount: '1000.00' })
    expect(posts[0]!.key).toMatch(UUID_V4)
  })

  it('201: stores the reference with the key in sessionStorage and redirects to the same-origin checkout', async () => {
    serve()
    const user = userEvent.setup()
    renderPage()
    await user.click(await payButton())
    await waitFor(() => expect(assign).toHaveBeenCalledTimes(1))
    expect(assign).toHaveBeenCalledWith(`${window.location.origin}/mock-gateway/checkout/${paymentPending.reference}`)
    expect(posts[0]!.body).toEqual({ invoiceId: 801, expectedAmount: '589.82' })
    expect(stored(invoiceOpenOlder)).toMatchObject({ key: posts[0]!.key, reference: paymentPending.reference })
    expect(Object.keys(stored(invoiceOpenOlder)!).sort()).toEqual(['bodyHash', 'key', 'reference', 'savedAt']) // no PII
    expect(screen.getByRole('button', { name: 'Opening the payment page…' })).toBeDisabled()
  })

  it('a double click starts one payment', async () => {
    serve({ replies: [async () => (await delay(50), HttpResponse.json(paymentInitiated, { status: 201 }))] })
    const user = userEvent.setup()
    renderPage()
    const button = await payButton()
    await user.dblClick(button)
    await waitFor(() => expect(assign).toHaveBeenCalledTimes(1))
    expect(posts).toHaveLength(1)
  })

  it.each([['https://evil.example/x'], ['javascript:alert(1)']])(
    'a redirect to %s is refused: error state, no navigation',
    async (redirectUrl) => {
      serve({ initiated: { ...paymentInitiated, redirectUrl } })
      const user = userEvent.setup()
      renderPage()
      await user.click(await payButton())
      expect(await screen.findByRole('heading', { name: REDIRECT_REFUSED_TITLE })).toBeInTheDocument()
      expect(assign).not.toHaveBeenCalled()
    },
  )

  it('abandoned checkout: a stored PENDING payment → "Continue payment" re-sends the same key and redirects', async () => {
    store(invoiceOpenOlder, { reference: paymentPending.reference })
    serve()
    const user = userEvent.setup()
    renderPage()
    expect(await screen.findByText(`You started a payment for this bill (${paymentPending.reference}).`)).toBeInTheDocument()
    expect(paymentGets).toEqual([paymentPending.reference])
    await user.click(screen.getByRole('button', { name: 'Continue payment' }))
    await waitFor(() => expect(assign).toHaveBeenCalledTimes(1))
    expect(posts).toEqual([{ key: STORED_KEY, body: { invoiceId: 801, expectedAmount: '589.82' } }])
  })

  it.each([
    ['FAILED (terminal)', paymentFailed],
    ['REVIEW (cannot be continued)', paymentReview],
  ])('a stored %s payment drops the record: no Continue, Pay uses a fresh key', async (_label, payment) => {
    store(invoiceOpenOlder, { reference: payment.reference })
    serve()
    const user = userEvent.setup()
    renderPage()
    await waitFor(() => expect(stored(invoiceOpenOlder)).toBeNull())
    expect(screen.queryByRole('button', { name: 'Continue payment' })).not.toBeInTheDocument()
    await user.click(await payButton())
    await waitFor(() => expect(posts).toHaveLength(1))
    expect(posts[0]!.key).not.toBe(STORED_KEY)
    expect(posts[0]!.key).toMatch(UUID_V4)
  })

  it('409 with a stored payment: "still open" links to that payment; the key is kept', async () => {
    store(invoiceOpenOlder, { reference: paymentPending.reference })
    serve({ replies: [() => problemResponse(problem(409, 'payment-in-progress', 'A payment is in progress.'))] })
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('button', { name: 'Continue payment' })
    await user.click(await payButton())
    const alert = await screen.findByText(/A payment for this bill is still open/)
    expect(within(alert).getByRole('link', { name: paymentPending.reference })).toHaveAttribute(
      'href',
      `/customer/payments/${paymentPending.reference}`,
    )
    expect(alert).toHaveTextContent('or try again in about 15 minutes.')
    expect(stored(invoiceOpenOlder)?.key).toBe(STORED_KEY)
    expect(assign).not.toHaveBeenCalled()
  })

  it('409 without a stored payment (e.g. one under review, E1): "still open" links to Payment history', async () => {
    serve({ replies: [() => problemResponse(problem(409, 'payment-in-progress', 'A payment is in progress.'))] })
    const user = userEvent.setup()
    renderPage()
    await user.click(await payButton())
    const alert = await screen.findByText(/A payment for this bill is still open/)
    expect(within(alert).getByRole('link', { name: 'Payment history' })).toHaveAttribute('href', '/customer/payments')
    await waitFor(() => expect(screen.getByRole('alert')).toHaveFocus())
  })

  it('422: "The amount due has changed" + invoices refetched; the next Pay uses a fresh key with the new amount', async () => {
    let current: Invoice = invoiceOpenOlder
    serve({
      invoices: () => [current],
      replies: [
        () => {
          current = { ...invoiceOpenOlder, amountPaid: '89.82', outstanding: '500.00' }
          return problemResponse(problem(422, 'amount-changed', 'The amount due changed.'))
        },
      ],
    })
    const user = userEvent.setup()
    renderPage()
    await user.click(await payButton())
    expect(await screen.findByText(AMOUNT_CHANGED_MESSAGE)).toBeInTheDocument()
    await user.click(await payButton('₹500.00'))
    await waitFor(() => expect(posts).toHaveLength(2))
    expect(invoiceGets).toBe(2)
    expect(posts[1]!.body).toEqual({ invoiceId: 801, expectedAmount: '500.00' })
    expect(posts[1]!.key).not.toBe(posts[0]!.key)
  })

  it('503: provider message + "Try again" re-sends the same key', async () => {
    serve({ replies: [() => problemResponse(problem(503, 'gateway-unavailable', 'Gateway unavailable.'))] })
    const user = userEvent.setup()
    renderPage()
    await user.click(await payButton())
    expect(await screen.findByText(PROVIDER_UNAVAILABLE_MESSAGE)).toBeInTheDocument()
    expect(screen.getByText('corr-503-gateway-unavailable')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(assign).toHaveBeenCalledTimes(1))
    expect(posts).toHaveLength(2)
    expect(posts[1]!.key).toBe(posts[0]!.key)
  })

  it('404: "This bill is no longer available" + invoices refetched', async () => {
    let invoices: Invoice[] = [invoiceOpenOlder, invoiceOpenNewer]
    serve({
      invoices: () => invoices,
      replies: [
        () => {
          invoices = [invoiceOpenNewer]
          return problemResponse(problem(404, 'not-found', 'Invoice not found.'))
        },
      ],
    })
    const user = userEvent.setup()
    renderPage()
    await user.click(await payButton())
    expect(await screen.findByText(BILL_GONE_MESSAGE)).toBeInTheDocument()
    expect(await payButton('₹1,000.00')).toBeInTheDocument()
    expect(invoiceGets).toBe(2)
  })

  it('bfcache: a persisted pageshow after the redirect resets the busy state and refetches invoices and the stored payment', async () => {
    serve()
    const user = userEvent.setup()
    renderPage()
    await user.click(await payButton())
    await waitFor(() => expect(assign).toHaveBeenCalledTimes(1))
    expect(screen.getByRole('button', { name: 'Opening the payment page…' })).toBeDisabled()
    await waitFor(() => expect(paymentGets).toEqual([paymentPending.reference]))
    pageShow(false)
    expect(screen.getByRole('button', { name: 'Opening the payment page…' })).toBeDisabled()
    pageShow(true)
    expect(await payButton()).toBeEnabled()
    await waitFor(() => expect(invoiceGets).toBe(2))
    await waitFor(() => expect(paymentGets).toEqual([paymentPending.reference, paymentPending.reference]))
    expect(screen.getByRole('button', { name: 'Continue payment' })).toBeEnabled()
  })

  it('no open invoice → "You have nothing to pay right now" with a Payment history link', async () => {
    serve({ invoices: [] })
    renderPage()
    expect(await screen.findByRole('heading', { level: 2, name: 'You have nothing to pay right now' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Payment history' })).toHaveAttribute('href', '/customer/payments')
  })

  it('invoices 500 → ErrorState with retry', async () => {
    let fail = true
    serve({ invoices: () => (fail ? problemResponse(problem(500, 'internal', 'Unexpected error.')) : [invoiceOpenOlder]) })
    const user = userEvent.setup()
    renderPage()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent("We couldn't load your bills")
    fail = false
    await user.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await payButton()).toBeInTheDocument()
  })

  it('invoices 403 → forbidden page', async () => {
    serve({ invoices: () => problemResponse(problem(403, 'forbidden', 'Access denied.')) })
    renderPage()
    expect(await screen.findByRole('heading', { level: 1, name: 'Access denied' })).toBeInTheDocument()
  })
})
