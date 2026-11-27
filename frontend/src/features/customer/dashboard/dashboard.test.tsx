import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { axe } from 'vitest-axe'
import { createQueryClient, ME_QUERY_KEY } from '@/app/queryClient'
import {
  casePage,
  caseSummary,
  emptyCasePage,
  emptyPaymentPage,
  invoiceOpenNewer,
  invoiceOpenOlder,
  paymentPage,
  paymentSucceeded,
  problem,
  profile,
  promotionClaimable,
  promotionFullyClaimed,
  subscriptionPrimary,
  subscriptionSecondary,
} from '@/test/fixtures/customer'
import { meCustomer } from '@/test/fixtures/me'
import { problemResponse } from '@/test/msw/customerHandlers'
import { clearXsrfCookie } from '@/test/msw/handlers'
import { server } from '@/test/msw/server'
import type { Invoice, Me, Permission } from '@/types/api'
import { CARD_FORBIDDEN_TITLE } from './DashboardCards'
import { DashboardPage } from './DashboardPage'

const ME = '*/api/v1/customers/me'

type Reply = () => Response
type Endpoint = 'profile' | 'subscriptions' | 'promotions' | 'cases' | 'invoices' | 'payments'

let client: QueryClient
let calls: Record<Endpoint, URL[]>

/** E1, E5, E8, E11, E13, E14 with per-test replies; every call is recorded with its URL. */
function serve(replies: Partial<Record<Endpoint, Reply>> = {}) {
  const defaults: Record<Endpoint, Reply> = {
    profile: () => HttpResponse.json(profile),
    subscriptions: () => HttpResponse.json([subscriptionSecondary, subscriptionPrimary]),
    promotions: () => HttpResponse.json([promotionFullyClaimed, promotionClaimable]),
    cases: () => HttpResponse.json(casePage([caseSummary], 0, 3)),
    invoices: () => HttpResponse.json([invoiceOpenOlder, invoiceOpenNewer]),
    payments: () => HttpResponse.json(paymentPage([paymentSucceeded], 0, 1, 7)),
  }
  const handler = (endpoint: Endpoint) => ({ request }: { request: Request }) => {
    calls[endpoint].push(new URL(request.url))
    return (replies[endpoint] ?? defaults[endpoint])()
  }
  server.use(
    http.get(`${ME}/profile`, handler('profile')),
    http.get(`${ME}/subscriptions`, handler('subscriptions')),
    http.get(`${ME}/promotions`, handler('promotions')),
    http.get(`${ME}/cases`, handler('cases')),
    http.get(`${ME}/invoices`, handler('invoices')),
    http.get(`${ME}/payments`, handler('payments')),
  )
}

