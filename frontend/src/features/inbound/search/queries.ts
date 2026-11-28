import { useMutation } from '@tanstack/react-query'
import { api, apiClient } from '@/services/api/client'
import type { CustomerMatch } from '@/types/api'

/**
 * I1 `POST /agent/customer-searches` (INB-01.1, INB-01.4): exact E.164 match on a live line; 0 or 1 item. POST so the number
 * never appears in a URL (NFR-PRIV).
 */
export async function searchCustomer(msisdn: string): Promise<CustomerMatch[]> {
  return api.call(apiClient.POST('/api/v1/agent/customer-searches', { body: { msisdn } }))
}

/** The search mutation: `gcTime: 0`, so the number (its variables) leaves the MutationCache at once (§3.1). */
export function useCustomerSearchMutation() {
  return useMutation({ mutationFn: searchCustomer, gcTime: 0 })
}
