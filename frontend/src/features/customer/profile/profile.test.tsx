import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { toast, Toaster } from 'sonner'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { axe } from 'vitest-axe'
import { createQueryClient, ME_QUERY_KEY } from '@/app/queryClient'
import { problem, profile, profileVerified } from '@/test/fixtures/customer'
import { meCustomer } from '@/test/fixtures/me'
import { problemResponse } from '@/test/msw/customerHandlers'
import { clearXsrfCookie } from '@/test/msw/handlers'
import { server } from '@/test/msw/server'
import type { Me, Profile, ProfileUpdate } from '@/types/api'
import { BILLING_ADDRESS_NOTICE, ProfilePage } from './ProfilePage'
import { PROFILE_QUERY_KEY } from './queries'

const PROFILE_URL = '*/api/v1/customers/me/profile'

let client: QueryClient
let gets: number
let patches: ProfileUpdate[]

/** The profile endpoints with a server-side profile that a PATCH or a test can change (E1, E2; stale version → 409). */
function serveProfile(initial: Profile = profile) {
  let current = initial
  server.use(
    http.get(PROFILE_URL, () => {
      gets += 1
      return HttpResponse.json(current)
    }),
    http.patch(PROFILE_URL, async ({ request }) => {
      const body = (await request.json()) as ProfileUpdate
      patches.push(body)
      if (body.version !== current.version) return problemResponse(problem(409, 'conflict', 'The profile was changed.'))
      current = { ...current, aliasName: body.aliasName, version: current.version + 1 }
      return HttpResponse.json(current)
    }),
  )
  return {
    /** Another tab or agent changed the profile on the server. */
    changeOnServer: (change: Partial<Profile>) => {
      current = { ...current, ...change, version: current.version + 1 }
    },
  }
}

function renderProfile(me: Me = meCustomer, { retry = true }: { retry?: boolean } = {}) {
  client = createQueryClient()
  if (!retry) client.setDefaultOptions({ queries: { retry: false } })
  client.setQueryData(ME_QUERY_KEY, me)
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/customer/profile']}>
        <ProfilePage />
      </MemoryRouter>
      <Toaster />
    </QueryClientProvider>,
  )
}

const aliasInput = () => screen.getByLabelText('Alias (optional)')
const saveButton = () => screen.getByRole('button', { name: 'Save' })
const loaded = () => screen.findByText(profile.fullName)

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
beforeEach(() => {
  gets = 0
  patches = []
  toast.dismiss()
})
afterEach(() => {
  cleanup() // unmount first: clearing the cache under a mounted useMeQuery would start a re-bootstrap
  server.resetHandlers()
  clearXsrfCookie()
  client.clear()
})
afterAll(() => server.close())

