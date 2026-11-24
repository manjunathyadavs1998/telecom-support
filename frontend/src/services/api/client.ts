import createClient, { type Middleware } from 'openapi-fetch'
import type { paths } from '@/types/api'
import { currentXsrfToken, XSRF_HEADER } from './csrf'
import { ApiError, toApiError } from './errors'

export const CORRELATION_HEADER = 'X-Correlation-Id'

/** UUID v4 from `crypto.getRandomValues`, which (unlike `crypto.randomUUID`) also works in insecure contexts (NFR-OBS). */
export function newCorrelationId(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6]! & 0x0f) | 0x40
  bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

const SAFE_METHODS = new Set(['GET', 'HEAD'])

export const apiMiddleware: Middleware = {
  async onRequest({ request }) {
    request.headers.set(CORRELATION_HEADER, newCorrelationId())
    if (!SAFE_METHODS.has(request.method)) {
      const token = await currentXsrfToken(request.url)
      if (token === null) request.headers.delete(XSRF_HEADER)
      else request.headers.set(XSRF_HEADER, token)
    }
    return request
  },
  onError({ request, error }) {
    return new ApiError({ status: 0, problem: null, correlationId: request.headers.get(CORRELATION_HEADER), cause: error })
  },
}

// baseUrl: the page origin, i.e. same-origin like '' in the browser; absolute so Node's fetch (tests) resolves it too.
export function createApiClient(baseUrl = globalThis.location?.origin ?? '') {
  // fetch is looked up per call (not captured at creation) so test interceptors such as MSW apply.
  const client = createClient<paths>({ baseUrl, credentials: 'same-origin', fetch: (request) => globalThis.fetch(request) })
  client.use(apiMiddleware)
  return client
}

export const apiClient = createApiClient()

export const api = {
  client: apiClient,
  /** Unwraps an openapi-fetch result: returns `data`, or throws `ApiError` for any non-2xx response. */
  async call<R extends { data?: unknown; error?: unknown; response: Response }>(
    request: Promise<R>,
  ): Promise<Exclude<R['data'], undefined>> {
    const { data, error, response } = await request
    if (!response.ok) throw toApiError(response, error)
    return data as Exclude<R['data'], undefined>
  },
}
