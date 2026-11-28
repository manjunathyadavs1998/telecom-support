import { hashKey, queryOptions, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { useSyncExternalStore } from 'react'
import { ME_QUERY_KEY } from '@/app/queryClient'
import { api, apiClient } from '@/services/api/client'
import { authChannel, type AuthEvent } from '@/services/api/authChannel'
import { bootstrapCsrf } from '@/services/api/csrf'
import { claimIdempotencyRecords, clearIdempotencyRecords } from '@/services/api/idempotency'
import { useInboundSessionStore } from '@/stores/inboundSessionStore'
import type { Me } from '@/types/api'

let csrfBootstrap: Promise<void> | null = null

/** Session bootstrap step 1 (frontend.md §7 Bootstrap): one `GET /auth/csrf` per page load, before the first `/me`. */
export function ensureCsrfBootstrapped(): Promise<void> {
  csrfBootstrap ??= bootstrapCsrf().catch(() => {
    // Network failure: `/me` still runs; mutating requests re-bootstrap the cookie when it is absent (csrf.ts).
  })
  return csrfBootstrap
}

/** Test seam: forget that the CSRF bootstrap ran (a new page load). */
export function resetCsrfBootstrap(): void {
  csrfBootstrap = null
}

/** `GET /auth/me`; a 401 resolves to `null` = anonymous, not an error (the 401 handler ignores it while no user is cached). */
export async function fetchMe(): Promise<Me | null> {
  await ensureCsrfBootstrapped()
  const result = await apiClient.GET('/api/v1/auth/me')
  if (result.response.status === 401) return null
  const me = await api.call(Promise.resolve(result))
  claimIdempotencyRecords(me.id) // records of another user are dropped (02 frontend.md §5, FC3)
  return me
}

/** The session source of truth (§5, §7): `['me']`, 60 s fresh, refetched on window focus, no polling, no 4xx retry. */
export const meQuery = queryOptions({
  queryKey: ME_QUERY_KEY,
  queryFn: fetchMe,
  staleTime: 60_000,
  refetchOnWindowFocus: true,
})

/**
 * `useQuery(meQuery)` that also re-subscribes after `queryClient.clear()`: clear() removes the query without notifying its
 * observers, so without this a mounted component would keep showing the previous user instead of re-bootstrapping.
 */
export function useMeQuery() {
  const client = useQueryClient()
  const cache = client.getQueryCache()
  useSyncExternalStore(
    (onChange) =>
      cache.subscribe((event) => {
        if (event.type === 'removed' && event.query.queryHash === meQueryHash) onChange()
      }),
    () => cache.find({ queryKey: ME_QUERY_KEY, exact: true }),
  )
  return useQuery(meQuery, client)
}

const meQueryHash = hashKey(ME_QUERY_KEY)

/**
 * After a successful login (§7 After login): drop everything cached, store the new user, tell the other tabs. Persisted E15
 * records survive only when the same user signs in again (02 frontend.md §5, FC3).
 */
export function applySignedIn(client: QueryClient, me: Me): void {
  client.clear()
  claimIdempotencyRecords(me.id)
  client.setQueryData(ME_QUERY_KEY, me)
  authChannel.post('login')
}

/**
 * After logout (204 or 401, §7 Logout): drop everything cached, with the persisted E15 idempotency records (02 frontend.md §5),
 * and tell the other tabs.
 */
export function applySignedOut(client: QueryClient): void {
  client.clear()
  clearIdempotencyRecords()
  authChannel.post('logout')
}

/**
 * Another tab logged in or out (§7 Cross-tab): drop everything cached and re-bootstrap `/me`. A logout elsewhere is a logout
 * here, so it also drops the persisted E15 records; after a login elsewhere the `/me` re-bootstrap keeps them only for the same
 * user (02 frontend.md §5, FC3).
 */
export function applySessionChangedElsewhere(client: QueryClient, event: AuthEvent): void {
  client.clear()
  // 03-inbound/frontend.md §5: the memory-only lookup tokens go with the cache (user decision 2026-11-28).
  useInboundSessionStore.getState().clear()
  if (event === 'logout') clearIdempotencyRecords()
  void client.prefetchQuery(meQuery)
}
