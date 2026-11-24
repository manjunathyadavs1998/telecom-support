import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router'
import { vi } from 'vitest'

export type MockRoute = { method?: string; path: string | RegExp; status?: number; body?: unknown }

/**
 * Minimal fetch mock: first matching route wins. Unmatched calls return 404 ProblemDetail so tests fail loudly.
 * Returns the spy so tests can assert on request bodies/headers.
 */
export function mockApi(routes: MockRoute[]) {
  const spy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString()
    const method = (init?.method ?? 'GET').toUpperCase()
    const route = routes.find(
      (r) => (r.method ?? 'GET').toUpperCase() === method && (typeof r.path === 'string' ? url.split('?')[0] === r.path : r.path.test(url)),
    )
    if (!route) {
      return new Response(JSON.stringify({ code: 'NOT_FOUND', detail: `No mock for ${method} ${url}` }), { status: 404 })
    }
    const status = route.status ?? 200
    return new Response(status === 204 ? null : JSON.stringify(route.body ?? {}), {
      status,
      headers: { 'Content-Type': 'application/json', 'X-Correlation-Id': 'test-correlation' },
    })
  })
  vi.stubGlobal('fetch', spy)
  document.cookie = 'XSRF-TOKEN=test-token; path=/'
  return spy
}

export function testQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
}

/** Render a route tree (or a single element at "/") with Query + Router providers. */
export function renderWithProviders(ui: ReactElement | RouteObject[], { route = '/' }: { route?: string } = {}) {
  const routes: RouteObject[] = Array.isArray(ui) ? ui : [{ path: '*', element: ui }]
  const router = createMemoryRouter(routes, { initialEntries: [route] })
  const client = testQueryClient()
  const result = render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { ...result, router, client }
}

export const customerMe = {
  id: 1,
  username: '+919876543210',
  displayName: 'John Doe',
  userType: 'CUSTOMER',
  customerId: 1,
  teamId: null,
  roles: ['CUSTOMER'],
  permissions: [
    'customer.profile.read',
    'customer.profile.update',
    'plan.read',
    'plan.change',
    'promotion.read',
    'promotion.claim',
    'case.create',
    'case.read',
    'payment.read',
    'payment.initiate',
  ],
}

export const outboundMe = {
  id: 7,
  username: 'outbound1',
  displayName: 'Meera Nair (Outbound)',
  userType: 'STAFF',
  customerId: null,
  teamId: 2,
  roles: ['OUTBOUND_AGENT'],
  permissions: ['case.read', 'case.read.queue', 'case.assign', 'case.status.update', 'case.note.internal', 'case.bookmark', 'analytics.read'],
}
