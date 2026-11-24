import createClient, { type Middleware } from 'openapi-fetch'
import type { paths } from '@/types/api'
import { currentLocation, navigateTo } from '@/app/navigation'
import { ME_QUERY_KEY, queryClient } from '@/app/queryClient'
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
const LOGIN_ROUTE = '/login'
// Login and logout handle their own 401 (frontend.md §7 Login, Logout).
const OWN_401_FLOW = new Set(['POST /api/v1/auth/login', 'POST /api/v1/auth/logout'])

/**
 * The single 401 handler (frontend.md §7). Ignored: login/logout, any request made while on `/login`, and any 401 while no
 * user is cached (the bootstrap `/me` = anonymous). Otherwise the session expired: go to the login page with `returnTo`,
 * then drop every cached query.
 */
function handleUnauthorized(request: Request, sentFrom: string): void {
  if (OWN_401_FLOW.has(`${request.method} ${new URL(request.url).pathname}`)) return
  if (sentFrom === LOGIN_ROUTE) return
  if (!queryClient.getQueryData(ME_QUERY_KEY)) return
  const { pathname, search, hash } = currentLocation()
  navigateTo(`${LOGIN_ROUTE}?reason=expired&returnTo=${encodeURIComponent(pathname + search + hash)}`)
  queryClient.clear()
}

// Where the app was when each request was sent, so the /login rule applies to the request, not to the response time.
const sentFromRoute = new WeakMap<Request, string>()

export const apiMiddleware: Middleware = {
  async onRequest({ request }) {
    sentFromRoute.set(request, currentLocation().pathname)
    request.headers.set(CORRELATION_HEADER, newCorrelationId())
    if (!SAFE_METHODS.has(request.method)) {
      const token = await currentXsrfToken(request.url)
      if (token === null) request.headers.delete(XSRF_HEADER)
      else request.headers.set(XSRF_HEADER, token)
    }
    return request
  },
  onResponse({ request, response }) {
    if (response.status === 401) handleUnauthorized(request, sentFromRoute.get(request) ?? currentLocation().pathname)
    return response
  },
  onError({ request, error }) {
    return new ApiError({ status: 0, problem: null, correlationId: request.headers.get(CORRELATION_HEADER), cause: error })
  },
}

// The contract declares `X-XSRF-TOKEN` as a required header parameter on mutating operations, but the middleware sets it
// from the cookie at send time. This is the one place that reconciles the two: callers' types drop that header.
type XsrfHeader = typeof XSRF_HEADER
type WithoutXsrf<Op> = Op extends { parameters: { header: infer H } }
  ? XsrfHeader extends keyof H
    ? Omit<Op, 'parameters'> & {
        parameters: Omit<Op['parameters'], 'header'> &
          ([keyof Omit<H, XsrfHeader>] extends [never] ? { header?: never } : { header: Omit<H, XsrfHeader> })
      }
    : Op
  : Op
export type ClientPaths = { [P in keyof paths]: { [M in keyof paths[P]]: WithoutXsrf<paths[P][M]> } }

// baseUrl: the page origin, i.e. same-origin like '' in the browser; absolute so Node's fetch (tests) resolves it too.
export function createApiClient(baseUrl = globalThis.location?.origin ?? '') {
  // fetch is looked up per call (not captured at creation) so test interceptors such as MSW apply.
  const client = createClient<ClientPaths>({ baseUrl, credentials: 'same-origin', fetch: (request) => globalThis.fetch(request) })
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
