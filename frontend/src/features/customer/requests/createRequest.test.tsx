import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { axe } from 'vitest-axe'
import { createQueryClient, ME_QUERY_KEY } from '@/app/queryClient'
import {
  caseCategories,
  caseCreated,
  casePage,
  problem,
  profile,
  profileVerified,
  subscriptionPrimary,
  subscriptionSecondary,
} from '@/test/fixtures/customer'
import { meCustomer } from '@/test/fixtures/me'
import { problemResponse } from '@/test/msw/customerHandlers'
import { clearXsrfCookie } from '@/test/msw/handlers'
import { server } from '@/test/msw/server'
import type { CaseCreateRequest, Category, Me, Profile, Subscription } from '@/types/api'
import { SECTION_FORBIDDEN_TITLE } from '@/components/states/SectionErrorState'
import {
  CATEGORIES_UNAVAILABLE,
  CreateRequestPage,
  NUMBERS_ERROR_TITLE,
  NUMBERS_LOADING_LABEL,
  SEVERITY_NOTE,
  STILL_SUBMITTING_MESSAGE,
} from './CreateRequestPage'
import { CASES_QUERY_KEY } from './queries'

const PROFILE_URL = '*/api/v1/customers/me/profile'
const CATEGORIES_URL = '*/api/v1/case-categories'
const SUBSCRIPTIONS_URL = '*/api/v1/customers/me/subscriptions'
const CASES_URL = '*/api/v1/customers/me/cases'
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

type Post = { key: string | null; body: CaseCreateRequest }

let client: QueryClient
let posts: Post[]
let subscriptionGets: number

function serve({
  me = profile,
  categories = caseCategories,
  subscriptions = [subscriptionPrimary],
  replies = [],
}: {
  me?: Profile
  categories?: Category[] | (() => Response)
  subscriptions?: Subscription[] | (() => Response | Promise<Response>)
  /** One reply per POST, in order; after them, 201. */
  replies?: (() => Response | Promise<Response>)[]
} = {}) {
  server.use(
    http.get(PROFILE_URL, () => HttpResponse.json(me)),
    http.get(CATEGORIES_URL, () => (typeof categories === 'function' ? categories() : HttpResponse.json(categories))),
    http.get(SUBSCRIPTIONS_URL, () => {
      subscriptionGets += 1
      return typeof subscriptions === 'function' ? subscriptions() : HttpResponse.json(subscriptions)
    }),
    http.post(CASES_URL, async ({ request }) => {
      posts.push({ key: request.headers.get('Idempotency-Key'), body: (await request.json()) as CaseCreateRequest })
      const reply = replies.shift()
      if (reply) return reply()
      await delay(20)
      return HttpResponse.json(caseCreated, { status: 201 })
    }),
  )
}

