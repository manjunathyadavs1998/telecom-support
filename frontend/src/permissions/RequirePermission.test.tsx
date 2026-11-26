import { QueryClientProvider, useQuery } from '@tanstack/react-query'
import { cleanup, render, renderHook, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import type { ReactNode } from 'react'
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { createQueryClient, ME_QUERY_KEY, queryClient } from '@/app/queryClient'
import { registerRouter } from '@/app/router'
import { appRoutes, buildAppRoutes } from '@/app/routes'
import { resetCsrfBootstrap } from '@/features/authentication/meQuery'
import { meAnalyst, meCustomer, meFixtures, meInbound, meInboundApprover, meOutbound, meSupervisor } from '@/test/fixtures/me'
import { clearXsrfCookie } from '@/test/msw/handlers'
import { server } from '@/test/msw/server'
import type { Me } from '@/types/api'
import { Can } from './Can'
import { RequirePermission } from './RequirePermission'
import { usePermissions } from './usePermissions'

/** A provider whose `/me` cache already holds `me` (fresh for 60 s, so no request is made). */
function withMe(me: Me | null) {
  const client = createQueryClient()
  client.setQueryData(ME_QUERY_KEY, me)
  return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

describe('usePermissions (BR-10)', () => {
  it('is set from the /me permissions and user type', () => {
    const { result } = renderHook(() => usePermissions(), { wrapper: withMe(meSupervisor) })
    expect([...result.current.permissions].sort()).toEqual([...meSupervisor.permissions].sort())
    expect(result.current.userType).toBe('STAFF')
    expect(result.current.can(['case.assign', 'case.assign.any'])).toBe(true)
    expect(result.current.can(['case.assign.any'], 'CUSTOMER')).toBe(false)
  })

  it('is empty when signed out', () => {
    const { result } = renderHook(() => usePermissions(), { wrapper: withMe(null) })
    expect(result.current.permissions.size).toBe(0)
    expect(result.current.userType).toBeNull()
    expect(result.current.can([])).toBe(false)
  })
})

describe('Can (UI gating only; the server enforces)', () => {
  it('shows children when every permission is held', () => {
    render(
      <Can allOf={['service.reactivate']}>
        <button type="button">Reactivate</button>
      </Can>,
      { wrapper: withMe(meInboundApprover) },
    )
    expect(screen.getByRole('button', { name: 'Reactivate' })).toBeInTheDocument()
  })

  it('hides children when a permission is missing', () => {
    render(
      <Can allOf={['customer.lookup', 'service.reactivate']}>
        <button type="button">Reactivate</button>
      </Can>,
      { wrapper: withMe(meInbound) },
    )
    expect(screen.queryByRole('button', { name: 'Reactivate' })).not.toBeInTheDocument()
  })

  it('renders the fallback instead when given', () => {
    render(
      <Can allOf={['case.assign.any']} fallback={<p>Assign to me only</p>}>
        <button type="button">Assign to anyone</button>
      </Can>,
      { wrapper: withMe(meOutbound) },
    )
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByText('Assign to me only')).toBeInTheDocument()
  })

  it.each([
    ['hides from a STAFF user holding plan.read', meInbound, false],
    ['shows to a CUSTOMER', meCustomer, true],
  ])('checks the user type: %s', (_name, me, visible) => {
    render(
      <Can allOf={['plan.read']} userType="CUSTOMER">
        <p>My plan</p>
      </Can>,
      { wrapper: withMe(me) },
    )
    expect(screen.queryByText('My plan') !== null).toBe(visible)
  })

  it('hides children when signed out', () => {
    render(
      <Can allOf={[]}>
        <p>Anything</p>
      </Can>,
      { wrapper: withMe(null) },
    )
    expect(screen.queryByText('Anything')).not.toBeInTheDocument()
  })
})

describe('routes and guards (frontend.md §2 guard order; XC-02, BR-10)', () => {
  let requests: string[]
  let unregister: (() => void) | null = null
  const onRequest = ({ request }: { request: Request }) => {
    requests.push(new URL(request.url).pathname)
  }

  /** Renders `routes` (default: the app's) at `path` on a memory router registered as the app navigator. */
  function renderAt(path: string, routes: RouteObject[] = appRoutes) {
    const router = createMemoryRouter(routes, { initialEntries: [path] })
    unregister = registerRouter(router)
    render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )
    return router
  }
  const url = (router: ReturnType<typeof renderAt>) => router.state.location.pathname + router.state.location.search

  /** The bootstrap `/me` answer: a user, or 401 = anonymous. */
  function meReturns(me: Me | null) {
    server.use(
      http.get('*/api/v1/auth/me', () =>
        me ? HttpResponse.json(me) : HttpResponse.json({ status: 401, title: 'Unauthorized' }, { status: 401 }),
      ),
    )
  }

  beforeAll(() => {
    server.listen({ onUnhandledFrame: 'error' })
    server.events.on('request:start', onRequest)
  })
  beforeEach(() => {
    requests = []
    resetCsrfBootstrap()
  })
  afterEach(() => {
    cleanup() // unmount first: clearing the cache under a mounted useMeQuery would start a re-bootstrap
    unregister?.()
    unregister = null
    server.resetHandlers()
    clearXsrfCookie()
    queryClient.clear()
  })
  afterAll(() => {
    server.events.removeListener('request:start', onRequest)
    server.close()
  })

  it.each(['/outbound', '/customer/plan?tab=1', '/', '/no-such-page'])(
    'signed out: %s → /login?returnTo=<path> (unknown paths too, so the route space is not revealed)',
    async (path) => {
      meReturns(null)
      const router = renderAt(path)
      await waitFor(() => expect(url(router)).toBe(`/login?returnTo=${encodeURIComponent(path)}`))
      expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument()
    },
  )

  it.each([
    ['CUSTOMER', meCustomer, '/customer', 'Home'],
    ['OUTBOUND_AGENT', meOutbound, '/outbound', 'Case queue'],
    ['BUSINESS_ANALYST', meAnalyst, '/analytics', 'Analytics'],
  ])('signed in as %s: / redirects to the landing route', async (_role, me, landing, title) => {
    meReturns(me)
    const router = renderAt('/')
    expect(await screen.findByRole('heading', { level: 1, name: title })).toBeInTheDocument()
    expect(url(router)).toBe(landing)
  })

  it('a user with no nav item gets NoAccessPage at /', async () => {
    meReturns({ ...meInbound, roles: [], permissions: [] })
    const router = renderAt('/')
    expect(await screen.findByText('No features are assigned to your account')).toBeInTheDocument()
    expect(url(router)).toBe('/')
  })

  it.each([
    ['a STAFF user on a customer page (user type)', meInbound, '/customer/plan', '/inbound'],
    ['a CUSTOMER on a staff page (permission)', meCustomer, '/audit', '/customer'],
    ['an outbound agent on audit (permission)', meOutbound, '/audit', '/outbound'],
  ])('forbidden: %s → ForbiddenPage at the unchanged URL', async (_name, me, path, landing) => {
    queryClient.setQueryData(ME_QUERY_KEY, me)
    const router = renderAt(path)
    expect(await screen.findByRole('heading', { level: 1, name: 'Access denied' })).toBeInTheDocument()
    expect(url(router)).toBe(path)
    expect(screen.getByRole('link', { name: 'Go to your home page' })).toHaveAttribute('href', landing)
    expect(requests).toEqual([])
  })

  it('forbidden: the guarded page never mounts, so no request is made for its data', async () => {
    queryClient.setQueryData(ME_QUERY_KEY, meOutbound)
    function AuditData() {
      useQuery({ queryKey: ['audit'], queryFn: () => fetch('/api/v1/audit-events').then((r) => r.json()) })
      return <p>audit data</p>
    }
    renderAt('/audit', [
      {
        path: '/audit',
        element: (
          <RequirePermission allOf={['audit.read']}>
            <AuditData />
          </RequirePermission>
        ),
      },
    ])
    expect(await screen.findByRole('heading', { level: 1, name: 'Access denied' })).toBeInTheDocument()
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(screen.queryByText('audit data')).not.toBeInTheDocument()
    expect(requests).toEqual([])
  })

  it('allowed: a registry route renders its page for a permitted user', async () => {
    queryClient.setQueryData(ME_QUERY_KEY, meOutbound)
    const router = renderAt('/outbound/bookmarks')
    expect(await screen.findByRole('heading', { level: 1, name: 'Bookmarks' })).toBeInTheDocument()
    expect(url(router)).toBe('/outbound/bookmarks')
  })

  it('unknown path while signed in → NotFoundPage at that URL', async () => {
    queryClient.setQueryData(ME_QUERY_KEY, meInbound)
    const router = renderAt('/no-such-page')
    expect(await screen.findByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument()
    expect(url(router)).toBe('/no-such-page')
  })

  // frontend.md §2 route table: every customer route, child routes included.
  const REF = 'PAY-20261125-000007'
  const CUSTOMER_ROUTES = [
    '/customer',
    '/customer/profile',
    '/customer/plan',
    '/customer/promotions',
    '/customer/requests',
    '/customer/requests/new',
    '/customer/payments',
    '/customer/payments/pay',
    `/customer/payments/${REF}`,
    `/customer/payments/${REF}/receipt`,
  ]

  it.each(CUSTOMER_ROUTES)('IN001 (holds plan.read, payment.read, …) on %s → ForbiddenPage, no API request', async (path) => {
    queryClient.setQueryData(ME_QUERY_KEY, meFixtures.IN001)
    const router = renderAt(path)
    expect(await screen.findByRole('heading', { level: 1, name: 'Access denied' })).toBeInTheDocument()
    expect(url(router)).toBe(path)
    expect(requests).toEqual([])
  })

  it.each([
    ['/customer/requests/new', 'New request'],
    ['/customer/payments/pay', 'Pay bill'],
    [`/customer/payments/${REF}`, REF],
    [`/customer/payments/${REF}/receipt`, 'Receipt'],
  ])('a customer on the child route %s gets its page (%s)', async (path, title) => {
    queryClient.setQueryData(ME_QUERY_KEY, meCustomer)
    const router = renderAt(path)
    expect(await screen.findByRole('heading', { level: 1, name: title })).toBeInTheDocument()
    expect(url(router)).toBe(path)
  })

  it.each([
    ['/customer/payments/pay', 'payment.initiate'],
    ['/customer/requests/new', 'case.create'],
  ])('a child route adds its own permissions: %s without %s → ForbiddenPage', async (path, missing) => {
    queryClient.setQueryData(ME_QUERY_KEY, { ...meCustomer, permissions: meCustomer.permissions.filter((p) => p !== missing) })
    renderAt(path)
    expect(await screen.findByRole('heading', { level: 1, name: 'Access denied' })).toBeInTheDocument()
    expect(requests).toEqual([])
  })

  it.each(['/customer/payments/PAY-1', '/customer/payments/pay-20261125-000007', '/customer/payments/nope/receipt'])(
    'a malformed :reference (%s) → NotFoundPage with no API call',
    async (path) => {
      queryClient.setQueryData(ME_QUERY_KEY, meCustomer)
      const router = renderAt(path)
      expect(await screen.findByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument()
      expect(url(router)).toBe(path)
      expect(requests).toEqual([])
    },
  )

  describe('a lazy registry page sits under the guard (NFR-PERF)', () => {
    function PaymentData() {
      const { data } = useQuery({
        queryKey: ['customer', 'payment', REF],
        queryFn: () => fetch(`/api/v1/customer/payments/${REF}`).then((r) => r.json() as Promise<{ status: string }>),
      })
      return <h1>Status {data?.status ?? '…'}</h1>
    }
    const routes = () => buildAppRoutes({ 'customer-payment-status': () => Promise.resolve({ Component: PaymentData }) })

    beforeEach(() => {
      server.use(http.get('*/api/v1/customer/payments/:reference', () => HttpResponse.json({ status: 'PENDING' })))
    })

    it('a customer gets the lazy page', async () => {
      queryClient.setQueryData(ME_QUERY_KEY, meCustomer)
      renderAt(`/customer/payments/${REF}`, routes())
      expect(await screen.findByRole('heading', { level: 1, name: 'Status PENDING' })).toBeInTheDocument()
      expect(requests).toEqual([`/api/v1/customer/payments/${REF}`])
    })

    it('IN001 gets ForbiddenPage; the lazy page never mounts and makes no request', async () => {
      queryClient.setQueryData(ME_QUERY_KEY, meFixtures.IN001)
      renderAt(`/customer/payments/${REF}`, routes())
      expect(await screen.findByRole('heading', { level: 1, name: 'Access denied' })).toBeInTheDocument()
      await new Promise((resolve) => setTimeout(resolve, 20))
      expect(screen.queryByRole('heading', { name: /^Status/ })).not.toBeInTheDocument()
      expect(requests).toEqual([])
    })
  })
})
