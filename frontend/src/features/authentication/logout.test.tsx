import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { toast } from 'sonner'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { AppProviders } from '@/app/AppProviders'
import { FORBIDDEN_ACTION_MESSAGE, ME_QUERY_KEY, queryClient } from '@/app/queryClient'
import { registerRouter } from '@/app/router'
import { appRoutes } from '@/app/routes'
import { authChannel } from '@/services/api/authChannel'
import { idempotencyStorageKey } from '@/services/api/idempotency'
import { useUiStore } from '@/stores/uiStore'
import { meCustomer, meInbound } from '@/test/fixtures/me'
import { BOOTSTRAP_TOKEN, clearXsrfCookie, setXsrfCookie } from '@/test/msw/handlers'
import { server } from '@/test/msw/server'
import type { Me } from '@/types/api'
import { resetCsrfBootstrap } from './meQuery'
import { LOGOUT_FAILED_MESSAGE, SIGNED_OUT_URL } from './useLogout'

const OTHER_KEY = ['other']
const CORRELATION_ID = '7d0c2f4e-1b2a-4c3d-8e9f-0a1b2c3d4e5f'
const ROTATED_TOKEN = 'rotated-token'
/** A persisted E15 record (02 frontend.md §5: cleared on logout with the query cache) and an unrelated session entry. */
const IDEM_KEY = idempotencyStorageKey('PAYMENT_INITIATE', '/customers/me/payments', 801)
const UNRELATED_KEY = 'unrelated'

const problem = (status: number, extra: Record<string, unknown> = {}) =>
  HttpResponse.json(
    { type: 'https://telecom.example/problems/x', title: 'Error', status, correlationId: CORRELATION_ID, ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )

/** Server state: the current session's user (`null` = none) and every auth request in order. */
let meResponse: Me | null
let calls: string[]
let csrfCount: number
let logoutTokens: (string | null)[]
/** The `POST /logout` responses, in order; the last one repeats. */
let logoutReplies: (() => Response | Promise<Response>)[]

function recordAuthRequests() {
  server.use(
    http.get('*/api/v1/auth/csrf', () => {
      calls.push('csrf')
      csrfCount += 1
      setXsrfCookie(csrfCount > 1 ? ROTATED_TOKEN : BOOTSTRAP_TOKEN)
      return HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: BOOTSTRAP_TOKEN })
    }),
    http.get('*/api/v1/auth/me', () => {
      calls.push('me')
      return meResponse ? HttpResponse.json(meResponse) : problem(401)
    }),
    http.post('*/api/v1/auth/logout', async ({ request }) => {
      calls.push('logout')
      logoutTokens.push(request.headers.get('X-XSRF-TOKEN'))
      const reply = logoutReplies[Math.min(logoutTokens.length - 1, logoutReplies.length - 1)]!
      const response = await reply()
      if (response.status === 204 || response.status === 401) meResponse = null
      return response
    }),
  )
}

const noContent = () => new HttpResponse(null, { status: 204 })

/** Another tab: a second channel with the same name (BroadcastChannel never delivers to its sender). */
function otherTab() {
  const tab = new BroadcastChannel('auth')
  const received: unknown[] = []
  tab.onmessage = (event: MessageEvent) => received.push(event.data)
  return { tab, received }
}

let unregisterRouter: (() => void) | null = null
let tab: BroadcastChannel | null = null

/** The app as `main.tsx` mounts it at `path`; records every committed location after the first page rendered. */
async function renderApp(me: Me, path: string, firstHeading: string) {
  meResponse = me
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  unregisterRouter = registerRouter(router)
  render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  expect(await screen.findByRole('heading', { level: 1, name: firstHeading })).toBeInTheDocument()
  queryClient.setQueryData(OTHER_KEY, 'cached')
  sessionStorage.setItem(IDEM_KEY, JSON.stringify({ key: 'k', bodyHash: 'h', reference: 'PAY-20261125-000007', savedAt: Date.now() }))
  sessionStorage.setItem(UNRELATED_KEY, 'kept')
  const locations: string[] = []
  let last = router.state.location.pathname + router.state.location.search
  router.subscribe((state) => {
    const at = state.location.pathname + state.location.search
    if (at !== last) locations.push(at)
    last = at
  })
  return { router, locations }
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 50))

/** Signed out: one redirect to the signed-out login page, banner shown, cache and E15 records empty, `logout` broadcast. */
async function expectSignedOut(locations: string[], received: unknown[]) {
  expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
  await settle()
  expect(locations).toEqual([SIGNED_OUT_URL])
  expect(screen.getByRole('status')).toHaveTextContent('You signed out.')
  expect(queryClient.getQueryData(OTHER_KEY)).toBeUndefined()
  expect(queryClient.getQueryData(ME_QUERY_KEY) ?? null).toBeNull() // the login page's re-bootstrap may store null
  expect(sessionStorage.getItem(IDEM_KEY)).toBeNull()
  expect(sessionStorage.getItem(UNRELATED_KEY)).toBe('kept')
  await waitFor(() => expect(received).toEqual([{ type: 'logout' }]))
  expect(useUiStore.getState().mobileNavOpen).toBe(false)
}

