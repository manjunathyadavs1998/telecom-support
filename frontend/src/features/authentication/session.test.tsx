import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppProviders } from '@/app/AppProviders'
import { registerNavigator } from '@/app/navigation'
import { registerRouter } from '@/app/router'
import { appRoutes } from '@/app/routes'
import { ME_QUERY_KEY, queryClient, shouldRetryQuery } from '@/app/queryClient'
import { authChannel } from '@/services/api/authChannel'
import { ApiError } from '@/services/api/errors'
import { meCustomer, meFixtures, meInbound } from '@/test/fixtures/me'
import { BOOTSTRAP_TOKEN, clearXsrfCookie, setXsrfCookie } from '@/test/msw/handlers'
import { server } from '@/test/msw/server'
import type { Me } from '@/types/api'
import { applySignedIn, applySignedOut, meQuery, resetCsrfBootstrap, useMeQuery } from './meQuery'

const OTHER_KEY = ['other']

function SessionProbe() {
  const { data, status } = useMeQuery()
  if (status === 'pending') return <p>loading</p>
  if (status === 'error') return <p>error</p>
  return <p>{data ? `signed in as ${data.displayName}` : 'anonymous'}</p>
}

const renderApp = () =>
  render(
    <AppProviders>
      <SessionProbe />
    </AppProviders>,
  )

/** Records the order of auth requests and answers `/me` with the current `meResponse`. */
let calls: string[]
let meResponse: Me | null
function recordAuthRequests() {
  server.use(
    http.get('*/api/v1/auth/csrf', () => {
      calls.push('csrf')
      setXsrfCookie(BOOTSTRAP_TOKEN)
      return HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: BOOTSTRAP_TOKEN })
    }),
    http.get('*/api/v1/auth/me', () => {
      calls.push('me')
      return meResponse
        ? HttpResponse.json(meResponse)
        : HttpResponse.json(
            { type: 'https://telecom.example/problems/unauthenticated', title: 'Unauthorized', status: 401 },
            { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
          )
    }),
  )
}

/** Another tab: a second channel with the same name (BroadcastChannel never delivers to its sender). */
function otherTab() {
  const tab = new BroadcastChannel('auth')
  const received: unknown[] = []
  tab.onmessage = (event: MessageEvent) => received.push(event.data)
  return { tab, received }
}

let navigate: ReturnType<typeof vi.fn<(to: string) => unknown>>
let unregister: () => void
let unregisterRouter: (() => void) | null = null

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
beforeEach(() => {
  calls = []
  meResponse = null
  resetCsrfBootstrap()
  recordAuthRequests()
  navigate = vi.fn<(to: string) => unknown>()
  unregister = registerNavigator({ navigate, location: () => ({ pathname: '/login', search: '', hash: '' }) })
})
afterEach(() => {
  cleanup() // unmount first: clearing the cache under a mounted useMeQuery would start a re-bootstrap
  unregister()
  unregisterRouter?.()
  unregisterRouter = null
  vi.useRealTimers()
  server.resetHandlers()
  clearXsrfCookie()
  queryClient.clear()
})
afterAll(() => {
  authChannel.close()
  server.close()
})

describe('bootstrap', () => {
  it('(a) /me 401 resolves to null = anonymous: no error, no redirect, no repeat request', async () => {
    renderApp()

    expect(await screen.findByText('anonymous')).toBeInTheDocument()
    expect(queryClient.getQueryData(ME_QUERY_KEY)).toBeNull()
    expect(calls).toEqual(['csrf', 'me'])
    expect(navigate).not.toHaveBeenCalled()
  })

  it('authenticated: GET /csrf once, then /me fills the cache', async () => {
    meResponse = meCustomer

    renderApp()

    expect(await screen.findByText('signed in as Customer One')).toBeInTheDocument()
    expect(queryClient.getQueryData(ME_QUERY_KEY)).toEqual(meCustomer)
    expect(calls).toEqual(['csrf', 'me'])
  })

  it('/me is fresh for 60 s, refetched on window focus, never polled', () => {
    expect(meQuery.queryKey).toEqual(ME_QUERY_KEY)
    expect(meQuery.staleTime).toBe(60_000)
    expect(meQuery.refetchOnWindowFocus).toBe(true)
    expect(meQuery.refetchInterval).toBeUndefined()
  })
})