describe('Profile page (frontend.md §3 Profile; CUS-02.1/02.3)', () => {
  it('shows a loading state, then the read-only block, contacts and the billing notice; axe clean', async () => {
    serveProfile(profileVerified)
    const { container } = renderProfile()
    expect(screen.getByText('Loading profile')).toBeInTheDocument()
    await loaded()

    const personal = screen.getByRole('region', { name: 'Personal details' })
    expect(within(personal).getByText(profile.primaryMsisdn)).toBeInTheDocument()
    expect(within(personal).getByText(profile.customerRef)).toBeInTheDocument()
    expect(within(personal).getByText(profile.accountRef)).toBeInTheDocument()
    expect(aliasInput()).toHaveValue('Ravi')

    const contacts = screen.getByRole('region', { name: 'Contact details' })
    expect(within(contacts).getByText('ravi@example.com')).toBeInTheDocument()
    expect(within(contacts).getAllByText('Verified')).toHaveLength(2)

    const billing = screen.getByRole('region', { name: 'Billing address' })
    expect(within(billing).getByText('12 MG Road')).toBeInTheDocument()
    expect(within(billing).getByText(BILLING_ADDRESS_NOTICE)).toBeInTheDocument()
    expect(within(billing).queryByRole('textbox')).not.toBeInTheDocument()
    expect(document.title).toBe('Profile · Telecom Support')
    expect(await axe(container)).toHaveNoViolations()
  })

  it('unset contacts show "Not set"; an unverified contact says so in text', async () => {
    serveProfile({ ...profile, email: 'new@example.com', emailVerified: false })
    renderProfile()
    await loaded()
    const contacts = screen.getByRole('region', { name: 'Contact details' })
    expect(within(contacts).getByText('Not verified')).toBeInTheDocument()
    expect(within(contacts).getByText('Not set')).toBeInTheDocument()
  })

  it('a 500 shows ErrorState with the correlation id and retry', async () => {
    let fail = true
    server.use(
      http.get(PROFILE_URL, () =>
        fail ? problemResponse(problem(500, 'internal', 'Unexpected error.')) : HttpResponse.json(profile),
      ),
    )
    const user = userEvent.setup()
    renderProfile(meCustomer, { retry: false }) // the 5xx retries (01 §5) are covered by the query client's own tests
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent("We couldn't load your profile")
    expect(alert).toHaveTextContent('corr-500-internal')
    fail = false
    await user.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await loaded()).toBeInTheDocument()
  })

  it('a 403 on the profile query renders the forbidden page', async () => {
    server.use(http.get(PROFILE_URL, () => problemResponse(problem(403, 'forbidden', 'Access denied.'))))
    renderProfile()
    expect(await screen.findByRole('heading', { level: 1, name: 'Access denied' })).toBeInTheDocument()
  })

  it('without customer.profile.update the alias is shown read-only', async () => {
    serveProfile(profileVerified)
    renderProfile({ ...meCustomer, permissions: meCustomer.permissions.filter((p) => p !== 'customer.profile.update') })
    await loaded()
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.getByText('Ravi')).toBeInTheDocument()
  })
})

