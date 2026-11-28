import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { OWN_FORBIDDEN_HANDLING } from '@/app/queryClient'
import type { ListParamsSchema } from '@/components/data/useListParams'
import { requestReferenceSchema } from '@/features/customer/requests/queries'
import { api, apiClient } from '@/services/api/client'
import type { ApiError } from '@/services/api/errors'
import type { AgentCaseCreated, AgentCaseCreateRequest, AgentCaseDetail, AgentCategory, CaseStatus } from '@/types/api'
import { inboundKeys } from '../verificationState'
import { INBOUND_STALE_TIME_MS, withVerification } from '../workspace/queries'

export { useAgentCasesQuery } from '../workspace/queries'

/**
 * I10 case detail (03-inbound/frontend.md §3.7; INB-02.2–02.4): activity oldest first and INTERNAL entries only when the server
 * returns them (BR-04 is server-side). A 404 (other customer or unknown) is not retried.
 */
export function useAgentCaseQuery(customerId: number, reference: string, enabled: boolean) {
  const queryClient = useQueryClient()
  return useQuery<AgentCaseDetail, ApiError>({
    queryKey: inboundKeys.case(customerId, reference),
    queryFn: () =>
      withVerification(queryClient, customerId, () =>
        api.call(
          apiClient.GET('/api/v1/agent/customers/{customerId}/cases/{reference}', {
            params: { path: { customerId, reference } },
          }),
        ),
      ),
    enabled,
    staleTime: INBOUND_STALE_TIME_MS,
  })
}

/** The 8 internal statuses in lifecycle order (03-inbound/frontend.md §3.6, `CaseStatus`). */
export const CASE_STATUSES: readonly CaseStatus[] = [
  'CREATED',
  'ASSIGNED',
  'IN_PROGRESS',
  'PENDING_CUSTOMER',
  'PENDING_EXTERNAL',
  'RESOLVED',
  'CLOSED',
  'REOPENED',
]

/** The I9 sort whitelist; the first is the default and never written to the URL (§3.6, F3). */
export const CASE_SORTS = [
  { value: 'createdAt,desc', label: 'Created newest' },
  { value: 'createdAt,asc', label: 'Created oldest' },
  { value: 'updatedAt,desc', label: 'Updated newest' },
  { value: 'updatedAt,asc', label: 'Updated oldest' },
] as const

/** The case list's URL filters (module constant: stable identity for `useListParams`). */
export const CASE_LIST_PARAMS: ListParamsSchema = {
  reference: requestReferenceSchema,
  status: CASE_STATUSES,
  sort: CASE_SORTS.map((sort) => sort.value),
}

/** I11 categories for an agent (INB-03.2): internal ones only when the server returns them (`case.category.internal`). */
export function useAgentCategoriesQuery(enabled: boolean) {
  return useQuery<AgentCategory[], ApiError>({
    queryKey: inboundKeys.categories(),
    queryFn: () => api.call(apiClient.GET('/api/v1/agent/case-categories')),
    enabled,
    staleTime: INBOUND_STALE_TIME_MS,
  })
}

/** The I12 path, also the idempotency key's scope: concrete, so a key never crosses customers (§3.8). */
export const caseCreatePath = (customerId: number) => `/agent/customers/${customerId}/cases`

export type CreateCaseOnBehalfVariables = { idempotencyKey: string; body: AgentCaseCreateRequest }

/**
 * I12 create on behalf (INB-03, NFR-REL): never optimistic (XC-07). Own 403 handling (§6: the form shows it). A 201 invalidates
 * every case list page of this customer.
 */
export function useCreateCaseOnBehalf(customerId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ idempotencyKey, body }: CreateCaseOnBehalfVariables): Promise<AgentCaseCreated> =>
      withVerification(queryClient, customerId, () =>
        api.call(
          apiClient.POST('/api/v1/agent/customers/{customerId}/cases', {
            params: { path: { customerId }, header: { 'Idempotency-Key': idempotencyKey } },
            body,
          }),
        ),
      ),
    meta: OWN_FORBIDDEN_HANDLING,
    gcTime: 0,
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: inboundKeys.customer(customerId),
        predicate: (query) => {
          const part = query.queryKey[3]
          return Array.isArray(part) && part[0] === 'cases'
        },
      }),
  })
}
