import { queryOptions, useQuery } from '@tanstack/react-query'
import { api, apiClient } from '@/services/api/client'
import type { Subscription } from '@/types/api'

/** Own subscriptions (frontend.md §5): one cache entry, also used for MSISDN labels on other customer pages. */
export const SUBSCRIPTIONS_QUERY_KEY = ['customer', 'subscriptions'] as const

/** Lists use a 30 s `staleTime` (frontend.md §5). */
const LIST_STALE_TIME_MS = 30_000

/** E5 `GET /customers/me/subscriptions` (CUS-03.1/03.2): plan summary, benefits and any pending change, primary first. */
export async function fetchSubscriptions(): Promise<Subscription[]> {
  return api.call(apiClient.GET('/api/v1/customers/me/subscriptions'))
}

export const subscriptionsQuery = queryOptions({
  queryKey: SUBSCRIPTIONS_QUERY_KEY,
  queryFn: fetchSubscriptions,
  staleTime: LIST_STALE_TIME_MS,
})

export function useSubscriptionsQuery() {
  return useQuery(subscriptionsQuery)
}

/** Primary first (E5 already orders them; a stable sort keeps the server order otherwise). */
export function primaryFirst(subscriptions: readonly Subscription[]): Subscription[] {
  return [...subscriptions].sort((a, b) => Number(b.primary) - Number(a.primary))
}