describe('Alias form (frontend.md §3 Profile, §6; CUS-02.1/02.4, XC-07)', () => {
  it('saves the trimmed alias with the cached version: cache updated, toast "Profile saved", form clean', async () => {
    serveProfile()
    const user = userEvent.setup()
    renderProfile()
    await loaded()
    await user.type(aliasInput(), '  Ravi K ')
    await user.click(saveButton())

    expect(await screen.findByText('Profile saved')).toBeInTheDocument()
    expect(patches).toEqual([{ aliasName: 'Ravi K', version: 3 }])
    expect(client.getQueryData<Profile>(PROFILE_QUERY_KEY)).toMatchObject({ aliasName: 'Ravi K', version: 4 })
    expect(aliasInput()).toHaveValue('Ravi K')
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled()
  })

  it("'' clears the alias (sends null)", async () => {
    serveProfile(profileVerified)
    const user = userEvent.setup()
    renderProfile()
    await loaded()
    await user.clear(aliasInput())
    await user.type(aliasInput(), '   ')
    await user.click(saveButton())
    await screen.findByText('Profile saved')
    expect(patches).toEqual([{ aliasName: null, version: profileVerified.version }])
  })

  it('sends the version from the current cache at submit, not the one the form started with', async () => {
    serveProfile()
    const user = userEvent.setup()
    renderProfile()
    await loaded()
    await user.type(aliasInput(), 'Ravi')
    // A newer profile arrives in the cache (e.g. a confirmed contact change) while the field is dirty: the input is kept.
    act(() => {
      client.setQueryData<Profile>(PROFILE_QUERY_KEY, { ...profile, aliasName: 'Other', version: 9 })
    })
    await waitFor(() => expect(aliasInput()).toHaveValue('Ravi'))
    await user.click(saveButton())
    await waitFor(() => expect(patches).toHaveLength(1))
    expect(patches[0]).toEqual({ aliasName: 'Ravi', version: 9 })
  })

  it('a newer cached profile resets the form when it is not dirty', async () => {
    serveProfile()
    renderProfile()
    await loaded()
    act(() => {
      client.setQueryData<Profile>(PROFILE_QUERY_KEY, { ...profile, aliasName: 'From elsewhere', version: 4 })
    })
    await waitFor(() => expect(aliasInput()).toHaveValue('From elsewhere'))
  })

  it('409 → root message, the profile is refetched, and saving again uses the new version', async () => {
    const backend = serveProfile()
    const user = userEvent.setup()
    renderProfile()
    await loaded()
    backend.changeOnServer({}) // version 3 → 4 on the server; the cache still holds 3

    await user.type(aliasInput(), 'Ravi')
    await user.click(saveButton())
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Your profile was changed elsewhere. We loaded the latest version.')
    await waitFor(() => expect(client.getQueryData<Profile>(PROFILE_QUERY_KEY)?.version).toBe(4))
    expect(gets).toBe(2)
    expect(aliasInput()).toHaveValue('Ravi') // the user's input is kept

    await user.click(saveButton())
    await screen.findByText('Profile saved')
    expect(patches.map((p) => p.version)).toEqual([3, 4])
  })

  it('nothing changes in the cache before the 200 (no optimistic update)', async () => {
    serveProfile()
    server.use(
      http.patch(PROFILE_URL, async () => {
        await delay(50)
        return HttpResponse.json({ ...profile, aliasName: 'Ravi', version: 4 })
      }),
    )
    const user = userEvent.setup()
    renderProfile()
    await loaded()
    await user.type(aliasInput(), 'Ravi')
    await user.click(saveButton())
    expect(await screen.findByRole('button', { name: 'Saving…' })).toBeDisabled()
    expect(client.getQueryData<Profile>(PROFILE_QUERY_KEY)?.aliasName).toBeNull()
    await screen.findByText('Profile saved')
  })

  it.each([
    ['61 characters', 'a'.repeat(61), 'Use at most 60 characters.'],
    ['a disallowed character', 'Ravi<3', "Use only letters, numbers, spaces and . ' -"],
  ])('validation: %s → field error, focus on the field, no request', async (_name, value, message) => {
    serveProfile()
    const user = userEvent.setup()
    renderProfile()
    await loaded()
    await user.type(aliasInput(), value)
    await user.click(saveButton())
    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(aliasInput()).toHaveAttribute('aria-invalid', 'true')
    expect(aliasInput()).toHaveAccessibleDescription(message)
    expect(aliasInput()).toHaveFocus()
    expect(patches).toEqual([])
  })

  it('400 → the field error from the server', async () => {
    serveProfile()
    server.use(
      http.patch(PROFILE_URL, () =>
        problemResponse(
          problem(400, 'validation', 'Invalid request.', { errors: [{ field: 'aliasName', message: 'Alias is invalid.' }] }),
        ),
      ),
    )
    const user = userEvent.setup()
    renderProfile()
    await loaded()
    await user.type(aliasInput(), 'Ravi')
    await user.click(saveButton())
    expect(await screen.findByText('Alias is invalid.')).toBeInTheDocument()
    expect(aliasInput()).toHaveFocus()
  })

  it.each([
    [422, 'account-closed', 'Your account is closed.', 'Your account is closed.'],
    [500, 'internal', 'Unexpected error.', 'Something went wrong. Please try again.'],
  ])('%i → root alert with the correlation id, focused; Cancel restores the saved alias', async (status, type, detail, shown) => {
    serveProfile()
    server.use(http.patch(PROFILE_URL, () => problemResponse(problem(status, type, detail))))
    const user = userEvent.setup()
    renderProfile()
    await loaded()
    await user.type(aliasInput(), 'Ravi')
    await user.click(saveButton())
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(shown)
    expect(alert).toHaveTextContent(`corr-${status}-${type}`)
    await waitFor(() => expect(alert).toHaveFocus())
    expect(client.getQueryData<Profile>(PROFILE_QUERY_KEY)?.aliasName).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(aliasInput()).toHaveValue('')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
