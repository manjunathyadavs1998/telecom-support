import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { toast, Toaster } from 'sonner'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { axe } from 'vitest-axe'
import { createQueryClient, ME_QUERY_KEY } from '@/app/queryClient'
import {
  problem,
  subscriptionPrimary,
  subscriptionSecondary,
  subscriptionWithPendingChange,
} from '@/test/fixtures/customer'
import { meCustomer } from '@/test/fixtures/me'
import { problemResponse } from '@/test/msw/customerHandlers'
import { clearXsrfCookie } from '@/test/msw/handlers'
import { server } from '@/test/msw/server'
import type { Me, Subscription } from '@/types/api'
import { PlanPage } from './PlanPage'

const SUBSCRIPTIONS_URL = '*/api/v1/customers/me/subscriptions'

let client: QueryClient

function serveSubscriptions(list: Subscription[]) {
  server.use(http.get(SUBSCRIPTIONS_URL, () => HttpResponse.json(list)))
}

function renderPlan(me: Me = meCustomer, { retry = true }: { retry?: boolean } = {}) {
  client = createQueryClient()
  if (!retry) client.setDefaultOptions({ queries: { retry: false } })
  client.setQueryData(ME_QUERY_KEY, me)
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/customer/plan']}>
        <PlanPage />
      </MemoryRouter>
      <Toaster />
    </QueryClientProvider>,
  )
}

/** The subscription cards (`<section aria-labelledby>` named by the plan heading); Sonner's toaster is a region too. */
const cards = async () =>
  (await screen.findAllByRole('heading', { level: 2 })).map((heading) => heading.closest('section')!)

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
beforeEach(() => toast.dismiss())
afterEach(() => {
  cleanup()
  server.resetHandlers()
  clearXsrfCookie()
  client.clear()
})
afterAll(() => server.close())

describe('My plan page (frontend.md §3 My plan; CUS-03.1–03.3)', () => {
  it('shows a loading state, then the collapsed card: plan, ₹/month, status, billing cycle, key benefits; axe clean', async () => {
    serveSubscriptions([{ ...subscriptionPrimary, billingCycleDay: 5 }])
    const { container } = renderPlan()
    expect(screen.getByText('Loading your plans')).toBeInTheDocument()
    const [card] = await cards()

    expect(within(card!).getByRole('heading', { level: 2, name: 'Unlimited 499' })).toBeInTheDocument()
    expect(within(card!).getByText('₹499.00 / month')).toBeInTheDocument()
    expect(within(card!).getByText('Active')).toBeInTheDocument()
    expect(within(card!).getByText('Day 5 of each month')).toBeInTheDocument()
    expect(within(card!).getByText(/\+919800000001/)).toHaveTextContent('Primary number')
    // key benefits only while collapsed
    expect(within(card!).getAllByText('75 GB')[0]).toBeVisible() // the second copy is in the collapsed details
    expect(within(card!).queryByText('100/day')).not.toBeVisible()
    // no usage data (CUS-03.3)
    expect(within(card!).queryByText(/usage/i)).not.toBeInTheDocument()
    expect(document.title).toBe('My plan · Telecom Support')
    expect(await axe(container)).toHaveNoViolations()
  })

  it('"View details" is a disclosure listing all benefits in non-empty groups only', async () => {
    serveSubscriptions([subscriptionPrimary])
    const user = userEvent.setup()
    renderPlan()
    const [card] = await cards()
    const toggle = within(card!).getByRole('button', { name: 'View details' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    const details = document.getElementById(toggle.getAttribute('aria-controls')!)!
    expect(details).not.toBeVisible()

    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(details).toBeVisible()
    const groups = within(details)
      .getAllByRole('heading', { level: 4 })
      .map((heading) => heading.textContent)
    expect(groups).toEqual(['Voice', 'Data', 'SMS', 'OTT']) // no Roaming / Other in this plan
    expect(within(details).getByText('100/day')).toBeVisible()
    expect(within(details).getByText('Basic pack')).toBeVisible()

    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(details).not.toBeVisible()
  })

  it('lists one card per subscription, primary first', async () => {
    serveSubscriptions([subscriptionSecondary, subscriptionPrimary])
    renderPlan()
    const regions = await cards()
    expect(regions).toHaveLength(2)
    expect(within(regions[0]!).getByText(/\+919800000001/)).toBeInTheDocument()
    expect(within(regions[1]!).getByText(/\+919800001234/)).toBeInTheDocument()
  })

  it('shows a pending plan change as "Changing to {plan} on {date}"', async () => {
    serveSubscriptions([subscriptionWithPendingChange])
    renderPlan()
    const [card] = await cards()
    expect(within(card!).getByText('Changing to POST799 on 1 Dec 2026')).toBeInTheDocument()
  })

  it('no subscriptions → empty state', async () => {
    serveSubscriptions([])
    renderPlan()
    expect(await screen.findByRole('heading', { name: 'No plans on your account' })).toBeInTheDocument()
  })

  it('a 500 shows ErrorState with the correlation id and retry', async () => {
    let fail = true
    server.use(
      http.get(SUBSCRIPTIONS_URL, () =>
        fail ? problemResponse(problem(500, 'internal', 'Unexpected error.')) : HttpResponse.json([subscriptionPrimary]),
      ),
    )
    const user = userEvent.setup()
    renderPlan(meCustomer, { retry: false })
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent("We couldn't load your plans")
    expect(alert).toHaveTextContent('corr-500-internal')
    fail = false
    await user.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('heading', { level: 2, name: 'Unlimited 499' })).toBeInTheDocument()
  })

  it('a 403 on the subscriptions query renders the forbidden page', async () => {
    server.use(http.get(SUBSCRIPTIONS_URL, () => problemResponse(problem(403, 'forbidden', 'Access denied.'))))
    renderPlan()
    expect(await screen.findByRole('heading', { level: 1, name: 'Access denied' })).toBeInTheDocument()
  })
})