/** The app as `main.tsx` mounts it, on a memory router at `path`; records every committed location after the first render. */
async function renderRouterApp(path: string, firstHeading: string) {
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  unregisterRouter = registerRouter(router)
  render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  expect(await screen.findByRole('heading', { level: 1, name: firstHeading })).toBeInTheDocument()
  const locations: string[] = []
  let last = router.state.location.pathname + router.state.location.search
  router.subscribe((state) => {
    const at = state.location.pathname + state.location.search
    if (at !== last) locations.push(at)
    last = at
  })
  return { router, locations }
}

/** `/me` cached more than 60 s ago, so the next check refetches it (§7 Re-validation). */
function makeMeStale() {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(Date.now() + 61_000)
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 50))

describe('re-validation on navigation (§7, XC-02)', () => {
  it('(b) stale /me + 401 on navigation → /login?reason=expired&returnTo=<the page being opened>, redirected exactly once', async () => {
    meResponse = meCustomer
    const { router, locations } = await renderRouterApp('/customer', 'Home')

    makeMeStale()
    meResponse = null
    await act(() => router.navigate('/customer/profile'))
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    await settle()

    expect(locations).toEqual([`/login?reason=expired&returnTo=${encodeURIComponent('/customer/profile')}`])
    expect(screen.getByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
    expect(queryClient.getQueryData(ME_QUERY_KEY) ?? null).toBeNull() // no user left (a re-bootstrap may store null)
    expect(calls.slice(0, 3)).toEqual(['csrf', 'me', 'me'])
    expect(screen.getByRole('status')).toHaveTextContent('Your session expired. Sign in again.')

    // Re-login returns to the page that was being opened.
    vi.useRealTimers()
    server.use(http.post('*/api/v1/auth/login', () => HttpResponse.json(meCustomer)))
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Mobile number or employee ID'), meCustomer.username)
    await user.type(screen.getByLabelText('Password'), 'pw')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Profile' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/customer/profile')
  })

  it('(c) stale /me + 401 on window focus → /login?reason=expired&returnTo=<current page>, exactly once', async () => {
    meResponse = meCustomer
    const { router, locations } = await renderRouterApp('/customer/plan', 'My plan')

    makeMeStale()
    meResponse = null
    act(() => {
      window.dispatchEvent(new Event('visibilitychange'))
    })
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    await settle()

    expect(locations).toEqual([`/login?reason=expired&returnTo=${encodeURIComponent('/customer/plan')}`])
    expect(queryClient.getQueryData(ME_QUERY_KEY) ?? null).toBeNull()
  })

  it('stale /me that is still valid → refetched, no redirect', async () => {
    meResponse = meCustomer
    const { router, locations } = await renderRouterApp('/customer', 'Home')

    makeMeStale()
    await act(() => router.navigate('/customer/profile'))
    expect(await screen.findByRole('heading', { level: 1, name: 'Profile' })).toBeInTheDocument()
    await settle()

    expect(locations).toEqual(['/customer/profile'])
    expect(calls).toEqual(['csrf', 'me', 'me'])
    expect(queryClient.getQueryData(ME_QUERY_KEY)).toEqual(meCustomer)
  })

  it('fresh /me (< 60 s) → navigation makes no /me request', async () => {
    meResponse = meCustomer
    const { router } = await renderRouterApp('/customer', 'Home')

    await act(() => router.navigate('/customer/profile'))
    expect(await screen.findByRole('heading', { level: 1, name: 'Profile' })).toBeInTheDocument()

    expect(calls).toEqual(['csrf', 'me'])
  })
})

