import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { toast, Toaster } from 'sonner'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { axe } from 'vitest-axe'
import { createQueryClient, ME_QUERY_KEY } from '@/app/queryClient'
import { ApiError } from '@/services/api/errors'
import { contactVerification, problem, profile, verificationProblem } from '@/test/fixtures/customer'
import { meCustomer } from '@/test/fixtures/me'
import { problemResponse } from '@/test/msw/customerHandlers'
import { clearXsrfCookie } from '@/test/msw/handlers'
import { server } from '@/test/msw/server'
import type { ContactVerification, ContactVerificationStart, Profile } from '@/types/api'
import { attemptsRemaining, normaliseMsisdn } from './contactSchemas'
import { ProfilePage } from './ProfilePage'
import { PROFILE_QUERY_KEY } from './queries'

const ME = '*/api/v1/customers/me'
const CODE = '482913'

let client: QueryClient
let starts: ContactVerificationStart[]
let confirms: { id: string; code: string }[]
let nextId: number

/** E1, E3, E4 with server state: a confirm applies the started value and bumps the profile version. */
function serveContacts(confirmReply?: () => Response | undefined) {
  let current: Profile = profile
  let pending: ContactVerificationStart | null = null
  server.use(
    http.get(`${ME}/profile`, () => HttpResponse.json(current)),
    http.post(`${ME}/contact-verifications`, async ({ request }) => {
      const body = (await request.json()) as ContactVerificationStart
      starts.push(body)
      pending = body
      const verification: ContactVerification = {
        ...contactVerification,
        id: nextId++,
        channel: body.channel,
        expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
      }
      return HttpResponse.json(verification, { status: 201 })
    }),
    http.post(`${ME}/contact-verifications/:id/confirm`, async ({ request, params }) => {
      const { code } = (await request.json()) as { code: string }
      confirms.push({ id: String(params.id), code })
      const override = confirmReply?.()
      if (override) return override
      if (pending?.channel === 'EMAIL') current = { ...current, email: pending.value, emailVerified: true }
      else if (pending) current = { ...current, secondaryMsisdn: pending.value, secondaryMsisdnVerified: true }
      current = { ...current, version: current.version + 1 }
      return HttpResponse.json(current)
    }),
  )
}

function renderProfile() {
  client = createQueryClient()
  client.setQueryData(ME_QUERY_KEY, meCustomer)
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/customer/profile']}>
        <ProfilePage />
      </MemoryRouter>
      <Toaster />
    </QueryClientProvider>,
  )
}

async function openDialog(name: 'Add email' | 'Add secondary mobile' | 'Change email') {
  const user = userEvent.setup()
  renderProfile()
  await user.click(await screen.findByRole('button', { name }))
  const dialog = await screen.findByRole('dialog', { name })
  return { user, dialog }
}

async function sendEmailCode(user: ReturnType<typeof userEvent.setup>, dialog: HTMLElement, value = 'ravi@example.com') {
  await user.type(within(dialog).getByLabelText('New email address'), value)
  await user.click(within(dialog).getByRole('button', { name: 'Send code' }))
  return within(dialog).findByLabelText('6-digit code')
}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
beforeEach(() => {
  starts = []
  confirms = []
  nextId = 501
  toast.dismiss()
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
  server.resetHandlers()
  clearXsrfCookie()
  client?.clear() // unset in the pure-function tests
})
afterAll(() => server.close())

describe('attemptsRemaining / normaliseMsisdn (frontend.md §4, §6; review F13, OQ 4)', () => {
  const apiError = (status: number, body: object | null) =>
    new ApiError({ status, problem: body as ApiError['problem'], correlationId: null })

  it('reads a numeric attemptsRemaining from a 422 only', () => {
    expect(attemptsRemaining(apiError(422, verificationProblem(2)))).toBe(2)
    expect(attemptsRemaining(apiError(422, verificationProblem(0)))).toBe(0)
    expect(attemptsRemaining(apiError(422, problem(422, 'verification-failed')))).toBeUndefined()
    expect(attemptsRemaining(apiError(422, { ...problem(422, 'x'), attemptsRemaining: '2' }))).toBeUndefined()
    expect(attemptsRemaining(apiError(400, verificationProblem(2)))).toBeUndefined()
    expect(attemptsRemaining(new Error('x'))).toBeUndefined()
  })

  it('a 10-digit Indian mobile becomes +91…; others are only compacted', () => {
    expect(normaliseMsisdn('98111 12222')).toBe('+919811112222')
    expect(normaliseMsisdn('+44 7700-900123')).toBe('+447700900123')
    expect(normaliseMsisdn('5811112222')).toBe('5811112222')
  })
})