/** Still signed in: error toast with the correlation id, same page, cache kept, nothing broadcast. */
async function expectStillSignedIn(locations: string[], received: unknown[], correlationId: string | null) {
  expect(await screen.findByText(LOGOUT_FAILED_MESSAGE)).toBeInTheDocument()
  if (correlationId) expect(screen.getByText(`Correlation id: ${correlationId}`)).toBeInTheDocument()
  else expect(screen.getByText(/^Correlation id: [0-9a-f-]{36}$/)).toBeInTheDocument()
  await settle()
  expect(locations).toEqual([])
  expect(queryClient.getQueryData(OTHER_KEY)).toBe('cached')
  expect(sessionStorage.getItem(IDEM_KEY)).not.toBeNull()
  expect(queryClient.getQueryData(ME_QUERY_KEY)).toEqual(meCustomer)
  expect(received).toEqual([])
}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
beforeEach(() => {
  meResponse = null
  calls = []
  csrfCount = 0
  logoutTokens = []
  logoutReplies = [noContent]
  localStorage.clear()
  sessionStorage.clear()
  useUiStore.setState({ sidebarCollapsed: null, mobileNavOpen: false })
  resetCsrfBootstrap()
  toast.dismiss() // Sonner's queue is module state and outlives an unmounted Toaster
  recordAuthRequests()
})
afterEach(() => {
  cleanup() // unmount first: clearing the cache under a mounted useMeQuery would start a re-bootstrap
  tab?.close()
  tab = null
  unregisterRouter?.()
  unregisterRouter = null
  server.resetHandlers()
  clearXsrfCookie()
  queryClient.clear()
})
afterAll(() => {
  authChannel.close()
  server.close()
})

describe('logout entry points (frontend.md §7 Logout; NFR-SEC)', () => {
  it('Sidebar "Logout" → 204: cache cleared, logout broadcast, /login?reason=signed-out', async () => {
    const user = userEvent.setup()
    const { locations } = await renderApp(meCustomer, '/customer', 'Home')
    const other = otherTab()
    tab = other.tab

    await user.click(screen.getByRole('button', { name: 'Logout' }))

    await expectSignedOut(locations, other.received)
    expect(calls.filter((c) => c === 'logout')).toHaveLength(1)
    expect(logoutTokens).toEqual([BOOTSTRAP_TOKEN])
  })

  it('UserMenu "Sign out" → 204: signed out', async () => {
    const user = userEvent.setup()
    const { locations } = await renderApp(meCustomer, '/customer', 'Home')
    const other = otherTab()
    tab = other.tab

    await user.click(screen.getByRole('button', { name: /Customer One/ }))
    await user.click(within(await screen.findByRole('menu')).getByRole('menuitem', { name: 'Sign out' }))

    await expectSignedOut(locations, other.received)
  })

  it('mobile drawer "Logout" → 204: drawer closed and signed out', async () => {
    const user = userEvent.setup()
    const { locations } = await renderApp(meCustomer, '/customer', 'Home')
    const other = otherTab()
    tab = other.tab

    await user.click(screen.getByRole('button', { name: 'Open navigation menu' }))
    const drawer = await screen.findByRole('dialog', { name: 'Navigation' })
    await user.click(within(drawer).getByRole('button', { name: 'Logout' }))

    await expectSignedOut(locations, other.received)
    expect(screen.queryByRole('dialog', { name: 'Navigation' })).not.toBeInTheDocument()
  })

  it('NoAccessPage "Sign out" → 204: signed out', async () => {
    const user = userEvent.setup()
    const { locations } = await renderApp({ ...meInbound, roles: [], permissions: [] }, '/', 'No access')
    const other = otherTab()
    tab = other.tab

    await user.click(screen.getByRole('button', { name: 'Sign out' }))

    await expectSignedOut(locations, other.received)
  })
})

