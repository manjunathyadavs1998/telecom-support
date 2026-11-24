import { QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { axe } from 'vitest-axe'
import { ME_QUERY_KEY, queryClient } from '@/app/queryClient'
import { registerRouter } from '@/app/router'
import { appRoutes } from '@/app/routes'
import { authChannel } from '@/services/api/authChannel'
import { meCustomer, meInbound } from '@/test/fixtures/me'
import { BOOTSTRAP_TOKEN, clearXsrfCookie, setXsrfCookie } from '@/test/msw/handlers'
import { server } from '@/test/msw/server'
import type { Me } from '@/types/api'
import { resetCsrfBootstrap } from './meQuery'

const PASSWORD = 'S3cret-pass!'
const CORRELATION_ID = '0b9d6a52-4f8e-4c1a-9d3e-7f2a1b3c4d5e'
const problem = (status: number, extra: Record<string, unknown> = {}) =>
  HttpResponse.json(
    { type: 'about:blank', title: 'Error', status, correlationId: CORRELATION_ID, ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json', ...(extra.headers as object) } },
  )

let loginCalls = 0
let csrfCalls = 0
/** Answers `POST /login` with the next response in `responses` (the last one repeats). */
function loginResponds(...responses: (() => Response)[]) {
  server.use(
    http.post('*/api/v1/auth/login', () => {
      const respond = responses[Math.min(loginCalls, responses.length - 1)]!
      loginCalls += 1
      return respond()
    }),
  )
}

let unregister: (() => void) | null = null

/** The app's routes at `path`, signed out (`/me` cached as `null`, fresh, so no bootstrap request). */
function renderLogin(path = '/login', me: Me | null = null) {
  queryClient.setQueryData(ME_QUERY_KEY, me)
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  unregister = registerRouter(router)
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, ...view }
}

const usernameInput = () => screen.getByLabelText('Mobile number or employee ID')
const passwordInput = () => screen.getByLabelText('Password')
const submitButton = () => screen.getByRole('button', { name: 'Sign in' })

async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>, username = '+919800000001') {
  await user.type(usernameInput(), username)
  await user.type(passwordInput(), PASSWORD)
  await user.click(submitButton())
}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
beforeEach(() => {
  loginCalls = 0
  csrfCalls = 0
  resetCsrfBootstrap()
  server.use(
    http.get('*/api/v1/auth/csrf', () => {
      csrfCalls += 1
      setXsrfCookie(`${BOOTSTRAP_TOKEN}-${csrfCalls}`)
      return HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: BOOTSTRAP_TOKEN })
    }),
  )
})
afterEach(() => {
  cleanup()
  unregister?.()
  unregister = null
  vi.useRealTimers()
  server.resetHandlers()
  clearXsrfCookie()
  queryClient.clear()
})
afterAll(() => {
  authChannel.close()
  server.close()
})

describe('validation (frontend.md §6)', () => {
  it('empty submit shows field messages, marks the fields invalid and focuses the first one; no request', async () => {
    const user = userEvent.setup()
    renderLogin()

    await user.click(submitButton())

    expect(await screen.findByText('Enter your mobile number or employee ID')).toBeInTheDocument()
    expect(screen.getByText('Enter your password')).toBeInTheDocument()
    expect(usernameInput()).toHaveAttribute('aria-invalid', 'true')
    expect(usernameInput()).toHaveAccessibleDescription('Enter your mobile number or employee ID')
    expect(passwordInput()).toHaveAttribute('aria-invalid', 'true')
    expect(usernameInput()).toHaveFocus()
    expect(loginCalls).toBe(0)
  })

  it('a whitespace-only username is invalid (trimmed); the password is not trimmed', async () => {
    const user = userEvent.setup()
    renderLogin()
    await user.type(usernameInput(), '   ')
    await user.type(passwordInput(), ' ')
    await user.click(submitButton())

    expect(await screen.findByText('Enter your mobile number or employee ID')).toBeInTheDocument()
    expect(screen.queryByText('Enter your password')).not.toBeInTheDocument()
  })
})

