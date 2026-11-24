/** Control (C0, DEL, C1) or whitespace characters (frontend.md §7.1). */
// oxlint-disable-next-line no-control-regex -- matching control characters is the point of this check
const UNSAFE_CHARACTERS = /[\u0000-\u001F\u007F-\u009F\s]/

/**
 * returnTo safety against open redirects (frontend.md §7.1, NFR-SEC). `raw` is accepted only if it starts with exactly one
 * `/` (not `//`, not `/\`), has no control or whitespace character, resolves to the same origin, and is not `/login`.
 * Returns `pathname + search + hash` for `router.navigate()`, otherwise `fallback` (the user's role landing page).
 */
export function safeReturnTo(
  raw: string | null | undefined,
  fallback: string,
  origin: string = window.location.origin,
): string {
  if (!raw || !raw.startsWith('/') || raw.startsWith('//') || raw.startsWith('/\\')) return fallback
  if (UNSAFE_CHARACTERS.test(raw)) return fallback
  let url: URL
  try {
    url = new URL(raw, origin)
  } catch {
    return fallback
  }
  if (url.origin !== new URL(origin).origin) return fallback
  if (url.pathname === '/login') return fallback
  return url.pathname + url.search + url.hash
}