describe('ContactChangeDialog (frontend.md §3 Profile, §6; CUS-02.2, NFR-SEC)', () => {
  it('email: validation, lower-cased start, masked destination, expiry and 30 s resend cooldown; axe clean', async () => {
    serveContacts()
    const { user, dialog } = await openDialog('Add email')
    expect(within(dialog).getByLabelText('New email address')).toHaveFocus()
    expect(await axe(dialog)).toHaveNoViolations()

    await user.type(within(dialog).getByLabelText('New email address'), 'not-an-email')
    await user.click(within(dialog).getByRole('button', { name: 'Send code' }))
    expect(await within(dialog).findByText('Enter a valid email address.')).toBeInTheDocument()
    expect(starts).toEqual([])

    await user.clear(within(dialog).getByLabelText('New email address'))
    const code = await sendEmailCode(user, dialog, '  Ravi@Example.COM ')
    expect(starts).toEqual([{ channel: 'EMAIL', value: 'ravi@example.com' }])
    expect(within(dialog).getByText(contactVerification.maskedDestination)).toBeInTheDocument()
    expect(code).toHaveAttribute('inputmode', 'numeric')
    expect(code).toHaveAttribute('autocomplete', 'one-time-code')
    expect(within(dialog).getByTestId('code-expiry')).toHaveTextContent(/^Code expires in (10:00|9:5\d)$/)
    expect(within(dialog).getByRole('button', { name: /^Send a new code \((30|29) s\)$/ })).toBeDisabled()
    expect(await axe(dialog)).toHaveNoViolations()
  })

  it('secondary mobile: 10 digits → +91…, the primary number is refused', async () => {
    serveContacts()
    const { user, dialog } = await openDialog('Add secondary mobile')
    const input = within(dialog).getByLabelText('New mobile number')
    await user.type(input, '98000 00001')
    await user.click(within(dialog).getByRole('button', { name: 'Send code' }))
    expect(await within(dialog).findByText('This is already your primary mobile number.')).toBeInTheDocument()
    await user.clear(input)
    await user.type(input, '12345')
    await user.click(within(dialog).getByRole('button', { name: 'Send code' }))
    expect(
      await within(dialog).findByText('Enter a 10-digit mobile number, or the full number with its country code (+…).'),
    ).toBeInTheDocument()
    expect(starts).toEqual([])

    await user.clear(input)
    await user.type(input, '9811112222')
    await user.click(within(dialog).getByRole('button', { name: 'Send code' }))
    await within(dialog).findByLabelText('6-digit code')
    expect(starts).toEqual([{ channel: 'SECONDARY_MSISDN', value: '+919811112222' }])
  })

  it('success: profile cache and version updated, toast, dialog closes, focus back on the row button, code not cached', async () => {
    serveContacts()
    const { user, dialog } = await openDialog('Add email')
    const code = await sendEmailCode(user, dialog)
    await user.type(code, CODE)
    await user.click(within(dialog).getByRole('button', { name: 'Verify' }))

    expect(await screen.findByText('Email verified')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(confirms).toEqual([{ id: '501', code: CODE }])
    expect(client.getQueryData<Profile>(PROFILE_QUERY_KEY)).toMatchObject({
      email: 'ravi@example.com',
      emailVerified: true,
      version: profile.version + 1,
    })
    const trigger = screen.getByRole('button', { name: 'Change email' })
    await waitFor(() => expect(trigger).toHaveFocus())
    const contacts = screen.getByRole('region', { name: 'Contact details' })
    expect(within(contacts).getByText('Verified')).toBeInTheDocument()

    // NFR-SEC: neither the code nor the contact value stays in the MutationCache, and the toast doesn't carry the code.
    await waitFor(() => expect(client.getMutationCache().getAll()).toHaveLength(0))
    expect(document.body.textContent).not.toContain(CODE)
  })

  it('wrong code → "Incorrect code. N attempts left." on the field', async () => {
    serveContacts(() => problemResponse(verificationProblem(3)))
    const { user, dialog } = await openDialog('Add email')
    const code = await sendEmailCode(user, dialog)
    await user.type(code, '111111')
    await user.click(within(dialog).getByRole('button', { name: 'Verify' }))
    expect(await within(dialog).findByText('Incorrect code. 3 attempts left.')).toBeInTheDocument()
    expect(code).toHaveAttribute('aria-invalid', 'true')
    expect(code).toHaveFocus()
  })

  it('locked or expired (422 without attempts left) → root detail, input disabled; "Send a new code" starts again', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] })
    let reply: Response | undefined = problemResponse({ ...verificationProblem(0), detail: 'Too many wrong codes.' })
    serveContacts(() => reply)
    const { user, dialog } = await openDialog('Add email')
    const code = await sendEmailCode(user, dialog)
    await user.type(code, '111111')
    await user.click(within(dialog).getByRole('button', { name: 'Verify' }))

    const alert = await within(dialog).findByRole('alert')
    expect(alert).toHaveTextContent('Too many wrong codes.')
    expect(code).toBeDisabled()
    expect(within(dialog).getByRole('button', { name: 'Verify' })).toBeDisabled()

    act(() => {
      vi.advanceTimersByTime(30_000)
    })
    reply = undefined
    await user.click(within(dialog).getByRole('button', { name: 'Send a new code' }))
    await waitFor(() => expect(starts).toHaveLength(2))
    await waitFor(() => expect(within(dialog).getByLabelText('6-digit code')).toBeEnabled())
    expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('6-digit code'), CODE)
    await user.click(within(dialog).getByRole('button', { name: 'Verify' }))
    expect(await screen.findByText('Email verified')).toBeInTheDocument()
    expect(confirms.map((c) => c.id)).toEqual(['501', '502'])
  })

  it('resend is disabled for 30 s with a visible countdown, then sends a new code', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] })
    serveContacts()
    const { user, dialog } = await openDialog('Add email')
    await sendEmailCode(user, dialog)
    expect(within(dialog).getByRole('button', { name: 'Send a new code (30 s)' })).toBeDisabled()
    act(() => {
      vi.advanceTimersByTime(12_000)
    })
    expect(within(dialog).getByRole('button', { name: 'Send a new code (18 s)' })).toBeDisabled()
    act(() => {
      vi.advanceTimersByTime(18_000)
    })
    await user.click(within(dialog).getByRole('button', { name: 'Send a new code' }))
    await waitFor(() => expect(starts).toHaveLength(2))
    expect(await within(dialog).findByRole('button', { name: 'Send a new code (30 s)' })).toBeDisabled()
  })

  it('expiry: announced only at 1 minute left and at 0', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] })
    serveContacts()
    const { user, dialog } = await openDialog('Add email')
    await sendEmailCode(user, dialog)
    const live = dialog.querySelector('[aria-live="polite"]')
    expect(live).toHaveTextContent('')
    act(() => {
      vi.advanceTimersByTime(9 * 60_000 + 1000)
    })
    expect(within(dialog).getByTestId('code-expiry')).toHaveTextContent('Code expires in 0:59')
    expect(live).toHaveTextContent('Less than 1 minute left to enter the code.')
    act(() => {
      vi.advanceTimersByTime(60_000)
    })
    expect(within(dialog).getByTestId('code-expiry')).toHaveTextContent('The code has expired.')
    expect(live).toHaveTextContent('The code has expired. Send a new code.')
  })

  it('429 → "Too many codes requested. Try again in N min." with a countdown; Send code disabled', async () => {
    server.use(
      http.post(`${ME}/contact-verifications`, () =>
        problemResponse(problem(429, 'too-many-requests', 'Too many requests.'), { 'Retry-After': '1200' }),
      ),
    )
    const { user, dialog } = await openDialog('Add email')
    await user.type(within(dialog).getByLabelText('New email address'), 'ravi@example.com')
    await user.click(within(dialog).getByRole('button', { name: 'Send code' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Too many codes requested. Try again in 20 min.')
    expect(within(dialog).getByTestId('contact-retry-countdown')).toHaveTextContent(/Try again in (20:00|19:5\d)/)
    expect(within(dialog).getByRole('button', { name: 'Send code' })).toBeDisabled()
  })

  it.each([
    [503, 'unavailable', "We couldn't send the code. Try again later."],
    [422, 'contact-in-use', 'This email address is already in use.'],
    [500, 'internal', 'Something went wrong. Please try again.'],
  ])('start %i → root alert with the correlation id', async (status, type, shown) => {
    server.use(
      http.post(`${ME}/contact-verifications`, () =>
        problemResponse(problem(status, type, status === 422 ? shown : 'Server text.')),
      ),
    )
    const { user, dialog } = await openDialog('Add email')
    await user.type(within(dialog).getByLabelText('New email address'), 'ravi@example.com')
    await user.click(within(dialog).getByRole('button', { name: 'Send code' }))
    const alert = await within(dialog).findByRole('alert')
    expect(alert).toHaveTextContent(shown)
    expect(alert).toHaveTextContent(`corr-${status}-${type}`)
  })

  it('start 400 with a value violation → field error', async () => {
    server.use(
      http.post(`${ME}/contact-verifications`, () =>
        problemResponse(problem(400, 'validation', 'Invalid.', { errors: [{ field: 'value', message: 'Value is invalid.' }] })),
      ),
    )
    const { user, dialog } = await openDialog('Add email')
    await user.type(within(dialog).getByLabelText('New email address'), 'ravi@example.com')
    await user.click(within(dialog).getByRole('button', { name: 'Send code' }))
    expect(await within(dialog).findByText('Value is invalid.')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('New email address')).toHaveFocus()
  })

  it('confirm 404 → back to step 1 with the notice and the value kept', async () => {
    serveContacts(() => problemResponse(problem(404, 'not-found', 'This code is no longer valid.')))
    const { user, dialog } = await openDialog('Add email')
    const code = await sendEmailCode(user, dialog)
    await user.type(code, CODE)
    await user.click(within(dialog).getByRole('button', { name: 'Verify' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('This code is no longer valid.')
    expect(within(dialog).getByLabelText('New email address')).toHaveValue('ravi@example.com')
  })

  it('Esc closes the dialog without a request and returns focus to the row button', async () => {
    serveContacts()
    const { user } = await openDialog('Add email')
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Add email' })).toHaveFocus()
    expect(starts).toEqual([])
  })
})
