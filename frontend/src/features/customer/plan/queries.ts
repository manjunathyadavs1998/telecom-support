import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, apiClient } from '@/services/api/client'
import type { PlanChange, PlanChangeRequest, PlanOption, Subscription } from '@/types/api'

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

/** Upgrade quotes for one subscription (frontend.md §5). */
export const planOptionsQueryKey = (subscriptionId: number) => ['customer', 'plan-options', subscriptionId] as const

/** E6 `GET /customers/me/subscriptions/{id}/plan-options` (CUS-03.4): eligible upgrades with the quote to echo in E7. */
export async function fetchPlanOptions(subscriptionId: number): Promise<PlanOption[]> {
  return api.call(
    apiClient.GET('/api/v1/customers/me/subscriptions/{subscriptionId}/plan-options', {
      params: { path: { subscriptionId } },
    }),
  )
}

/** Fetched on demand only ("See upgrade options"), so `enabled` follows the user's request. */
export function usePlanOptionsQuery(subscriptionId: number, enabled: boolean) {
  return useQuery({
    queryKey: planOptionsQueryKey(subscriptionId),
    queryFn: () => fetchPlanOptions(subscriptionId),
    staleTime: LIST_STALE_TIME_MS,
    enabled,
  })
}

/** The E7 path, also the idempotency key's scope (frontend.md §4: same body on another subscription → another key). */
export const planChangePath = (subscriptionId: number) => `/customers/me/subscriptions/${subscriptionId}/plan-changes`

/** The E7 body: the quote the customer confirmed, echoed verbatim (server strings, no arithmetic, F8). */
export function planChangeBody(option: PlanOption): PlanChangeRequest {
  return {
    targetPlanCode: option.plan.code,
    expectedEffectiveDate: option.effectiveDate,
    expectedCharge: option.charge,
  }
}

export type SchedulePlanChangeVariables = { subscriptionId: number; idempotencyKey: string; body: PlanChangeRequest }

/** E7 `POST /customers/me/subscriptions/{id}/plan-changes` (CUS-03.5, XC-06): the body echoes the confirmed quote verbatim. */
export async function postPlanChange({
  subscriptionId,
  idempotencyKey,
  body,
}: SchedulePlanChangeVariables): Promise<PlanChange> {
  return api.call(
    apiClient.POST('/api/v1/customers/me/subscriptions/{subscriptionId}/plan-changes', {
      params: { path: { subscriptionId }, header: { 'Idempotency-Key': idempotencyKey } },
      body,
    }),
  )
}

/** Plan change: never optimistic (XC-07); a 201 invalidates the subscriptions and that subscription's options. */
export function useSchedulePlanChange() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: postPlanChange,
    onSuccess: (_change, { subscriptionId }) => {
      void client.invalidateQueries({ queryKey: SUBSCRIPTIONS_QUERY_KEY, exact: true })
      void client.invalidateQueries({ queryKey: planOptionsQueryKey(subscriptionId), exact: true })
    },
  })
}