describe('logout responses (frontend.md §7 Logout, 401 handler)', () => {
  it('401 (session already gone) → signed out with exactly one redirect, never reason=expired', async () => {
    logoutReplies = [() => problem(401)]
    const user = userEvent.setup()
    const { locations } = await renderApp(meCustomer, '/customer', 'Home')
    const other = otherTab()
    tab = other.tab

    await user.click(screen.getByRole('button', { name: 'Logout' }))

    await expectSignedOut(locations, other.received)
    expect(locations.some((at) => at.includes('reason=expired'))).toBe(false)
  })

  it('403 (stale CSRF token) → re-bootstraps the token and retries once → 204: signed out', async () => {
    logoutReplies = [() => problem(403), noContent]
    const user = userEvent.setup()
    const { locations } = await renderApp(meCustomer, '/customer', 'Home')
    const other = otherTab()
    tab = other.tab
    calls = []

    await user.click(screen.getByRole('button', { name: 'Logout' }))

    await expectSignedOut(locations, other.received)
    expect(calls.slice(0, 3)).toEqual(['logout', 'csrf', 'logout'])
    expect(logoutTokens).toEqual([BOOTSTRAP_TOKEN, ROTATED_TOKEN])
    expect(screen.queryByText(FORBIDDEN_ACTION_MESSAGE)).not.toBeInTheDocument()
  })

  it('403 twice → retried once only, then handled like 5xx: toast, still signed in', async () => {
    logoutReplies = [() => problem(403)]
    const user = userEvent.setup()
    const { locations } = await renderApp(meCustomer, '/customer', 'Home')
    const other = otherTab()
    tab = other.tab

    await user.click(screen.getByRole('button', { name: 'Logout' }))

    await expectStillSignedIn(locations, other.received, CORRELATION_ID)
    expect(calls.filter((c) => c === 'logout')).toHaveLength(2)
    expect(screen.queryByText(FORBIDDEN_ACTION_MESSAGE)).not.toBeInTheDocument()
  })

  it('500 → error toast with the correlation id; still signed in, cache kept, no navigation', async () => {
    logoutReplies = [() => problem(500)]
    const user = userEvent.setup()
    const { locations } = await renderApp(meCustomer, '/customer', 'Home')
    const other = otherTab()
    tab = other.tab

    await user.click(screen.getByRole('button', { name: 'Logout' }))

    await expectStillSignedIn(locations, other.received, CORRELATION_ID)
    expect(screen.getByRole('heading', { level: 1, name: 'Home' })).toBeInTheDocument()
  })

  it('network failure → error toast with the request correlation id; still signed in', async () => {
    logoutReplies = [() => HttpResponse.error()]
    const user = userEvent.setup()
    const { locations } = await renderApp(meCustomer, '/customer', 'Home')
    const other = otherTab()
    tab = other.tab

    await user.click(screen.getByRole('button', { name: 'Logout' }))

    await expectStillSignedIn(locations, other.received, null)
  })

  it('a second click while the logout is pending joins it: one request, one redirect', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    logoutReplies = [() => gate.then(noContent)]
    const user = userEvent.setup()
    const { locations } = await renderApp(meCustomer, '/customer', 'Home')
    const other = otherTab()
    tab = other.tab

    const logout = screen.getByRole('button', { name: 'Logout' })
    await user.click(logout)
    await user.click(logout)
    await waitFor(() => expect(calls).toContain('logout'))
    act(() => release())

    await expectSignedOut(locations, other.received)
    expect(calls.filter((c) => c === 'logout')).toHaveLength(1)
  })
})

describe('cross-tab sign-out (frontend.md §7 Cross-tab)', () => {
  it('a logout in another tab clears this tab and sends it to /login with returnTo', async () => {
    const { router } = await renderApp(meCustomer, '/customer/plan', 'My plan')
    const other = otherTab()
    tab = other.tab

    meResponse = null // the other tab ended the shared server session
    other.tab.postMessage({ type: 'logout' })

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(router.state.location.search).toBe(`?returnTo=${encodeURIComponent('/customer/plan')}`)
    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
    expect(queryClient.getQueryData(OTHER_KEY)).toBeUndefined()
    expect(sessionStorage.getItem(IDEM_KEY)).toBeNull() // a logout elsewhere is a logout here (02 frontend.md §5, FC3)
    expect(sessionStorage.getItem(UNRELATED_KEY)).toBe('kept')
    expect(calls.filter((c) => c === 'logout')).toHaveLength(0)
  })

  it('a login elsewhere by the same user keeps the E15 records; by another user it clears them (FC3)', async () => {
    await renderApp(meCustomer, '/customer/plan', 'My plan')
    const other = otherTab()
    tab = other.tab

    const mesBefore = calls.filter((c) => c === 'me').length
    other.tab.postMessage({ type: 'login' })
    await waitFor(() => expect(calls.filter((c) => c === 'me').length).toBeGreaterThan(mesBefore))
    await screen.findByRole('heading', { level: 1, name: 'My plan' })
    expect(sessionStorage.getItem(IDEM_KEY)).not.toBeNull()

    meResponse = { ...meCustomer, id: meCustomer.id + 1000, username: '+919800000999' }
    other.tab.postMessage({ type: 'login' })
    await waitFor(() => expect(sessionStorage.getItem(IDEM_KEY)).toBeNull())
    expect(sessionStorage.getItem(UNRELATED_KEY)).toBe('kept')
  })
})
