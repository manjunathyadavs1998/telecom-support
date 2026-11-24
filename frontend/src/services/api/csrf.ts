// CSRF (backend.md §4.2): the XSRF-TOKEN cookie is the source of truth. It is read at send time for every mutating request
// and never cached here, because the server rotates it on login and logout.
export const XSRF_COOKIE = 'XSRF-TOKEN'
export const XSRF_HEADER = 'X-XSRF-TOKEN'
export const CSRF_PATH = '/api/v1/auth/csrf'

export function readXsrfCookie(): string | null {
  if (typeof document === 'undefined') return null
  for (const part of document.cookie.split(';')) {
    const [name, ...rest] = part.trim().split('=')
    if (name === XSRF_COOKIE) return decodeURIComponent(rest.join('='))
  }
  return null
}

let inFlight: Promise<void> | null = null

/** Bootstraps the cookie with one shared `GET /auth/csrf`; concurrent callers await the same request. */
export function bootstrapCsrf(base: string | URL = globalThis.location?.href ?? 'http://localhost/'): Promise<void> {
  inFlight ??= fetch(new URL(CSRF_PATH, base), { credentials: 'same-origin' })
    .then(() => undefined)
    .finally(() => {
      inFlight = null
    })
  return inFlight
}

/** The token to send now: the current cookie, bootstrapping it first if it is absent. */
export async function currentXsrfToken(base?: string | URL): Promise<string | null> {
  if (readXsrfCookie() === null) {
    try {
      await bootstrapCsrf(base)
    } catch {
      // Network failure: send without the header; the request itself will fail or get a 403.
    }
  }
  return readXsrfCookie()
}
