import { keepPreviousData, useQuery } from '@tanstack/react-query'
import type { ListApiQuery } from '@/components/data/useListParams'
import { api, apiClient } from '@/services/api/client'
import type { PaymentPage } from '@/types/api'

/** Own payments (frontend.md §5): `['customer','payments',params]`; a terminal payment status invalidates the whole prefix. */
export const PAYMENTS_QUERY_KEY = ['customer', 'payments'] as const
export const paymentsQueryKey = (query: ListApiQuery) => [...PAYMENTS_QUERY_KEY, query] as const

/** Lists use a 30 s `staleTime` (frontend.md §5). */
const LIST_STALE_TIME_MS = 30_000

/**
 * E14 `GET /customers/me/payments` (CUS-07, BR-05/06): own payments, newest first. Without `from`/`to` the server applies the
 * last 3 months. There is no reference search on this list. `page` is 0-based here.
 */
export async function fetchPayments({ from, to, page, size }: ListApiQuery): Promise<PaymentPage> {
  const query = { ...(from && to ? { from, to } : {}), page, size }
  return api.call(apiClient.GET('/api/v1/customers/me/payments', { params: { query } }))
}

/**
 * One page of payments; the previous page stays on screen while the next one loads (`keepPreviousData`). Refetched on window
 * focus, so a status the server reconciled meanwhile shows up (CUS-07.5).
 */
export function usePaymentsQuery(query: ListApiQuery) {
  return useQuery({
    queryKey: paymentsQueryKey(query),
    queryFn: () => fetchPayments(query),
    staleTime: LIST_STALE_TIME_MS,
    placeholderData: keepPreviousData,
    refetchOnWindowFocus: 'always',
  })
}
