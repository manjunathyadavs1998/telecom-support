/**
 * The only way the SPA talks to the backend (frontend/CLAUDE.md).
 * - same-origin session cookie; CSRF token echoed from the XSRF-TOKEN cookie on mutating requests
 * - X-Correlation-Id on every request; surfaced in errors so users can quote it (NFR-OBS)
 * - RFC 7807 ProblemDetail → ApiError with field errors for forms (CUS-02.4)
 * - optional Idempotency-Key for create/payment calls (CUS-06.6, NFR-REL)
 */

export type FieldError = { field: string; message: string }

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly correlationId?: string
  readonly fieldErrors: FieldError[]

  constructor(status: number, code: string, message: string, correlationId?: string, fieldErrors: FieldError[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.correlationId = correlationId
    this.fieldErrors = fieldErrors
  }
}

type Query = Record<string, string | number | boolean | null | undefined>

export type RequestOptions = {
  query?: Query
  body?: unknown
  /** Pass a stable key per user action (reuse it on retries of the same action). */
  idempotencyKey?: string
  signal?: AbortSignal
}

const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'

function readCookie(name: string): string | undefined {
  return document.cookie
    .split('; ')
    .find((c) => c.startsWith(`${name}=`))
    ?.slice(name.length + 1)
}

async function ensureCsrfToken(): Promise<string | undefined> {
  let token = readCookie(CSRF_COOKIE)
  if (!token) {
    await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })
    token = readCookie(CSRF_COOKIE)
  }
  return token ? decodeURIComponent(token) : undefined
}

function buildUrl(path: string, query?: Query): string {
  if (!query) return path
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value))
  }
  const qs = params.toString()
  return qs ? `${path}?${qs}` : path
}

let unauthorizedHandler: (() => void) | undefined

/** Registered by the app shell: invoked when any call returns 401 (expired session). */
export function onUnauthorized(handler: () => void) {
  unauthorizedHandler = handler
}

async function toApiError(res: Response): Promise<ApiError> {
  const correlationId = res.headers.get('X-Correlation-Id') ?? undefined
  try {
    const problem = (await res.json()) as {
      code?: string
      detail?: string
      title?: string
      correlationId?: string
      errors?: FieldError[]
    }
    return new ApiError(
      res.status,
      problem.code ?? String(res.status),
      problem.detail ?? problem.title ?? 'Request failed',
      problem.correlationId ?? correlationId,
      problem.errors ?? [],
    )
  } catch {
    return new ApiError(res.status, String(res.status), 'The server returned an unexpected response', correlationId)
  }
}

async function request<T>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    'X-Correlation-Id': crypto.randomUUID(),
  }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (method !== 'GET') {
    const token = await ensureCsrfToken()
    if (token) headers[CSRF_HEADER] = token
  }
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey

  let res: Response
  try {
    res = await fetch(buildUrl(path, options.query), {
      method,
      headers,
      credentials: 'same-origin',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new ApiError(0, 'NETWORK_ERROR', 'Cannot reach the server. Check your connection and try again.')
  }

  if (res.status === 401 && path !== '/api/v1/auth/login') unauthorizedHandler?.()
  if (!res.ok) throw await toApiError(res)
  if (res.status === 204) return undefined as T
  const type = res.headers.get('Content-Type') ?? ''
  return (type.includes('json') ? await res.json() : await res.blob()) as T
}

export const http = {
  get: <T>(path: string, options?: Omit<RequestOptions, 'body'>) => request<T>('GET', path, options),
  post: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'body'>) =>
    request<T>('POST', path, { ...options, body }),
  put: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'body'>) =>
    request<T>('PUT', path, { ...options, body }),
  patch: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'body'>) =>
    request<T>('PATCH', path, { ...options, body }),
  delete: <T>(path: string, options?: Omit<RequestOptions, 'body'>) => request<T>('DELETE', path, options),
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}
