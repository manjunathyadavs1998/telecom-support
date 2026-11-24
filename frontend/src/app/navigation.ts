// Navigation seam for code outside React (the API client's 401 handler). The router (F14) registers itself here with
// `registerNavigator({ navigate: (to) => router.navigate(to), location: () => router.state.location })`.
// Navigation goes only through the router, never `location.*` (frontend.md §7).

export interface AppLocation {
  pathname: string
  search: string
  hash: string
}

export interface AppNavigator {
  navigate(to: string): unknown
  location(): AppLocation
}

let current: AppNavigator | null = null

/** Registers the router; returns an unregister function. */
export function registerNavigator(navigator: AppNavigator): () => void {
  current = navigator
  return () => {
    if (current === navigator) current = null
  }
}

/** Navigates through the registered router. Without one (before the router is mounted) it does nothing. */
export function navigateTo(to: string): void {
  void current?.navigate(to)
}

/** The router's current location; before a router is registered, the document's (read only). */
export function currentLocation(): AppLocation {
  if (current) return current.location()
  const { pathname, search, hash } = globalThis.location ?? { pathname: '/', search: '', hash: '' }
  return { pathname, search, hash }
}

export const LOGIN_PATH = '/login'

/** `/login?[reason=…&]returnTo=<target>` (frontend.md §2 guard order, §7 401 handler). */
export function loginUrl(returnTo: string, reason?: 'expired'): string {
  return `${LOGIN_PATH}?${reason ? `reason=${reason}&` : ''}returnTo=${encodeURIComponent(returnTo)}`
}
