/**
 * Origins of external payment pages the browser may be sent to (frontend.md §4 `redirectTo`, OQ 12): a build-time constant,
 * empty in slice 02 (the mock gateway is served same-origin under `/mock-gateway`).
 */
export const PAYMENT_GATEWAY_ORIGINS: readonly string[] = []

/** The browser navigation, behind an object so tests can observe it (jsdom does not navigate). */
export const browserNavigation = {
  assign(url: string): void {
    window.location.assign(url)
  },
}

/**
 * `redirectUrl` resolved against `origin`; accepted only for http(s) and when same-origin or in `PAYMENT_GATEWAY_ORIGINS`
 * (frontend.md §4, F19, NFR-SEC). Anything else (another origin, `javascript:`, `data:`, unparsable) → null.
 */
export function resolveRedirect(
  url: string,
  origin: string = window.location.origin,
  allowed: readonly string[] = PAYMENT_GATEWAY_ORIGINS,
): string | null {
  let target: URL
  try {
    target = new URL(url, origin)
  } catch {
    return null
  }
  if (target.protocol !== 'http:' && target.protocol !== 'https:') return null
  if (target.origin !== origin && !allowed.includes(target.origin)) return null
  return target.href
}

/** frontend.md §4 `redirectTo(url)`: navigates and returns true, or refuses (false) so the caller shows an error state. */
export function redirectTo(url: string): boolean {
  const target = resolveRedirect(url)
  if (target === null) return false
  browserNavigation.assign(target)
  return true
}
