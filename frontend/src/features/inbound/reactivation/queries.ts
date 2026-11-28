import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import type { ListApiQuery } from '@/components/data/useListParams'
import { api, apiClient } from '@/services/api/client'
import type { ApiError } from '@/services/api/errors'
import type { ReactivationEligibility, ReactivationPage, ReactivationStatus } from '@/types/api'
import { inboundKeys } from '../verificationState'
import { INBOUND_STALE_TIME_MS, withVerification } from '../workspace/queries'

/** The id of a history row, so an "already open" notice can link to it (§3.9). */
export const historyRowId = (reference: string) => `reactivation-${reference}`

/** OQ 3: History refetches every 15 s while page 1 holds a PENDING or EXPIRING row. */
export const HISTORY_POLL_MS = 15_000
const IN_FLIGHT: ReadonlySet<ReactivationStatus> = new Set(['PENDING', 'EXPIRING'])

/** I13 eligibility (03-inbound/frontend.md §3.9; INB-04.1/04.2/04.6): `service.reactivate` holders only. */
export function useEligibilityQuery(customerId: number, enabled: boolean) {
  const queryClient = useQueryClient()
  return useQuery<ReactivationEligibility, ApiError>({
    queryKey: inboundKeys.eligibility(customerId),
    queryFn: () =>
      withVerification(queryClient, customerId, () =>
        api.call(
          apiClient.GET('/api/v1/agent/customers/{customerId}/reactivation-eligibility', {
            params: { path: { customerId } },
          }),
        ),
      ),
    enabled,
    staleTime: 0,
  })
}

/** True while a row of this page is still in flight (PENDING / EXPIRING). */
export function hasInFlight(page: ReactivationPage | undefined): boolean {
  return page?.items.some((item) => IN_FLIGHT.has(item.status)) ?? false
}

/**
 * I15 history (every inbound agent; INB-04.4/04.5): 3-month default window, 20 per page. Polls every 15 s while page 1 holds
 * a PENDING or EXPIRING row, and refetches on window focus (OQ 3).
 */
export function useReactivationHistoryQuery(customerId: number, query: ListApiQuery, enabled: boolean) {
  const queryClient = useQueryClient()
  const { from, to, page, size } = query
  const params = { ...(from && to ? { from, to } : {}), page, size }
  return useQuery<ReactivationPage, ApiError>({
    queryKey: inboundKeys.reactivations(customerId, params),
    queryFn: () =>
      withVerification(queryClient, customerId, () =>
        api.call(
          apiClient.GET('/api/v1/agent/customers/{customerId}/reactivations', {
            params: { path: { customerId }, query: params },
          }),
        ),
      ),
    enabled,
    staleTime: INBOUND_STALE_TIME_MS,
    placeholderData: keepPreviousData,
    refetchOnWindowFocus: true,
    refetchInterval: (current) => (page === 0 && hasInFlight(current.state.data) ? HISTORY_POLL_MS : false),
  })
}

/** Refetch eligibility and every history page of the customer (after a create, or when a pending row settles). */
export function invalidateReactivation(queryClient: ReturnType<typeof useQueryClient>, customerId: number) {
  void queryClient.invalidateQueries({ queryKey: inboundKeys.eligibility(customerId) })
  void queryClient.invalidateQueries({
    queryKey: inboundKeys.customer(customerId),
    predicate: (query) => {
      const part = query.queryKey[3]
      return Array.isArray(part) && part[0] === 'reactivations'
    },
  })
}