describe('server errors (frontend.md §6)', () => {
  it('400 errors[] map to the fields, unknown fields to the root', async () => {
    loginResponds(() =>
      problem(400, {
        detail: 'Validation failed',
        errors: [
          { field: 'username', message: 'Username is too long' },
          { field: 'other', message: 'ignored' },
        ],
      }),
    )
    const user = userEvent.setup()
    renderLogin()
    await fillAndSubmit(user)

    expect(await screen.findByText('Username is too long')).toBeInTheDocument()
    expect(usernameInput()).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('alert')).toHaveTextContent('Validation failed')
    await waitFor(() => expect(usernameInput()).toHaveFocus())
  })

  it('401 shows the generic root error, focuses it and clears the password', async () => {
    loginResponds(() => problem(401, { detail: 'Bad credentials for this user' }))
    const user = userEvent.setup()
    renderLogin()
    await fillAndSubmit(user)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Invalid username or password.')
    expect(alert).not.toHaveTextContent(CORRELATION_ID)
    await waitFor(() => expect(alert).toHaveFocus())
    expect(passwordInput()).toHaveValue('')
    expect(usernameInput()).toHaveValue('+919800000001')
  })

  it('429 disables submit with a live countdown from Retry-After', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    loginResponds(() => problem(429, { headers: { 'Retry-After': '3' } }))
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderLogin()
    await fillAndSubmit(user)

    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts. Try again in 3 s.')
    expect(submitButton()).toBeDisabled()
    act(() => vi.advanceTimersByTime(1000))
    expect(screen.getByRole('alert')).toHaveTextContent('Try again in 2 s.')
    act(() => vi.advanceTimersByTime(1000))
    expect(screen.getByRole('alert')).toHaveTextContent('Try again in 1 s.')
    expect(submitButton()).toBeDisabled()
    act(() => vi.advanceTimersByTime(1000))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(submitButton()).toBeEnabled()
  })

  it('403 re-bootstraps CSRF and retries once, then succeeds', async () => {
    loginResponds(
      () => problem(403),
      () => HttpResponse.json(meCustomer),
    )
    const user = userEvent.setup()
    const { router } = renderLogin()
    await fillAndSubmit(user)

    await waitFor(() => expect(router.state.location.pathname).toBe('/customer'))
    expect(loginCalls).toBe(2)
    expect(csrfCalls).toBe(2) // the cookie bootstrap before the first POST, then the re-bootstrap
  })

  it('403 twice → generic root error after exactly one retry', async () => {
    loginResponds(() => problem(403))
    const user = userEvent.setup()
    renderLogin()
    await fillAndSubmit(user)

    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong. Please try again.')
    expect(loginCalls).toBe(2)
  })

  it('500 shows the generic error with the correlation id', async () => {
    loginResponds(() => problem(500, { detail: 'internal' }))
    const user = userEvent.setup()
    renderLogin()
    await fillAndSubmit(user)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Something went wrong. Please try again.')
    expect(alert).toHaveTextContent(CORRELATION_ID)
    expect(alert).not.toHaveTextContent('internal')
  })
})

describe('password hygiene (NFR-SEC)', () => {
  it('no mutation (and so no password) stays in the MutationCache after settle', async () => {
    loginResponds(() => problem(401))
    const user = userEvent.setup()
    renderLogin()
    await fillAndSubmit(user)
    await screen.findByRole('alert')

    await waitFor(() => expect(queryClient.getMutationCache().getAll()).toHaveLength(0))
    expect(JSON.stringify(queryClient.getMutationCache().getAll().map((m) => m.state))).not.toContain(PASSWORD)
    expect(passwordInput()).toHaveValue('')
  })

  it.each([
    ['success', () => HttpResponse.json(meCustomer)],
    ['400', () => problem(400, { errors: [{ field: 'username', message: 'Required' }] })],
    ['500', () => problem(500)],
  ])('the password never reaches the query or mutation cache (%s)', async (_name, respond) => {
    loginResponds(respond)
    const user = userEvent.setup()
    renderLogin()
    await fillAndSubmit(user)
    await waitFor(() => expect(loginCalls).toBe(1))
    await waitFor(() => expect(submitButton()).toBeEnabled())

    const snapshot = JSON.stringify([
      queryClient.getQueryCache().getAll().map((q) => [q.queryKey, q.state.data, q.state.error]),
      queryClient.getMutationCache().getAll().map((m) => [m.state, m.options.mutationKey]),
    ])
    expect(snapshot).not.toContain(PASSWORD)
  })

  it('loading state: while the request is pending the form is busy and submit is disabled; no second request', async () => {
    let release: () => void = () => {}
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    server.use(
      http.post('*/api/v1/auth/login', async () => {
        loginCalls += 1
        await gate
        return HttpResponse.json(meCustomer)
      }),
    )
    const user = userEvent.setup()
    renderLogin()
    await fillAndSubmit(user)

    const busy = await screen.findByRole('button', { name: 'Signing in…' })
    expect(busy).toBeDisabled()
    expect(busy.closest('form')).toHaveAttribute('aria-busy', 'true')
    await user.click(busy)
    release()
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Signing in…' })).not.toBeInTheDocument())
    expect(loginCalls).toBe(1)
  })

  it('the password can be shown and hidden', async () => {
    const user = userEvent.setup()
    renderLogin()
    expect(passwordInput()).toHaveAttribute('type', 'password')
    await user.click(screen.getByRole('button', { name: 'Show password' }))
    expect(passwordInput()).toHaveAttribute('type', 'text')
    await user.click(screen.getByRole('button', { name: 'Hide password' }))
    expect(passwordInput()).toHaveAttribute('type', 'password')
  })
})