function renderDashboard(me: Me = meCustomer) {
  client = createQueryClient()
  client.setDefaultOptions({ queries: { retry: false } })
  client.setQueryData(ME_QUERY_KEY, me)
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/customer']}>
        <DashboardPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const card = (name: string) => screen.getByRole('region', { name })
const findCard = (name: string) => screen.findByRole('region', { name })

/** Waits until every card has left its loading state. */
async function settled() {
  await waitFor(() => expect(document.querySelectorAll('[aria-busy="true"]')).toHaveLength(0))
}

const without = (...dropped: Permission[]): Me => ({
  ...meCustomer,
  permissions: meCustomer.permissions.filter((p) => !dropped.includes(p)),
})

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
beforeEach(() => {
  calls = { profile: [], subscriptions: [], promotions: [], cases: [], invoices: [], payments: [] }
})
afterEach(() => {
  cleanup()
  server.resetHandlers()
  clearXsrfCookie()
  client.clear()
})
afterAll(() => server.close())

describe('Customer dashboard (frontend.md §3 Dashboard; CUS-01)', () => {
  it('each card shows its skeleton, then its own data; axe clean', async () => {
    serve()
    const { container } = renderDashboard()

    expect(screen.getByRole('heading', { level: 1, name: 'Home' })).toBeInTheDocument()
    for (const label of ['Loading your account', 'Loading offers', 'Loading your plan', 'Loading recent requests', 'Loading your bills']) {
      expect(screen.getByText(label)).toBeInTheDocument()
    }
    await settled()

    const identity = card('My account')
    expect(within(identity).getByText(profile.fullName)).toBeInTheDocument()
    expect(within(identity).getByText(profile.primaryMsisdn)).toBeInTheDocument()
    expect(within(identity).getByText(profile.accountRef)).toBeInTheDocument()
    expect(within(identity).getByText(profile.customerRef)).toBeInTheDocument()

    // Banner: the first offer with a claimable number (the fully claimed one comes first and is skipped).
    const banner = card('Offer for you')
    expect(within(banner).getByText(promotionClaimable.title)).toBeInTheDocument()
    expect(within(banner).queryByText(promotionFullyClaimed.title)).not.toBeInTheDocument()

    // Plan: the primary subscription even though E5 listed the secondary first.
    const plan = card('My current plan')
    expect(within(plan).getByText(subscriptionPrimary.plan.name)).toBeInTheDocument()
    expect(within(plan).getByText(subscriptionPrimary.msisdn)).toBeInTheDocument()
    expect(within(plan).getByText('₹499.00 / month')).toBeInTheDocument()
    expect(within(plan).getByText('Active')).toBeInTheDocument()

    const requests = card('Recent requests')
    expect(within(requests).getByText(caseSummary.subject)).toBeInTheDocument()
    expect(within(requests).getByText(new RegExp(caseSummary.reference))).toBeInTheDocument()

    expect(await axe(container)).toHaveNoViolations()
  })

  it('asks E11 for 3 requests and E14 for 1 payment in the default window, E13 for open invoices only', async () => {
    serve()
    renderDashboard()
    await settled()

    expect(calls.cases).toHaveLength(1)
    expect(Object.fromEntries(calls.cases[0]!.searchParams)).toEqual({ page: '0', size: '3' })
    expect(calls.payments).toHaveLength(1)
    expect(Object.fromEntries(calls.payments[0]!.searchParams)).toEqual({ page: '0', size: '1' })
    expect(calls.invoices[0]!.searchParams.get('status')).toBe('OPEN')
  })

  it("bills show each open invoice's server outstanding (no total, F8), at most 3, and the last payment", async () => {
    const fourth: Invoice = { ...invoiceOpenNewer, id: 899, invoiceRef: 'INV-202612-000009' }
    const third: Invoice = { ...invoiceOpenNewer, id: 898, invoiceRef: 'INV-202612-000008', outstanding: '10.05' }
    serve({ invoices: () => HttpResponse.json([invoiceOpenOlder, invoiceOpenNewer, third, fourth]) })
    renderDashboard()
    await settled()

    const bills = card('Bills')
    const rows = within(bills).getAllByRole('listitem')
    expect(rows).toHaveLength(3)
    expect(rows[0]).toHaveTextContent(invoiceOpenOlder.invoiceRef)
    expect(rows[0]).toHaveTextContent('Due 15 Nov 2026')
    expect(rows[0]).toHaveTextContent('Outstanding ₹589.82')
    expect(rows[1]).toHaveTextContent('Outstanding ₹1,000.00')
    expect(rows[2]).toHaveTextContent('Outstanding ₹10.05')
    expect(within(bills).queryByText(fourth.invoiceRef)).not.toBeInTheDocument()
    // No client-side total anywhere (589.82 + 1000.00 + 10.05).
    expect(bills).not.toHaveTextContent('1,599.87')
    expect(bills).not.toHaveTextContent(/total/i)

    expect(within(bills).getByRole('heading', { level: 3, name: 'Last payment' })).toBeInTheDocument()
    expect(within(bills).getByRole('link', { name: paymentSucceeded.reference })).toHaveAttribute(
      'href',
      `/customer/payments/${paymentSucceeded.reference}`,
    )
    expect(within(bills).getByText('Paid')).toBeInTheDocument()
  })

  it('links: plan → My plan, View all → requests, Pay now → pay bill, banner → the offer, quick links per permission', async () => {
    serve()
    renderDashboard()
    await settled()

    expect(within(card('My current plan')).getByRole('link', { name: 'View my plan' })).toHaveAttribute('href', '/customer/plan')
    expect(within(card('Recent requests')).getByRole('link', { name: 'View all requests' })).toHaveAttribute(
      'href',
      '/customer/requests',
    )
    expect(within(card('Bills')).getByRole('link', { name: 'Pay now' })).toHaveAttribute('href', '/customer/payments/pay')
    expect(within(card('Bills')).getByRole('link', { name: 'Payment history' })).toHaveAttribute('href', '/customer/payments')
    expect(within(card('Offer for you')).getByRole('link', { name: `View offer: ${promotionClaimable.title}` })).toHaveAttribute(
      'href',
      `/customer/promotions#${promotionClaimable.code}`,
    )

    const quick = card('Quick links')
    expect(within(quick).getAllByRole('link').map((a) => [a.textContent, a.getAttribute('href')])).toEqual([
      ['Create request', '/customer/requests/new'],
      ['Pay now', '/customer/payments/pay'],
      ['Payment history', '/customer/payments'],
      ['Promotions', '/customer/promotions'],
    ])
  })

  it('E8 500 → only the banner shows ErrorState (with correlation id and retry); the other cards render', async () => {
    serve({ promotions: () => problemResponse(problem(500, 'internal', 'Something went wrong.')) })
    renderDashboard()
    await settled()

    const banner = card('Offer for you')
    expect(within(banner).getByRole('alert')).toHaveTextContent("We couldn't load your offers")
    expect(within(banner).getByText('corr-500-internal')).toBeInTheDocument()
    expect(within(banner).getByRole('button', { name: 'Try again' })).toBeInTheDocument()

    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(within(card('My account')).getByText(profile.fullName)).toBeInTheDocument()
    expect(within(card('My current plan')).getByText(subscriptionPrimary.plan.name)).toBeInTheDocument()
    expect(within(card('Recent requests')).getByText(caseSummary.subject)).toBeInTheDocument()
    expect(within(card('Bills')).getByText(invoiceOpenOlder.invoiceRef)).toBeInTheDocument()
  })

  it('E5 403 → only the plan card says the information isn\'t available; the page stays (no ForbiddenPage); axe clean', async () => {
    serve({ subscriptions: () => problemResponse(problem(403, 'forbidden', 'Access denied.')) })
    const { container } = renderDashboard()
    await settled()

    const plan = card('My current plan')
    expect(within(plan).getByRole('alert')).toHaveTextContent(CARD_FORBIDDEN_TITLE)
    expect(within(plan).queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()

    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(screen.getByRole('heading', { level: 1, name: 'Home' })).toBeInTheDocument()
    expect(within(card('My account')).getByText(profile.fullName)).toBeInTheDocument()
    expect(within(card('Offer for you')).getByText(promotionClaimable.title)).toBeInTheDocument()
    expect(within(card('Bills')).getByText(invoiceOpenOlder.invoiceRef)).toBeInTheDocument()
    expect(await axe(container)).toHaveNoViolations()
  })

  it('a failing last payment (E14 500) shows inside the bills card while its invoices still render', async () => {
    serve({ payments: () => problemResponse(problem(500, 'internal', 'Something went wrong.')) })
    renderDashboard()
    await settled()

    const bills = card('Bills')
    expect(within(bills).getByRole('alert')).toHaveTextContent("We couldn't load your last payment")
    expect(within(bills).getByText(invoiceOpenOlder.invoiceRef)).toBeInTheDocument()
  })

  it('empty states: no plan, no requests, no open bills (no Pay now), no last payment; banner hidden without a claimable offer; axe clean', async () => {
    serve({
      subscriptions: () => HttpResponse.json([]),
      promotions: () => HttpResponse.json([promotionFullyClaimed]),
      cases: () => HttpResponse.json(emptyCasePage),
      invoices: () => HttpResponse.json([]),
      payments: () => HttpResponse.json(emptyPaymentPage),
    })
    const { container } = renderDashboard()
    await settled()

    expect(within(card('My current plan')).getByRole('heading', { name: 'No plan on your account' })).toBeInTheDocument()
    expect(within(card('Recent requests')).getByRole('heading', { name: 'No requests in the last 3 months' })).toBeInTheDocument()
    const bills = card('Bills')
    expect(within(bills).getByRole('heading', { name: 'No open bills' })).toBeInTheDocument()
    expect(within(bills).getByText('No payments in the last 3 months.')).toBeInTheDocument()
    expect(within(bills).queryByRole('link', { name: 'Pay now' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Offer for you' })).not.toBeInTheDocument()
    expect(await axe(container)).toHaveNoViolations()
  })

  it('Pay now needs an invoice with a positive outstanding (string check) and payment.initiate', async () => {
    const zero: Invoice = { ...invoiceOpenOlder, outstanding: '0.00' }
    serve({ invoices: () => HttpResponse.json([zero]) })
    renderDashboard()
    await settled()
    expect(within(card('Bills')).getByText('Outstanding')).toBeInTheDocument()
    expect(within(card('Bills')).queryByRole('link', { name: 'Pay now' })).not.toBeInTheDocument()
    cleanup()
    client.clear()

    serve()
    renderDashboard(without('payment.initiate'))
    await settled()
    expect(within(await findCard('Bills')).getByText(invoiceOpenOlder.invoiceRef)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Pay now' })).not.toBeInTheDocument()
  })

  it("a card without its read permission is not rendered and fires no request; the others still do", async () => {
    serve()
    renderDashboard(without('plan.read', 'case.read', 'promotion.read'))
    await settled()

    expect(within(await findCard('Bills')).getByText(invoiceOpenOlder.invoiceRef)).toBeInTheDocument()
    expect(card('My account')).toBeInTheDocument()
    for (const name of ['My current plan', 'Recent requests', 'Offer for you']) {
      expect(screen.queryByRole('region', { name })).not.toBeInTheDocument()
    }
    expect(calls.subscriptions).toHaveLength(0)
    expect(calls.cases).toHaveLength(0)
    expect(calls.promotions).toHaveLength(0)
    expect(within(card('Quick links')).getAllByRole('link').map((a) => a.textContent)).toEqual(['Pay now', 'Payment history'])
  })
})
