import { http, HttpResponse } from 'msw'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '@/test/msw/server'
import { BOOTSTRAP_TOKEN, clearXsrfCookie, setXsrfCookie } from '@/test/msw/handlers'
import { api } from './client'
import { ApiError, applyProblemToForm, GENERIC_ERROR_MESSAGE } from './errors'

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const CREDENTIALS = { username: '+919876543210', password: 'secret' }
// The contract marks X-XSRF-TOKEN as a required header parameter; the middleware always replaces this placeholder.
const XSRF_PLACEHOLDER = { header: { 'X-XSRF-TOKEN': 'placeholder' } }

const ME = {
  id: 1,
  username: '+919876543210',
  displayName: 'Asha',
  userType: 'CUSTOMER',
  teamId: null,
  customerId: null,
  roles: ['CUSTOMER'],
  permissions: ['customer.profile.read'],
}

function problem(status: number, type: string, extra: Record<string, unknown> = {}) {
  return {
    type: `https://telecom.example/problems/${type}`,
    title: type,
    status,
    detail: `${type} detail`,
    correlationId: `corr-${status}`,
    ...extra,
  }
}

function problemResponse(status: number, type: string, extra: Record<string, unknown> = {}, headers: Record<string, string> = {}) {
  return HttpResponse.json(problem(status, type, extra), {
    status,
    headers: { 'Content-Type': 'application/problem+json', 'X-Correlation-Id': `corr-${status}`, ...headers },
  })
}

const login = () => api.call(api.client.POST('/api/v1/auth/login', { params: XSRF_PLACEHOLDER, body: CREDENTIALS }))
const logout = () => api.call(api.client.POST('/api/v1/auth/logout', { params: XSRF_PLACEHOLDER }))
const me = () => api.call(api.client.GET('/api/v1/auth/me'))

async function caught(promise: Promise<unknown>): Promise<ApiError> {
  const error = await promise.then(
    () => {
      throw new Error('expected the call to fail')
    },
    (e: unknown) => e,
  )
  expect(error).toBeInstanceOf(ApiError)
  return error as ApiError
}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
beforeEach(() => setXsrfCookie('token-1'))
afterEach(() => {
  server.resetHandlers()
  clearXsrfCookie()
})
afterAll(() => server.close())

describe('CSRF header', () => {
  it('sends the current cookie on POST and picks up the value rotated by login', async () => {
    const sent: (string | null)[] = []
    server.use(
      http.post('*/api/v1/auth/login', ({ request }) => {
        sent.push(request.headers.get('X-XSRF-TOKEN'))
        setXsrfCookie('token-2') // login rotates the cookie (backend.md §4.2)
        return HttpResponse.json(ME)
      }),
      http.post('*/api/v1/auth/logout', ({ request }) => {
        sent.push(request.headers.get('X-XSRF-TOKEN'))
        return new HttpResponse(null, { status: 204 })
      }),
    )

    await expect(login()).resolves.toEqual(ME)
    await logout()

    expect(sent).toEqual(['token-1', 'token-2'])
  })

  it('is absent on GET', async () => {
    let header: string | null = 'unset'
    server.use(
      http.get('*/api/v1/auth/me', ({ request }) => {
        header = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json(ME)
      }),
    )

    await me()

    expect(header).toBeNull()
  })

  it('bootstraps a missing cookie with one shared GET /auth/csrf before mutating requests', async () => {
    clearXsrfCookie()
    let csrfCalls = 0
    const sent: (string | null)[] = []
    server.use(
      http.get('*/api/v1/auth/csrf', async () => {
        csrfCalls++
        await new Promise((resolve) => setTimeout(resolve, 10))
        setXsrfCookie(BOOTSTRAP_TOKEN)
        return HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: BOOTSTRAP_TOKEN })
      }),
      http.post('*/api/v1/auth/logout', ({ request }) => {
        sent.push(request.headers.get('X-XSRF-TOKEN'))
        return new HttpResponse(null, { status: 204 })
      }),
    )

    await Promise.all([logout(), logout()])

    expect(csrfCalls).toBe(1)
    expect(sent).toEqual([BOOTSTRAP_TOKEN, BOOTSTRAP_TOKEN])
  })
})

describe('X-Correlation-Id', () => {
  it('is a fresh v4 UUID with crypto.randomUUID undefined', async () => {
    const original = Object.getOwnPropertyDescriptor(crypto, 'randomUUID')
    Object.defineProperty(crypto, 'randomUUID', { value: undefined, configurable: true })
    const ids: (string | null)[] = []
    server.use(
      http.get('*/api/v1/auth/me', ({ request }) => {
        ids.push(request.headers.get('X-Correlation-Id'))
        return HttpResponse.json(ME)
      }),
    )

    try {
      await me()
      await me()
    } finally {
      if (original) Object.defineProperty(crypto, 'randomUUID', original)
      else delete (crypto as { randomUUID?: unknown }).randomUUID
    }

    expect(ids).toHaveLength(2)
    expect(ids[0]).toMatch(UUID_V4)
    expect(ids[1]).toMatch(UUID_V4)
    expect(ids[0]).not.toBe(ids[1])
  })
})