function renderPage(me: Me = meCustomer) {
  client = createQueryClient()
  client.setDefaultOptions({ queries: { retry: false } })
  client.setQueryData(ME_QUERY_KEY, me)
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/customer/requests/new']}>
        <CreateRequestPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

type User = ReturnType<typeof userEvent.setup>

const form = () => screen.findByRole('form', { name: 'New request' })

async function choose(user: User, label: string, option: string) {
  await user.click(screen.getByRole('combobox', { name: label }))
  await user.click(await screen.findByRole('option', { name: option }))
}

/** A valid form for the category without subcategories. */
async function fillValid(user: User, category = 'General enquiry') {
  await form()
  await choose(user, 'Category', category)
  await user.type(screen.getByRole('textbox', { name: 'Subject' }), '  Slow data  ')
  await user.type(screen.getByRole('textbox', { name: 'Description' }), 'Data is slow every evening.')
}

const submit = () => screen.getByRole('button', { name: /^(Submit request|Submitting…)$/ })

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
beforeEach(() => {
  posts = []
  subscriptionGets = 0
})
afterEach(() => {
  cleanup()
  server.resetHandlers()
  clearXsrfCookie()
  client.clear()
})
afterAll(() => server.close())

describe('Create request (frontend.md §3 Create request, §6; CUS-06)', () => {
  it('shows contact number and account reference read-only, the severity note, no severity or attachment input; axe clean', async () => {
    serve()
    const { container } = renderPage()
    expect(screen.getByText('Loading the request form')).toBeInTheDocument()
    const f = await form()
    expect(within(f).getByText('Contact number').nextElementSibling).toHaveTextContent(profile.primaryMsisdn)
    expect(within(f).getByText('Account reference').nextElementSibling).toHaveTextContent(profile.accountRef)
    expect(within(f).queryByRole('textbox', { name: /contact|account/i })).not.toBeInTheDocument()
    expect(within(f).getByText(SEVERITY_NOTE)).toBeInTheDocument()
    expect(within(f).queryByRole('combobox', { name: /severity|priority/i })).not.toBeInTheDocument()
    expect(container.querySelector('input[type="file"]')).toBeNull()
    expect(within(f).queryByRole('combobox', { name: 'Subcategory' })).not.toBeInTheDocument()
    expect(document.title).toBe('New request · Telecom Support')
    expect(await axe(container)).toHaveNoViolations()
  })

  it('client validation: nothing is sent and the errors are linked to their fields', async () => {
    serve()
    const user = userEvent.setup()
    renderPage()
    await form()
    await user.click(submit())
    expect(await screen.findByText('Choose a category.')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Subject' })).toHaveAccessibleDescription('Enter at least 5 characters.')
    expect(screen.getByRole('textbox', { name: 'Description' })).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('combobox', { name: 'Category' })).toHaveFocus()
    expect(posts).toEqual([])
  })

  it('subcategory: shown and required only when the category has subcategories; reset on category change', async () => {
    serve()
    const user = userEvent.setup()
    renderPage()
    await fillValid(user, 'Billing')
    expect(screen.getByRole('combobox', { name: 'Subcategory' })).toBeInTheDocument()
    await user.click(submit())
    expect(await screen.findByText('Choose a subcategory.')).toBeInTheDocument()
    expect(posts).toEqual([])

    await choose(user, 'Subcategory', 'Refund')
    await user.click(submit())
    await screen.findByRole('heading', { name: `Request ${caseCreated.reference} submitted` })
    expect(posts[0]!.body).toEqual({
      categoryCode: 'BILLING',
      subcategoryCode: 'BILLING_REFUND',
      subscriptionId: null,
      subject: 'Slow data',
      description: 'Data is slow every evening.',
      preferredContact: 'PHONE',
    })
  })

  it('a category without subcategories hides the subcategory and sends null; severity is never sent', async () => {
    serve()
    const user = userEvent.setup()
    renderPage()
    await form()
    await choose(user, 'Category', 'Billing')
    await choose(user, 'Subcategory', 'Refund')
    await fillValid(user, 'General enquiry')
    expect(screen.queryByRole('combobox', { name: 'Subcategory' })).not.toBeInTheDocument()
    await user.click(submit())
    await waitFor(() => expect(posts).toHaveLength(1))
    expect(posts[0]!.body).toMatchObject({ categoryCode: 'GENERAL', subcategoryCode: null })
    expect(posts[0]!.body).not.toHaveProperty('severity')
  })

  it('Email is disabled with the hint unless the email is verified', async () => {
    serve()
    renderPage()
    await form()
    const email = screen.getByRole('radio', { name: 'Email' })
    expect(email).toBeDisabled()
    expect(email).toHaveAccessibleDescription('Verify your email in Profile first')
    expect(screen.getByRole('radio', { name: 'Phone' })).toBeChecked()
  })

  it('with a verified email, Email can be chosen and is sent', async () => {
    serve({ me: profileVerified })
    const user = userEvent.setup()
    renderPage()
    await fillValid(user)
    await user.click(screen.getByRole('radio', { name: 'Email' }))
    await user.click(submit())
    await waitFor(() => expect(posts).toHaveLength(1))
    expect(posts[0]!.body.preferredContact).toBe('EMAIL')
  })

  it('number picker: hidden with one subscription; with two it defaults to "Not specific" and sends the chosen id', async () => {
    serve({ subscriptions: [subscriptionPrimary, subscriptionSecondary] })
    const user = userEvent.setup()
    renderPage()
    await fillValid(user)
    const picker = await screen.findByRole('combobox', { name: 'Which number is it about?' })
    expect(picker).toHaveTextContent('Not specific')
    await choose(user, 'Which number is it about?', subscriptionSecondary.msisdn)
    await user.click(submit())
    await waitFor(() => expect(posts).toHaveLength(1))
    expect(posts[0]!.body.subscriptionId).toBe(subscriptionSecondary.id)
  })

  it('number picker while E5 is pending: a loading state and Submit disabled, so "Not specific" is never sent by accident (FC2)', async () => {
    let release: () => void = () => {}
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    serve({
      subscriptions: async () => {
        await gate
        return HttpResponse.json([subscriptionPrimary, subscriptionSecondary])
      },
    })
    const user = userEvent.setup()
    renderPage()
    await fillValid(user)
    expect(screen.getByText(NUMBERS_LOADING_LABEL)).toBeInTheDocument()
    expect(submit()).toBeDisabled()
    await user.click(submit())
    expect(posts).toHaveLength(0)
    release()
    await screen.findByRole('combobox', { name: 'Which number is it about?' })
    expect(screen.queryByText(NUMBERS_LOADING_LABEL)).not.toBeInTheDocument()
    expect(submit()).toBeEnabled()
  })

  it('number picker E5 500: its section shows an error with retry; the retry brings the picker back (FC2)', async () => {
    let fail = true
    serve({
      subscriptions: () =>
        fail
          ? problemResponse(problem(500, 'internal', 'Unexpected error.'))
          : HttpResponse.json([subscriptionPrimary, subscriptionSecondary]),
    })
    const user = userEvent.setup()
    const { container } = renderPage()
    const section = await screen.findByRole('region', { name: 'Which number is it about?' })
    const alert = await within(section).findByRole('alert')
    expect(within(alert).getByRole('heading', { name: NUMBERS_ERROR_TITLE })).toBeInTheDocument()
    expect(alert).toHaveTextContent('corr-500-internal')
    expect(screen.getByRole('form', { name: 'New request' })).toBeInTheDocument()
    expect(await axe(container)).toHaveNoViolations()
    fail = false
    await user.click(within(alert).getByRole('button', { name: 'Try again' }))
    await screen.findByRole('combobox', { name: 'Which number is it about?' })
    expect(screen.queryByRole('region', { name: 'Which number is it about?' })).not.toBeInTheDocument()
  })

  it('number picker E5 403: the section shows the forbidden-section copy without retry; the form stays usable (FC2)', async () => {
    serve({ subscriptions: () => problemResponse(problem(403, 'forbidden', 'Forbidden.')) })
    const user = userEvent.setup()
    renderPage()
    const section = await screen.findByRole('region', { name: 'Which number is it about?' })
    const alert = await within(section).findByRole('alert')
    expect(within(alert).getByRole('heading', { name: SECTION_FORBIDDEN_TITLE })).toBeInTheDocument()
    expect(within(alert).queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Access denied' })).not.toBeInTheDocument()
    await fillValid(user)
    expect(submit()).toBeEnabled()
    await user.click(submit())
    await screen.findByRole('heading', { name: `Request ${caseCreated.reference} submitted` })
    expect(posts[0]!.body.subscriptionId).toBeNull()
  })

  it.each<[string, Me | undefined, Subscription[]]>([
    ['one subscription', undefined, [subscriptionPrimary]],
    ['no plan.read', { ...meCustomer, permissions: meCustomer.permissions.filter((p) => p !== 'plan.read') }, [subscriptionPrimary, subscriptionSecondary]],
  ])('no number picker with %s', async (name, me, subscriptions) => {
    serve({ subscriptions })
    renderPage(me)
    await form()
    if (name === 'no plan.read') expect(subscriptionGets).toBe(0)
    else await waitFor(() => expect(subscriptionGets).toBe(1))
    expect(screen.queryByRole('combobox', { name: 'Which number is it about?' })).not.toBeInTheDocument()
  })

  it('description counter is linked to the field and announced only from 3900 characters', async () => {
    serve()
    renderPage()
    await form()
    const description = screen.getByRole('textbox', { name: 'Description' })
    expect(description).toHaveAccessibleDescription('0 / 4000 characters')
    const live = document.querySelector('[aria-live="polite"].sr-only')!
    fireEvent.change(description, { target: { value: 'a'.repeat(3899) } })
    expect(screen.getByText('3899 / 4000 characters')).toBeInTheDocument()
    expect(live).toHaveTextContent('')
    fireEvent.change(description, { target: { value: 'a'.repeat(3900) } })
    expect(live).toHaveTextContent('100 characters left')
  })

  it('double click → one POST with a UUID key; success shows the reference, focuses it and invalidates the lists', async () => {
    serve()
    const user = userEvent.setup()
    renderPage()
    await fillValid(user)
    client.setQueryData([...CASES_QUERY_KEY, { page: 0, size: 20 }], casePage())
    await user.dblClick(submit())
    const heading = await screen.findByRole('heading', { name: `Request ${caseCreated.reference} submitted` })
    expect(posts).toHaveLength(1)
    expect(posts[0]!.key).toMatch(UUID_V4)
    await waitFor(() => expect(heading).toHaveFocus())
    expect(within(heading).getByRole('status')).toHaveTextContent(`Request ${caseCreated.reference} submitted`)
    const confirmation = heading.closest('section')!
    expect(within(confirmation).getByText('Status').nextElementSibling).toHaveTextContent('Submitted')
    expect(within(confirmation).getByText('Priority').nextElementSibling).toHaveTextContent('Low')
    expect(within(confirmation).getByText('Created').nextElementSibling).toHaveTextContent('26 Nov 2026, 10:30 am')
    expect(within(confirmation).getByRole('link', { name: 'View my requests' })).toHaveAttribute('href', '/customer/requests')
    expect(client.getQueryState([...CASES_QUERY_KEY, { page: 0, size: 20 }])?.isInvalidated).toBe(true)
  })

  it('"Create another" resets the form and uses a new key for the same content', async () => {
    serve()
    const user = userEvent.setup()
    renderPage()
    await fillValid(user)
    await user.click(submit())
    await user.click(await screen.findByRole('button', { name: 'Create another' }))
    await form()
    expect(screen.getByRole('textbox', { name: 'Subject' })).toHaveValue('')
    await fillValid(user)
    await user.click(submit())
    await screen.findByRole('button', { name: 'Create another' })
    expect(posts).toHaveLength(2)
    expect(posts[1]!.key).not.toBe(posts[0]!.key)
  })

  it('a 500 keeps the key: the retry re-sends the same key; root error shows the correlation id', async () => {
    serve({ replies: [() => problemResponse(problem(500, 'internal', 'Unexpected error.'))] })
    const user = userEvent.setup()
    renderPage()
    await fillValid(user)
    await user.click(submit())
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Something went wrong. Please try again.')
    expect(alert).toHaveTextContent('corr-500-internal')
    await waitFor(() => expect(alert).toHaveFocus())
    await user.click(submit())
    await screen.findByRole('heading', { name: `Request ${caseCreated.reference} submitted` })
    expect(posts).toHaveLength(2)
    expect(posts[1]!.key).toBe(posts[0]!.key)
  })

  it('409 → "still being submitted" (key kept); 422 → the server detail (new key next time)', async () => {
    serve({
      replies: [
        () => problemResponse(problem(409, 'idempotency-in-progress', 'In progress.')),
        () => problemResponse(problem(422, 'business-rule', 'You have too many open requests.')),
      ],
    })
    const user = userEvent.setup()
    renderPage()
    await fillValid(user)
    await user.click(submit())
    expect(await screen.findByRole('alert')).toHaveTextContent(STILL_SUBMITTING_MESSAGE)
    await user.click(submit())
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('You have too many open requests.'))
    expect(posts[1]!.key).toBe(posts[0]!.key)
    await user.click(submit())
    await screen.findByRole('heading', { name: `Request ${caseCreated.reference} submitted` })
    expect(posts[2]!.key).not.toBe(posts[1]!.key)
  })

  it('an idempotency-mismatch 422 shows "Please try again."', async () => {
    serve({ replies: [() => problemResponse(problem(422, 'idempotency-mismatch', 'Key reused with another body.'))] })
    const user = userEvent.setup()
    renderPage()
    await fillValid(user)
    await user.click(submit())
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Please try again.')
    expect(alert).not.toHaveTextContent('Key reused')
  })

  it('a 400 maps errors[] onto the fields and focuses the first one', async () => {
    serve({
      replies: [
        () =>
          problemResponse(
            problem(400, 'validation', 'Validation failed.', {
              errors: [{ field: 'subject', message: 'Subject is not allowed.' }],
            }),
          ),
      ],
    })
    const user = userEvent.setup()
    renderPage()
    await fillValid(user)
    await user.click(submit())
    const subject = screen.getByRole('textbox', { name: 'Subject' })
    await waitFor(() => expect(subject).toHaveAccessibleDescription('Subject is not allowed.'))
    expect(subject).toHaveFocus()
  })

  it('E10 error → ErrorState with retry instead of the form; E10 empty → unavailable message', async () => {
    serve({ categories: () => problemResponse(problem(500, 'internal', 'Unexpected error.')) })
    renderPage()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent("We couldn't load request categories")
    expect(within(alert).getByRole('button', { name: 'Try again' })).toBeInTheDocument()
    expect(screen.queryByRole('form', { name: 'New request' })).not.toBeInTheDocument()
    cleanup()
    client.clear()

    serve({ categories: [] })
    renderPage()
    expect(await screen.findByRole('heading', { level: 2, name: CATEGORIES_UNAVAILABLE })).toBeInTheDocument()
    expect(screen.queryByRole('form', { name: 'New request' })).not.toBeInTheDocument()
  })

  it('a 403 on the categories renders the forbidden page', async () => {
    serve({ categories: () => problemResponse(problem(403, 'forbidden', 'Access denied.')) })
    renderPage()
    expect(await screen.findByRole('heading', { level: 1, name: 'Access denied' })).toBeInTheDocument()
  })
})
