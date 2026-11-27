import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { toast, Toaster } from 'sonner'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { axe } from 'vitest-axe'
import { createQueryClient, FORBIDDEN_ACTION_MESSAGE, ME_QUERY_KEY } from '@/app/queryClient'
import {
  planChangeScheduled,
  planOptions,
  problem,
  subscriptionPrimary,
  subscriptionSecondary,
  subscriptionSuspended,
  subscriptionWithPendingChange,
} from '@/test/fixtures/customer'
import { meCustomer } from '@/test/fixtures/me'
import { problemResponse } from '@/test/msw/customerHandlers'
import { clearXsrfCookie } from '@/test/msw/handlers'
import { server } from '@/test/msw/server'
import type { Me, PlanChangeRequest, PlanOption, Subscription } from '@/types/api'
import { PlanPage } from './PlanPage'
import { STILL_PROCESSING_MESSAGE } from './PlanChangeDialog'
import { NO_UPGRADES_MESSAGE } from './UpgradeOptions'

const SUBSCRIPTIONS_URL = '*/api/v1/customers/me/subscriptions'
const OPTIONS_URL = '*/api/v1/customers/me/subscriptions/:subscriptionId/plan-options'
const CHANGE_URL = '*/api/v1/customers/me/subscriptions/:subscriptionId/plan-changes'
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

type Post = { subscriptionId: string; key: string | null; body: PlanChangeRequest }

let client: QueryClient
let optionGets: number
let subscriptionGets: number
let posts: Post[]

/** E5–E7 with server-side state: a 201 schedules the change on that subscription (E5 then shows it). */
function serve({
  subscriptions = [subscriptionPrimary],
  options = planOptions,
  replies = [],
}: {
  subscriptions?: Subscription[]
  options?: PlanOption[]
  /** One reply per POST, in order; after them, 201. */
  replies?: (() => Response | Promise<Response>)[]
} = {}) {
  let current = subscriptions
  server.use(
    http.get(SUBSCRIPTIONS_URL, () => {
      subscriptionGets += 1
      return HttpResponse.json(current)
    }),
    http.get(OPTIONS_URL, () => {
      optionGets += 1
      return HttpResponse.json(options)
    }),
    http.post(CHANGE_URL, async ({ request, params }) => {
      posts.push({
        subscriptionId: String(params.subscriptionId),
        key: request.headers.get('Idempotency-Key'),
        body: (await request.json()) as PlanChangeRequest,
      })
      const reply = replies.shift()
      if (reply) return reply()
      current = current.map((s) =>
        String(s.id) === params.subscriptionId ? { ...s, pendingPlanChange: planChangeScheduled } : s,
      )
      return HttpResponse.json(planChangeScheduled, { status: 201 })
    }),
  )
}