describe('login and logout', () => {
  it('login success clears the whole cache, stores the new user and tells the other tabs', async () => {
    const { tab, received } = otherTab()
    queryClient.setQueryData(ME_QUERY_KEY, null)
    queryClient.setQueryData(OTHER_KEY, 'previous user data')

    applySignedIn(queryClient, meInbound)

    expect(queryClient.getQueryData(OTHER_KEY)).toBeUndefined()
    expect(queryClient.getQueryCache().getAll()).toHaveLength(1)
    expect(queryClient.getQueryData(ME_QUERY_KEY)).toEqual(meInbound)
    await waitFor(() => expect(received).toEqual([{ type: 'login' }]))
    tab.close()
  })

  it('logout clears the whole cache and tells the other tabs', async () => {
    const { tab, received } = otherTab()
    queryClient.setQueryData(ME_QUERY_KEY, meCustomer)
    queryClient.setQueryData(OTHER_KEY, 'customer data')

    applySignedOut(queryClient)

    expect(queryClient.getQueryCache().getAll()).toHaveLength(0)
    await waitFor(() => expect(received).toEqual([{ type: 'logout' }]))
    tab.close()
  })
})

describe('cross-tab', () => {
  it.each(['logout', 'login'] as const)(
    '(d) a %s message on BroadcastChannel("auth") clears the cache and re-bootstraps /me',
    async (type) => {
      meResponse = meCustomer
      renderApp()
      expect(await screen.findByText('signed in as Customer One')).toBeInTheDocument()
      queryClient.setQueryData(OTHER_KEY, 'customer data')

      meResponse = type === 'login' ? meInbound : null
      const { tab } = otherTab()
      tab.postMessage({ type })

      expect(await screen.findByText(type === 'login' ? 'signed in as Inbound Agent' : 'anonymous')).toBeInTheDocument()
      expect(queryClient.getQueryData(OTHER_KEY)).toBeUndefined()
      expect(calls).toEqual(['csrf', 'me', 'me'])
      expect(navigate).not.toHaveBeenCalled()
      tab.close()
    },
  )

  it('ignores unknown messages', async () => {
    meResponse = meCustomer
    renderApp()
    expect(await screen.findByText('signed in as Customer One')).toBeInTheDocument()

    const { tab } = otherTab()
    tab.postMessage({ type: 'hello' })
    tab.postMessage('logout')
    await new Promise((resolve) => setTimeout(resolve, 20))

    expect(queryClient.getQueryData(ME_QUERY_KEY)).toEqual(meCustomer)
    expect(calls).toEqual(['csrf', 'me'])
    tab.close()
  })
})

describe('QueryClient defaults', () => {
  const error = (status: number) => new ApiError({ status, problem: null, correlationId: null })

  it('retries queries only on 5xx and network failures, at most twice', () => {
    expect(shouldRetryQuery(0, error(0))).toBe(true)
    expect(shouldRetryQuery(1, error(503))).toBe(true)
    expect(shouldRetryQuery(2, error(500))).toBe(false)
    for (const status of [400, 401, 403, 404, 409, 422, 429]) expect(shouldRetryQuery(0, error(status))).toBe(false)
    expect(shouldRetryQuery(0, new TypeError('bug'))).toBe(false)
  })

  it('never retries mutations', () => {
    expect(queryClient.getDefaultOptions().mutations?.retry).toBe(false)
  })
})

describe('Me fixtures', () => {
  it('cover the 7 role combinations with special roles only alongside their base role', () => {
    const all = Object.values(meFixtures)
    expect(all).toHaveLength(7)
    expect(new Set(all.map((me) => me.roles.join('+'))).size).toBe(7)
    for (const me of all) expect(new Set(me.permissions).size).toBe(me.permissions.length)
  })
})
