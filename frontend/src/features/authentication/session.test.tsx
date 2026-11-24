import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppProviders } from '@/app/AppProviders'
import { registerNavigator } from '@/app/navigation'
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
