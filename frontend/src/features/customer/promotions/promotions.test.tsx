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
  problem,
  promotionClaim,
  promotionClaimable,
  promotionFullyClaimed,
  promotionPartlyClaimed,
  subscriptionPrimary,
  subscriptionSecondary,
} from '@/test/fixtures/customer'
import { meCustomer } from '@/test/fixtures/me'
import { problemResponse } from '@/test/msw/customerHandlers'
import { clearXsrfCookie } from '@/test/msw/handlers'
import { server } from '@/test/msw/server'
import type { Me, Promotion, PromotionClaimRequest } from '@/types/api'
import { SECTION_FORBIDDEN_TITLE } from '@/components/states/SectionErrorState'
import { CLAIM_STILL_PROCESSING_MESSAGE, NUMBERS_ERROR_TITLE, OFFER_GONE_MESSAGE } from './ClaimDialog'
import { PromotionsPage } from './PromotionsPage'

const PROMOTIONS_URL = '*/api/v1/customers/me/promotions'
const CLAIM_URL = '*/api/v1/customers/me/promotions/:promotionCode/claims'
const SUBSCRIPTIONS_URL = '*/api/v1/customers/me/subscriptions'
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const NEW_TERMS_HASH = 'b'.repeat(64)

type Post = { promotionCode: string; key: string | null; body: PromotionClaimRequest }

let client: QueryClient
let promotionGets: number
let subscriptionGets: number
let posts: Post[]

/** E5, E8, E9 with server state: a 201 moves the number from eligible to claimed (§10 D3). */
function serve({
  promotions = [promotionClaimable],
  replies = [],
}: {
  promotions?: Promotion[]
  /** One reply per POST, in order; after them, 201. */
  replies?: (() => Response | Promise<Response>)[]
} = {}) {
  let current = promotions
  const state = {
    set: (next: Promotion[]) => {
      current = next
    },
  }
  server.use(
    http.get(PROMOTIONS_URL, () => {
      promotionGets += 1
      return HttpResponse.json(current)
    }),
    http.get(SUBSCRIPTIONS_URL, () => {
      subscriptionGets += 1
      return HttpResponse.json([subscriptionPrimary, subscriptionSecondary])
    }),
    http.post(CLAIM_URL, async ({ request, params }) => {
      const body = (await request.json()) as PromotionClaimRequest
      posts.push({ promotionCode: String(params.promotionCode), key: request.headers.get('Idempotency-Key'), body })
      const reply = replies.shift()
      if (reply) return reply()
      current = current.map((p) =>
        p.code === params.promotionCode
          ? {
              ...p,
              claimed: true,
              eligibleSubscriptionIds: p.eligibleSubscriptionIds.filter((id) => id !== body.subscriptionId),
              claimedSubscriptionIds: [...p.claimedSubscriptionIds, body.subscriptionId],
            }
          : p,
      )
      return HttpResponse.json({ ...promotionClaim, subscriptionId: body.subscriptionId }, { status: 201 })
    }),
  )
  return state
}

function renderPromotions(me: Me = meCustomer, path = '/customer/promotions') {
  client = createQueryClient()
  client.setDefaultOptions({ queries: { retry: false } })
  client.setQueryData(ME_QUERY_KEY, me)
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <PromotionsPage />
      </MemoryRouter>
      <Toaster />
    </QueryClientProvider>,
  )
}

/** The offer card, found by its heading. */
async function card(title = promotionClaimable.title) {
  return (await screen.findByRole('heading', { level: 2, name: title })).closest('section')!
}

const claimButton = (title = promotionClaimable.title) =>
  screen.getByRole('button', { name: new RegExp(`^(View & claim|Claimed): ${title}$`) })

async function openClaim(user: ReturnType<typeof userEvent.setup>, title = promotionClaimable.title) {
  await card(title)
  await user.click(claimButton(title))
  const dialog = await screen.findByRole('dialog', { name: title })
  await within(dialog).findByRole('form', { name: 'Claim offer' })
  return dialog
}

const acceptTerms = (dialog: HTMLElement) =>
  within(dialog).getByRole('checkbox', { name: 'I accept the terms and conditions' })
const confirm = (dialog: HTMLElement) => within(dialog).getByRole('button', { name: 'Confirm claim' })

