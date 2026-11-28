import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import type { ListApiQuery } from '@/components/data/useListParams'
import { api, apiClient } from '@/services/api/client'
import type { ApiError } from '@/services/api/errors'
import type { PaymentPage } from '@/types/api'
import { inboundKeys } from '../verificationState'
import { INBOUND_STALE_TIME_MS, withVerification } from '../workspace/queries'

/**
 * I8 payments of a verified customer (03-inbound/frontend.md §3.5; INB-01.3, BR-05/06): 02 Payment history rules — no dates =
 * the server's 3-month window, 20 per page, newest first. The previous page stays on screen while the next loads.
 */
export function useAgentPaymentsQuery(customerId: number, query: ListApiQuery, enabled: boolean) {
  const queryClient = useQueryClient()
  const { from, to, page, size } = query
  const params = { ...(from && to ? { from, to } : {}), page, size, sort: 'initiatedAt,desc' as const }
  return useQuery<PaymentPage, ApiError>({
    queryKey: inboundKeys.payments(customerId, params),
    queryFn: () =>
      withVerification(queryClient, customerId, () =>
        api.call(
          apiClient.GET('/api/v1/agent/customers/{customerId}/payments', {
            params: { path: { customerId }, query: params },
          }),
        ),
      ),
    enabled,
    staleTime: INBOUND_STALE_TIME_MS,
    placeholderData: keepPreviousData,
  })
}