describe('ApiError mapping', () => {
  it('maps a 400 ProblemDetail with field errors', async () => {
    const errors = [{ field: 'username', message: 'Enter your mobile number or employee ID' }]
    server.use(http.post('*/api/v1/auth/login', () => problemResponse(400, 'validation', { errors })))

    const error = await caught(login())

    expect(error.status).toBe(400)
    expect(error.problem?.type).toBe('https://telecom.example/problems/validation')
    expect(error.problem?.detail).toBe('validation detail')
    expect(error.message).toBe('validation detail')
    expect(error.correlationId).toBe('corr-400')
    expect(error.fieldErrors).toEqual(errors)
    expect(error.retryAfterSec).toBeUndefined()
  })

  it.each([
    [401, 'unauthenticated'],
    [403, 'forbidden'],
    [500, 'internal'],
  ])('maps %i %s', async (status, type) => {
    server.use(http.post('*/api/v1/auth/login', () => problemResponse(status, type)))

    const error = await caught(login())

    expect(error.status).toBe(status)
    expect(error.problem?.type).toBe(`https://telecom.example/problems/${type}`)
    expect(error.message).toBe(`${type} detail`)
    expect(error.correlationId).toBe(`corr-${status}`)
    expect(error.fieldErrors).toEqual([])
  })

  it('parses Retry-After on 429', async () => {
    server.use(
      http.post('*/api/v1/auth/login', () => problemResponse(429, 'too-many-requests', {}, { 'Retry-After': '42' })),
    )

    const error = await caught(login())

    expect(error.status).toBe(429)
    expect(error.retryAfterSec).toBe(42)
    expect(error.problem?.type).toBe('https://telecom.example/problems/too-many-requests')
  })

  it('handles a non-JSON error body: no problem, generic message, correlation id from the header', async () => {
    server.use(
      http.get(
        '*/api/v1/auth/me',
        () =>
          new HttpResponse('<html>Bad Gateway</html>', {
            status: 502,
            headers: { 'Content-Type': 'text/html', 'X-Correlation-Id': 'gateway-id' },
          }),
      ),
    )

    const error = await caught(me())

    expect(error.status).toBe(502)
    expect(error.problem).toBeNull()
    expect(error.message).toBe(GENERIC_ERROR_MESSAGE)
    expect(error.correlationId).toBe('gateway-id')
  })

  it('handles a JSON error body that is not a ProblemDetail', async () => {
    server.use(http.get('*/api/v1/auth/me', () => HttpResponse.json({ oops: true }, { status: 500 })))

    const error = await caught(me())

    expect(error.status).toBe(500)
    expect(error.problem).toBeNull()
    expect(error.message).toBe(GENERIC_ERROR_MESSAGE)
  })

  it('turns a network failure into status 0 with the correlation id that was sent', async () => {
    let sentId: string | null = null
    server.use(
      http.get('*/api/v1/auth/me', ({ request }) => {
        sentId = request.headers.get('X-Correlation-Id')
        return HttpResponse.error()
      }),
    )

    const error = await caught(me())

    expect(error.status).toBe(0)
    expect(error.problem).toBeNull()
    expect(error.message).toBe(GENERIC_ERROR_MESSAGE)
    expect(error.correlationId).toMatch(UUID_V4)
    expect(error.correlationId).toBe(sentId)
  })
})

describe('applyProblemToForm', () => {
  const apiError = (status: number, type: string, extra: Record<string, unknown> = {}) =>
    new ApiError({ status, problem: problem(status, type, extra), correlationId: `corr-${status}` })

  it('maps known fields to the field and the rest to root.server', () => {
    const setError = vi.fn()
    const error = apiError(400, 'validation', {
      errors: [
        { field: 'username', message: 'Required' },
        { field: 'unknown', message: 'Bad' },
      ],
    })

    applyProblemToForm(error, setError, ['username', 'password'])

    expect(setError).toHaveBeenCalledWith('username', { type: 'server', message: 'Required' })
    expect(setError).toHaveBeenCalledWith('root.server', { type: 'server', message: 'validation detail' })
    expect(setError).toHaveBeenCalledTimes(2)
  })

  it('maps only fields when every field error is known', () => {
    const setError = vi.fn()

    applyProblemToForm(apiError(400, 'validation', { errors: [{ field: 'password', message: 'Required' }] }), setError, [
      'username',
      'password',
    ])

    expect(setError).toHaveBeenCalledExactlyOnceWith('password', { type: 'server', message: 'Required' })
  })

  it('puts an error without field errors on root.server', () => {
    const setError = vi.fn()

    applyProblemToForm(apiError(401, 'unauthenticated'), setError, ['username'])

    expect(setError).toHaveBeenCalledExactlyOnceWith('root.server', { type: 'server', message: 'unauthenticated detail' })
  })

  it('uses the generic message for a non-ApiError', () => {
    const setError = vi.fn()

    applyProblemToForm(new TypeError('boom'), setError, ['username'])

    expect(setError).toHaveBeenCalledExactlyOnceWith('root.server', { type: 'server', message: GENERIC_ERROR_MESSAGE })
  })
})