async function claimWithTerms(user: ReturnType<typeof userEvent.setup>, dialog: HTMLElement) {
  await user.click(acceptTerms(dialog))
  await user.click(confirm(dialog))
}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
beforeEach(() => {
  promotionGets = 0
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

describe('Promotions page (frontend.md §3 Promotions; CUS-04.1/04.2)', () => {
  it('shows loading, then the card: title, description, valid until (IST, exclusive end), eligibility, terms disclosure; axe clean', async () => {
    serve()
    const user = userEvent.setup()
    const { container } = renderPromotions()
    expect(screen.getByText('Loading offers')).toBeInTheDocument()
    const section = await card()
    expect(within(section).getByText(promotionClaimable.shortDescription)).toBeInTheDocument()
    // validTo 2026-12-31T18:30:00Z is 1 Jan 2027 00:00 IST: shown as that instant, not as "31 Dec" (F23)
    expect(within(section).getByText('1 Jan 2027, 12:00 am')).toBeInTheDocument()
    expect(within(section).getByText(promotionClaimable.eligibility)).toBeInTheDocument()

    const terms = within(section).getByRole('button', { name: 'Terms' })
    expect(terms).toHaveAttribute('aria-expanded', 'false')
    expect(within(section).getByText(promotionClaimable.terms)).not.toBeVisible()
    await user.click(terms)
    expect(terms).toHaveAttribute('aria-expanded', 'true')
    expect(within(section).getByText(promotionClaimable.terms)).toBeVisible()
    expect(document.title).toBe('Promotions · Telecom Support')
    expect(await axe(container)).toHaveNoViolations()
  })

  it('per number: CTA enabled iff a number is eligible; claimed numbers listed; none left → disabled "Claimed"', async () => {
    serve({ promotions: [promotionClaimable, promotionPartlyClaimed, promotionFullyClaimed] })
    renderPromotions()
    const claimable = await card(promotionClaimable.title)
    expect(claimButton(promotionClaimable.title)).toBeEnabled()
    expect(within(claimable).queryByText(/Claimed for/)).not.toBeInTheDocument()

    const partly = await card(promotionPartlyClaimed.title)
    expect(claimButton(promotionPartlyClaimed.title)).toHaveTextContent('View & claim')
    expect(claimButton(promotionPartlyClaimed.title)).toBeEnabled()
    expect(await within(partly).findByText('Claimed for number ending 0001')).toBeInTheDocument()

    const full = await card(promotionFullyClaimed.title)
    expect(claimButton(promotionFullyClaimed.title)).toHaveTextContent('Claimed')
    expect(claimButton(promotionFullyClaimed.title)).toBeDisabled()
    expect(within(full).getByText('Claimed for number ending 0001')).toBeInTheDocument()
  })

  it.each<[string, Me]>([
    ['promotion.claim', { ...meCustomer, permissions: meCustomer.permissions.filter((p) => p !== 'promotion.claim') }],
    ['plan.read', { ...meCustomer, permissions: meCustomer.permissions.filter((p) => p !== 'plan.read') }],
  ])('without %s there is no claim button', async (missing, me) => {
    serve()
    renderPromotions(me)
    const section = await card()
    expect(within(section).queryByRole('button', { name: /View & claim/ })).not.toBeInTheDocument()
    if (missing === 'plan.read') expect(subscriptionGets).toBe(0) // E5 is not called without plan.read
  })

  it('no offers → empty state', async () => {
    serve({ promotions: [] })
    renderPromotions()
    expect(await screen.findByRole('heading', { name: 'No offers for you right now' })).toBeInTheDocument()
  })

  it('a 500 shows ErrorState with retry; a 403 renders the forbidden page', async () => {
    server.use(http.get(PROMOTIONS_URL, () => problemResponse(problem(500, 'internal', 'Unexpected error.'))))
    renderPromotions()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent("We couldn't load offers")
    expect(alert).toHaveTextContent('corr-500-internal')
    cleanup()
    client.clear()

    server.use(http.get(PROMOTIONS_URL, () => problemResponse(problem(403, 'forbidden', 'Access denied.'))))
    renderPromotions()
    expect(await screen.findByRole('heading', { level: 1, name: 'Access denied' })).toBeInTheDocument()
  })

  it('E5 500 on the page: a "Your numbers" notice with retry instead of silently hiding the claimed numbers (FC8)', async () => {
    serve({ promotions: [promotionPartlyClaimed] })
    let fail = true
    server.use(
      http.get(SUBSCRIPTIONS_URL, () =>
        fail
          ? problemResponse(problem(500, 'internal', 'Unexpected error.'))
          : HttpResponse.json([subscriptionPrimary, subscriptionSecondary]),
      ),
    )
    const user = userEvent.setup()
    const { container } = renderPromotions()
    const notice = await screen.findByRole('region', { name: 'Your numbers' })
    const alert = await within(notice).findByRole('alert')
    expect(within(alert).getByRole('heading', { name: NUMBERS_ERROR_TITLE })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 2, name: promotionPartlyClaimed.title })).toBeInTheDocument()
    expect(await axe(container)).toHaveNoViolations()
    fail = false
    await user.click(within(alert).getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Your numbers' })).not.toBeInTheDocument())
  })

  it('E5 403: the page notice and the claim dialog show "isn\'t available for your account" without retry (FC8)', async () => {
    serve()
    server.use(http.get(SUBSCRIPTIONS_URL, () => problemResponse(problem(403, 'forbidden', 'Access denied.'))))
    const user = userEvent.setup()
    renderPromotions()
    const notice = await screen.findByRole('region', { name: 'Your numbers' })
    expect(within(notice).getByRole('heading', { name: SECTION_FORBIDDEN_TITLE })).toBeInTheDocument()
    expect(within(notice).queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()

    await card()
    await user.click(claimButton())
    const dialog = await screen.findByRole('dialog', { name: promotionClaimable.title })
    expect(await within(dialog).findByRole('heading', { name: SECTION_FORBIDDEN_TITLE })).toBeInTheDocument()
    expect(within(dialog).queryByRole('heading', { name: NUMBERS_ERROR_TITLE })).not.toBeInTheDocument()
    expect(within(dialog).queryByRole('form', { name: 'Claim offer' })).not.toBeInTheDocument()
  })

  it('/customer/promotions#<code> focuses that card\'s heading (after the page heading)', async () => {
    serve({ promotions: [promotionClaimable, promotionPartlyClaimed] })
    renderPromotions(meCustomer, `/customer/promotions#${promotionPartlyClaimed.code}`)
    const heading = await screen.findByRole('heading', { level: 2, name: promotionPartlyClaimed.title })
    await waitFor(() => expect(heading).toHaveFocus())
  })
})

describe('claim (frontend.md §3 Promotions, §6; CUS-04.3/04.4, XC-06, NFR-REL)', () => {
  it('shows full terms; one eligible number is preselected; Confirm needs the terms accepted; nothing is sent before', async () => {
    serve()
    const user = userEvent.setup()
    renderPromotions()
    const dialog = await openClaim(user)
    expect(within(dialog).getByRole('region', { name: 'Terms and conditions' })).toHaveTextContent(promotionClaimable.terms)
    const radio = within(dialog).getByRole('radio', { name: subscriptionPrimary.msisdn })
    expect(radio).toBeChecked()
    expect(acceptTerms(dialog)).not.toBeChecked()

    await user.click(confirm(dialog))
    expect(await within(dialog).findByText('Accept the terms and conditions to claim this offer.')).toBeInTheDocument()
    expect(acceptTerms(dialog)).toHaveAttribute('aria-invalid', 'true')
    expect(posts).toHaveLength(0)
    expect(await axe(dialog)).toHaveNoViolations()

    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(posts).toHaveLength(0)
  })

  it('Confirm sends the number, acceptTerms and termsHash with a key; success after the 201: toast and refreshed claim state', async () => {
    serve()
    const user = userEvent.setup()
    renderPromotions()
    const dialog = await openClaim(user)
    await claimWithTerms(user, dialog)

    expect(await screen.findByText('Offer claimed')).toBeInTheDocument()
    expect(posts).toHaveLength(1)
    expect(posts[0]!.promotionCode).toBe(promotionClaimable.code)
    expect(posts[0]!.body).toEqual({
      subscriptionId: subscriptionPrimary.id,
      acceptTerms: true,
      termsHash: promotionClaimable.termsHash,
    })
    expect(posts[0]!.key).toMatch(UUID_V4)
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const section = await card()
    expect(await within(section).findByText('Claimed for number ending 0001')).toBeInTheDocument()
    expect(claimButton()).toBeDisabled()
    expect(claimButton()).toHaveTextContent('Claimed')
  })

  it('with several eligible numbers the customer must choose one (labelled by MSISDN)', async () => {
    serve({ promotions: [{ ...promotionClaimable, eligibleSubscriptionIds: [101, 102] }] })
    const user = userEvent.setup()
    renderPromotions()
    const dialog = await openClaim(user)
    expect(within(dialog).getByRole('radio', { name: subscriptionPrimary.msisdn })).not.toBeChecked()
    await claimWithTerms(user, dialog)
    expect(await within(dialog).findByText('Choose a number.')).toBeInTheDocument()
    expect(posts).toHaveLength(0)

    await user.click(within(dialog).getByRole('radio', { name: subscriptionSecondary.msisdn }))
    await user.click(confirm(dialog))
    expect(await screen.findByText('Offer claimed')).toBeInTheDocument()
    expect(posts[0]!.body.subscriptionId).toBe(subscriptionSecondary.id)
  })

  it('a double click sends one request', async () => {
    serve({
      replies: [
        async () => {
          await delay(50)
          return HttpResponse.json(promotionClaim, { status: 201 })
        },
      ],
    })
    const user = userEvent.setup()
    renderPromotions()
    const dialog = await openClaim(user)
    await user.click(acceptTerms(dialog))
    await user.dblClick(confirm(dialog))
    expect(await screen.findByText('Offer claimed')).toBeInTheDocument()
    expect(posts).toHaveLength(1)
  })

  it('a 422 → root detail + refetch; new terms must be accepted again; the retry uses a new key and the new hash', async () => {
    const state = serve({
      replies: [
        () => {
          state.set([{ ...promotionClaimable, terms: 'Updated terms.', termsHash: NEW_TERMS_HASH }])
          return problemResponse(problem(422, 'terms-changed', 'The terms have changed. Please review them.'))
        },
      ],
    })
    const user = userEvent.setup()
    renderPromotions()
    const dialog = await openClaim(user)
    await claimWithTerms(user, dialog)

    const alert = await within(dialog).findByRole('alert')
    expect(alert).toHaveTextContent('The terms have changed. Please review them.')
    expect(alert).toHaveTextContent('corr-422-terms-changed')
    await waitFor(() => expect(promotionGets).toBe(2))
    expect(await within(dialog).findByText('Updated terms.')).toBeInTheDocument()
    expect(acceptTerms(dialog)).not.toBeChecked()

    await claimWithTerms(user, dialog)
    expect(await screen.findByText('Offer claimed')).toBeInTheDocument()
    expect(posts[1]!.body.termsHash).toBe(NEW_TERMS_HASH)
    expect(posts[1]!.key).not.toBe(posts[0]!.key)
  })

  it('a 404 → "This offer is no longer available." + refetch, dialog closes', async () => {
    const state = serve({
      replies: [
        () => {
          state.set([])
          return problemResponse(problem(404, 'not-found', 'Promotion not found.'))
        },
      ],
    })
    const user = userEvent.setup()
    renderPromotions()
    const dialog = await openClaim(user)
    await claimWithTerms(user, dialog)
    expect(await screen.findByText(OFFER_GONE_MESSAGE)).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(await screen.findByRole('heading', { name: 'No offers for you right now' })).toBeInTheDocument()
  })

  it('a 409 → "still processing"; the retry reuses the key; the same body on another promotion gets another key', async () => {
    serve({
      promotions: [promotionClaimable, { ...promotionClaimable, code: 'VOICE5', title: 'Free calls' }],
      replies: [() => problemResponse(problem(409, 'idempotency-in-progress', 'Still processing.'))],
    })
    const user = userEvent.setup()
    renderPromotions()
    const dialog = await openClaim(user)
    await claimWithTerms(user, dialog)
    expect(await within(dialog).findByText(CLAIM_STILL_PROCESSING_MESSAGE)).toBeInTheDocument()
    expect(within(dialog).getByRole('alert')).toHaveFocus()

    await user.click(confirm(dialog))
    expect(await screen.findByText('Offer claimed')).toBeInTheDocument()
    expect(posts[1]!.key).toBe(posts[0]!.key)

    const other = await openClaim(user, 'Free calls')
    await claimWithTerms(user, other)
    await waitFor(() => expect(posts).toHaveLength(3))
    expect(posts[2]!.promotionCode).toBe('VOICE5')
    expect(posts[2]!.body).toEqual(posts[0]!.body)
    expect(posts[2]!.key).not.toBe(posts[0]!.key)
  })

  it('an idempotency-mismatch 422 → "Please try again." without business copy', async () => {
    serve({ replies: [() => problemResponse(problem(422, 'idempotency-mismatch', 'Key reused with another body.'))] })
    const user = userEvent.setup()
    renderPromotions()
    const dialog = await openClaim(user)
    await claimWithTerms(user, dialog)
    expect(await within(dialog).findByText('Please try again.')).toBeInTheDocument()
    expect(within(dialog).queryByText('Key reused with another body.')).not.toBeInTheDocument()
  })

  it('a 403 → forbidden toast only; no error in the dialog', async () => {
    serve({ replies: [() => problemResponse(problem(403, 'forbidden', 'Access denied.'))] })
    const user = userEvent.setup()
    renderPromotions()
    const dialog = await openClaim(user)
    await claimWithTerms(user, dialog)
    expect(await screen.findByText(FORBIDDEN_ACTION_MESSAGE)).toBeInTheDocument()
    expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument()
    expect(confirm(dialog)).toBeEnabled()
  })
})
