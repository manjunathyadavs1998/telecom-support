import { http, HttpResponse } from 'msw'
import { customerHandlers } from './customerHandlers'

/** Sets the readable XSRF-TOKEN cookie the way the server's Set-Cookie would in a browser (backend.md §4.2). */
export function setXsrfCookie(value: string) {
  document.cookie = `XSRF-TOKEN=${encodeURIComponent(value)}; path=/`
}

export function clearXsrfCookie() {
  document.cookie = 'XSRF-TOKEN=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT'
}

export const BOOTSTRAP_TOKEN = 'bootstrap-token'

/** Default `/api/v1/auth/*` handlers and the slice 02 customer handlers; tests override per case with `server.use(...)`. */
export const handlers = [
  http.get('*/api/v1/auth/csrf', () => {
    setXsrfCookie(BOOTSTRAP_TOKEN)
    return HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: BOOTSTRAP_TOKEN })
  }),
  ...customerHandlers,
]
