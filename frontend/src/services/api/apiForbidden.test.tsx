import { useMutation, useQuery } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { toast } from 'sonner'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { AppProviders } from '@/app/AppProviders'
import { registerRouter } from '@/app/router'
import { FORBIDDEN_ACTION_MESSAGE, ME_QUERY_KEY, OWN_FORBIDDEN_HANDLING, queryClient } from '@/app/queryClient'
import { resetCsrfBootstrap } from '@/features/authentication/meQuery'
import { ForbiddenPage } from '@/pages/ForbiddenPage'
import { meInbound } from '@/test/fixtures/me'
import { clearXsrfCookie } from '@/test/msw/handlers'
import { server } from '@/test/msw/server'
import { api, apiClient } from './client'
import { ApiError } from './errors'

const CORRELATION_ID = '5f0c2d1e-8a3b-4c7d-9e6f-1a2b3c4d5e6f'
const forbidden = () =>
  HttpResponse.json(
    { type: 'https://telecom.example/problems/forbidden', title: 'Forbidden', status: 403, correlationId: CORRELATION_ID },
    { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
  )

/** A page with a protected action (any mutating endpoint answering 403 stands in for a later slice's action). */
function ActionPage({ ownForbiddenHandling = false }: { ownForbiddenHandling?: boolean }) {
  const action = useMutation({
    mutationFn: () => api.call(apiClient.POST('/api/v1/auth/logout')),
    meta: ownForbiddenHandling ? OWN_FORBIDDEN_HANDLING : undefined,
  })
  return (
    <main>
      <h1>Action page</h1>
      <button type="button" onClick={() => action.mutate()}>
        Do it
      </button>
      {action.isError ? <p>action failed</p> : null}
    </main>
  )
}

/** A page whose query gets a 403 renders `ForbiddenPage` (frontend.md §7). */
function QueryPage() {
  const query = useQuery({ queryKey: ['probe'], queryFn: () => api.call(apiClient.GET('/api/v1/auth/me')) })
  if (query.error instanceof ApiError && query.error.status === 403) return <ForbiddenPage />
  return <p>{query.isPending ? 'loading' : 'loaded'}</p>
}

let unregister: (() => void) | null = null

function renderApp(element: React.ReactNode) {
  // A signed-in user, fresh in the cache, so the bootstrap makes no /me request.
  queryClient.setQueryData(ME_QUERY_KEY, meInbound)
  queryClient.setQueryData(['cases'], ['case 1'])
  const router = createMemoryRouter([{ path: '/page', element }], { initialEntries: ['/page'] })
  unregister = registerRouter(router)
  render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  return router
}

const cacheSnapshot = () =>
  queryClient
    .getQueryCache()
    .getAll()
    .map((query) => [query.queryHash, query.state.data])

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
beforeEach(() => {
  resetCsrfBootstrap()
  toast.dismiss() // Sonner's queue is module state and outlives an unmounted Toaster
  server.use(http.post('*/api/v1/auth/logout', forbidden), http.get('*/api/v1/auth/me', forbidden))
})
afterEach(() => {
  cleanup()
  unregister?.()
  unregister = null
  server.resetHandlers()
  clearXsrfCookie()
  queryClient.clear()
})
afterAll(() => server.close())

describe('403 on an API call (frontend.md §7; XC-02, XC-07)', () => {
  it('mutation 403 → toast with the correlation id; cache unchanged; no navigation', async () => {
    const user = userEvent.setup()
    const router = renderApp(<ActionPage />)
    const before = cacheSnapshot()

    await user.click(screen.getByRole('button', { name: 'Do it' }))

    expect(await screen.findByText(FORBIDDEN_ACTION_MESSAGE)).toBeInTheDocument()
    expect(screen.getByText(`Correlation id: ${CORRELATION_ID}`)).toBeInTheDocument()
    expect(await screen.findByText('action failed')).toBeInTheDocument()
    expect(cacheSnapshot()).toEqual(before)
    expect(queryClient.getQueryData(ME_QUERY_KEY)).toEqual(meInbound)
    expect(router.state.location.pathname).toBe('/page')
  })

  it('the toast never leaks a stack trace or internal server text from the problem body', async () => {
    const internal = 'java.lang.IllegalStateException: boom at com.telecom.secret.Service.run(Service.java:42)'
    server.use(
      http.post('*/api/v1/auth/logout', () =>
        HttpResponse.json(
          {
            type: 'https://telecom.example/problems/forbidden',
            title: 'Forbidden',
            status: 403,
            detail: internal,
            correlationId: CORRELATION_ID,
            stack: internal,
            trace: internal,
            exception: 'com.telecom.secret.Service',
          },
          { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    const user = userEvent.setup()
    renderApp(<ActionPage />)

    await user.click(screen.getByRole('button', { name: 'Do it' }))

    expect(await screen.findByText(`Correlation id: ${CORRELATION_ID}`)).toBeInTheDocument()
    const text = document.body.textContent ?? ''
    for (const leak of ['IllegalStateException', 'com.telecom', 'Service.java', 'stack', 'boom']) {
      expect(text).not.toContain(leak)
    }
  })

  it('a mutation that handles its own 403 (login) gets no toast', async () => {
    const user = userEvent.setup()
    renderApp(<ActionPage ownForbiddenHandling />)

    await user.click(screen.getByRole('button', { name: 'Do it' }))

    expect(await screen.findByText('action failed')).toBeInTheDocument()
    expect(screen.queryByText(FORBIDDEN_ACTION_MESSAGE)).not.toBeInTheDocument()
  })

  it('query 403 → the page renders ForbiddenPage, no toast, no retry', async () => {
    let calls = 0
    server.use(
      http.get('*/api/v1/auth/me', () => {
        calls += 1
        return forbidden()
      }),
    )
    const router = renderApp(<QueryPage />)

    expect(await screen.findByRole('heading', { level: 1, name: 'Access denied' })).toBeInTheDocument()
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.queryByText(FORBIDDEN_ACTION_MESSAGE)).not.toBeInTheDocument()
    expect(calls).toBe(1)
    expect(router.state.location.pathname).toBe('/page')
    await waitFor(() => expect(queryClient.getQueryData(ME_QUERY_KEY)).toEqual(meInbound))
  })
})