function renderPlan(me: Me = meCustomer) {
  client = createQueryClient()
  client.setDefaultOptions({ queries: { retry: false } })
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

/** The card of a subscription, found by its number. */
async function card(msisdn = subscriptionPrimary.msisdn) {
  return (await screen.findByText(new RegExp(`^\\${msisdn}`))).closest('section')!
}

async function openOptions(user: ReturnType<typeof userEvent.setup>, msisdn = subscriptionPrimary.msisdn) {
  await user.click(within(await card(msisdn)).getByRole('button', { name: 'See upgrade options' }))
  return screen.findByRole('table', { name: `Upgrade options for ${msisdn}` })
}

async function choose(user: ReturnType<typeof userEvent.setup>, msisdn = subscriptionPrimary.msisdn) {
  const table = await openOptions(user, msisdn)
  await user.click(within(table).getByRole('button', { name: 'Choose Unlimited 799' }))
  return screen.findByRole('alertdialog', { name: 'Change to Unlimited 799?' })
}

const confirmButton = (dialog: HTMLElement) => within(dialog).getByRole('button', { name: 'Confirm change' })

const quoteBody: PlanChangeRequest = {
  targetPlanCode: 'POST799',
  expectedEffectiveDate: '2026-12-01',
  expectedCharge: '0.00',
}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
beforeEach(() => {
  optionGets = 0
  subscriptionGets = 0
  posts = []
  toast.dismiss()
})
afterEach(() => {
  cleanup()
  server.resetHandlers()
  clearXsrfCookie()
  client.clear()
})
afterAll(() => server.close())

describe('upgrade options (frontend.md §3 My plan; CUS-03.4)', () => {
  it('fetches options only on demand and compares current vs each option; axe clean', async () => {
    serve()
    const user = userEvent.setup()
    const { container } = renderPlan()
    await card()
    expect(optionGets).toBe(0)

    const table = await openOptions(user)
    expect(optionGets).toBe(1)
    const rows = within(table).getAllByRole('row')
    expect(rows).toHaveLength(3) // header, current, one option
    expect(rows[1]).toHaveTextContent('Unlimited 499 (current)')
    expect(rows[1]).toHaveTextContent('₹499.00 / month')
    expect(rows[2]).toHaveTextContent('Unlimited 799')
    expect(rows[2]).toHaveTextContent('₹799.00 / month')
    expect(rows[2]).toHaveTextContent('+₹300.00')
    expect(rows[2]).toHaveTextContent('150 GB')
    expect(rows[2]).toHaveTextContent('1 Dec 2026')
    expect(rows[2]).toHaveTextContent('₹0.00')
    expect(await axe(container)).toHaveNoViolations()
  })

  it('no options → "No upgrades are available for this plan"', async () => {
    serve({ options: [] })
    const user = userEvent.setup()
    renderPlan()
    await user.click(within(await card()).getByRole('button', { name: 'See upgrade options' }))
    expect(await screen.findByText(NO_UPGRADES_MESSAGE)).toBeInTheDocument()
  })

  it.each<[string, Subscription, Me]>([
    ['a pending change', subscriptionWithPendingChange, meCustomer],
    ['a SUSPENDED subscription', { ...subscriptionSuspended, msisdn: subscriptionPrimary.msisdn }, meCustomer],
    ['no plan.change', subscriptionPrimary, { ...meCustomer, permissions: meCustomer.permissions.filter((p) => p !== 'plan.change') }],
  ])('upgrade controls are hidden with %s', async (_case, subscription, me) => {
    serve({ subscriptions: [subscription] })
    renderPlan(me)
    const section = await card()
    expect(within(section).queryByRole('button', { name: 'See upgrade options' })).not.toBeInTheDocument()
  })
})

describe('plan change (frontend.md §3 My plan, §6; CUS-03.5, XC-06, XC-07, NFR-REL)', () => {
  it('the confirm dialog shows effective date, charge (even ₹0.00) and new rental; Cancel sends nothing', async () => {
    serve()
    const user = userEvent.setup()
    renderPlan()
    const dialog = await choose(user)
    expect(dialog).toHaveTextContent('Effective date1 Dec 2026')
    expect(dialog).toHaveTextContent('Charge₹0.00')
    expect(dialog).toHaveTextContent('New rental₹799.00 / month')

    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    expect(posts).toHaveLength(0)
  })

  it('Confirm echoes the quote with an idempotency key; success only after the 201: toast, card shows the pending change', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    serve({ replies: [] })
    server.use(
      http.post(CHANGE_URL, async ({ request, params }) => {
        posts.push({
          subscriptionId: String(params.subscriptionId),
          key: request.headers.get('Idempotency-Key'),
          body: (await request.json()) as PlanChangeRequest,
        })
        await gate
        server.use(
          http.get(SUBSCRIPTIONS_URL, () => HttpResponse.json([subscriptionWithPendingChange])),
        )
        return HttpResponse.json(planChangeScheduled, { status: 201 })
      }),
    )
    const user = userEvent.setup()
    renderPlan()
    const dialog = await choose(user)
    await user.click(confirmButton(dialog))

    await waitFor(() => expect(posts).toHaveLength(1))
    expect(posts[0]!.subscriptionId).toBe(String(subscriptionPrimary.id))
    expect(posts[0]!.body).toEqual(quoteBody)
    expect(posts[0]!.key).toMatch(UUID_V4)
    // nothing changes before the server confirms (XC-07)
    expect(within(dialog).getByRole('button', { name: 'Confirming…' })).toBeDisabled()
    expect(screen.queryByText(/Changing to/)).not.toBeInTheDocument()

    release()
    expect(await screen.findByText('Plan change scheduled for 1 Dec 2026')).toBeInTheDocument()
    expect(await screen.findByText('Changing to POST799 on 1 Dec 2026')).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'See upgrade options' })).not.toBeInTheDocument()
  })

  it('a double click sends one request', async () => {
    serve({ replies: [async () => { await delay(50); return HttpResponse.json(planChangeScheduled, { status: 201 }) }] })
    const user = userEvent.setup()
    renderPlan()
    const dialog = await choose(user)
    const button = confirmButton(dialog)
    await user.dblClick(button)
    expect(await screen.findByText('Plan change scheduled for 1 Dec 2026')).toBeInTheDocument()
    expect(posts).toHaveLength(1)
  })

  it('409 → "still processing" in the dialog; the retry reuses the same key', async () => {
    serve({ replies: [() => problemResponse(problem(409, 'idempotency-in-progress', 'Still processing.'))] })
    const user = userEvent.setup()
    renderPlan()
    const dialog = await choose(user)
    await user.click(confirmButton(dialog))
    expect(await within(dialog).findByText(STILL_PROCESSING_MESSAGE)).toBeInTheDocument()
    expect(within(dialog).getByRole('alert')).toHaveFocus()

    await user.click(confirmButton(dialog))
    expect(await screen.findByText('Plan change scheduled for 1 Dec 2026')).toBeInTheDocument()
    expect(posts).toHaveLength(2)
    expect(posts[1]!.key).toBe(posts[0]!.key)
  })

  it('a business 422 (stale quote, suspended account) → dialog closes, detail + "Options were refreshed.", options refetched, next try uses a new key', async () => {
    serve({
      replies: [() => problemResponse(problem(422, 'plan-change-rejected', 'Your account is suspended.'))],
    })
    const user = userEvent.setup()
    renderPlan()
    const dialog = await choose(user)
    await user.click(confirmButton(dialog))

    const alert = await screen.findByText('Your account is suspended. Options were refreshed.')
    expect(alert.closest('[role="alert"]')).toHaveFocus()
    expect(alert.closest('[role="alert"]')).toHaveTextContent('corr-422-plan-change-rejected')
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    await waitFor(() => expect(optionGets).toBe(2))
    await waitFor(() => expect(subscriptionGets).toBe(2)) // E5 too: the reason may be a scheduled change or SUSPENDED (FC7)

    const table = screen.getByRole('table', { name: /Upgrade options/ })
    await user.click(within(table).getByRole('button', { name: 'Choose Unlimited 799' }))
    await user.click(confirmButton(await screen.findByRole('alertdialog')))
    expect(await screen.findByText('Plan change scheduled for 1 Dec 2026')).toBeInTheDocument()
    expect(posts[1]!.key).not.toBe(posts[0]!.key)
  })

  it('an idempotency-mismatch 422 → "Please try again." (no business copy) and a fresh key', async () => {
    serve({
      replies: [() => problemResponse(problem(422, 'idempotency-mismatch', 'Key reused with another body.'))],
    })
    const user = userEvent.setup()
    renderPlan()
    const dialog = await choose(user)
    await user.click(confirmButton(dialog))
    expect(await within(dialog).findByText('Please try again.')).toBeInTheDocument()
    expect(within(dialog).queryByText('Key reused with another body.')).not.toBeInTheDocument()
    expect(optionGets).toBe(1)

    await user.click(confirmButton(dialog))
    expect(await screen.findByText('Plan change scheduled for 1 Dec 2026')).toBeInTheDocument()
    expect(posts[1]!.key).not.toBe(posts[0]!.key)
  })

  it('the same quote on another subscription gets another key (key scoped to the subscription path)', async () => {
    serve({ subscriptions: [subscriptionPrimary, subscriptionSecondary] })
    const user = userEvent.setup()
    renderPlan()
    await user.click(confirmButton(await choose(user, subscriptionPrimary.msisdn)))
    expect(await screen.findByText('Plan change scheduled for 1 Dec 2026')).toBeInTheDocument()
    await user.click(confirmButton(await choose(user, subscriptionSecondary.msisdn)))
    await waitFor(() => expect(posts).toHaveLength(2))
    expect(posts.map((p) => p.subscriptionId)).toEqual([String(subscriptionPrimary.id), String(subscriptionSecondary.id)])
    expect(posts[1]!.body).toEqual(posts[0]!.body)
    expect(posts[1]!.key).not.toBe(posts[0]!.key)
  })

  it('a 500 → generic root error with the correlation id; the retry keeps the key', async () => {
    serve({ replies: [() => problemResponse(problem(500, 'internal', 'Unexpected error.'))] })
    const user = userEvent.setup()
    renderPlan()
    const dialog = await choose(user)
    await user.click(confirmButton(dialog))
    const alert = await within(dialog).findByRole('alert')
    expect(alert).toHaveTextContent('Something went wrong. Please try again.')
    expect(alert).toHaveTextContent('corr-500-internal')
    await user.click(confirmButton(dialog))
    await waitFor(() => expect(posts).toHaveLength(2))
    expect(posts[1]!.key).toBe(posts[0]!.key)
  })

  it('a 403 → forbidden toast only; no error in the dialog and nothing changes', async () => {
    serve({ replies: [() => problemResponse(problem(403, 'forbidden', 'Access denied.'))] })
    const user = userEvent.setup()
    renderPlan()
    const dialog = await choose(user)
    await user.click(confirmButton(dialog))
    expect(await screen.findByText(FORBIDDEN_ACTION_MESSAGE)).toBeInTheDocument()
    expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText(/Changing to/)).not.toBeInTheDocument()
  })
})