describe('after login (frontend.md §7)', () => {
  it('clears the cache, stores the user, broadcasts login and honours a safe returnTo', async () => {
    const other = new BroadcastChannel('auth')
    const received: unknown[] = []
    other.onmessage = (event: MessageEvent) => received.push(event.data)
    loginResponds(() => HttpResponse.json(meCustomer))
    queryClient.setQueryData(['previous'], 'previous user data')
    const user = userEvent.setup()
    const { router } = renderLogin(`/login?returnTo=${encodeURIComponent('/customer/profile?tab=1')}`)
    await fillAndSubmit(user)

    await waitFor(() => expect(router.state.location.pathname).toBe('/customer/profile'))
    expect(router.state.location.search).toBe('?tab=1')
    expect(queryClient.getQueryData(['previous'])).toBeUndefined()
    expect(queryClient.getQueryData(ME_QUERY_KEY)).toEqual(meCustomer)
    await waitFor(() => expect(received).toEqual([{ type: 'login' }]))
    other.close()
  })

  it.each(['//evil.example', 'https://evil.example/x', '/\\evil.example', '/login?x=1'])(
    'an unsafe returnTo %s falls back to the landing route',
    async (returnTo) => {
      loginResponds(() => HttpResponse.json(meCustomer))
      const user = userEvent.setup()
      const { router } = renderLogin(`/login?returnTo=${encodeURIComponent(returnTo)}`)
      await fillAndSubmit(user)

      await waitFor(() => expect(router.state.location.pathname).toBe('/customer'))
    },
  )

  it('a safe returnTo the new user may not open falls back to their landing route', async () => {
    loginResponds(() => HttpResponse.json(meInbound))
    const user = userEvent.setup()
    const { router } = renderLogin(`/login?returnTo=${encodeURIComponent('/customer/plan')}`)
    await fillAndSubmit(user, 'IN001')

    await waitFor(() => expect(router.state.location.pathname).toBe('/inbound'))
  })

  it('keyboard only: Tab through the fields and submit with Enter', async () => {
    loginResponds(() => HttpResponse.json(meInbound))
    const user = userEvent.setup()
    const { router } = renderLogin()
    await user.tab()
    expect(usernameInput()).toHaveFocus()
    await user.keyboard('IN001')
    await user.tab()
    expect(passwordInput()).toHaveFocus()
    await user.keyboard(`${PASSWORD}{Enter}`)

    await waitFor(() => expect(router.state.location.pathname).toBe('/inbound'))
  })
})

describe('login page', () => {
  it.each([
    ['expired', 'Your session expired. Sign in again.'],
    ['signed-out', 'You signed out.'],
  ])('?reason=%s shows its banner', (reason, text) => {
    renderLogin(`/login?reason=${reason}`)
    expect(screen.getByRole('status')).toHaveTextContent(text)
  })

  it('no reason → no banner; title and heading', () => {
    renderLogin()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
    expect(document.title).toBe('Sign in · Telecom Support')
  })

  it('an already signed-in user is sent to their landing route', async () => {
    const { router } = renderLogin('/login?returnTo=%2Fcustomer%2Fplan', meInbound)
    await waitFor(() => expect(router.state.location.pathname).toBe('/inbound'))
  })

  it('has no axe violations (with a banner and errors shown)', async () => {
    const user = userEvent.setup()
    const { container } = renderLogin('/login?reason=expired')
    await user.click(submitButton())
    await screen.findByText('Enter your password')
    expect(await axe(container)).toHaveNoViolations()
  })
})
