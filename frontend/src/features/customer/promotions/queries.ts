import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, apiClient } from '@/services/api/client'
import type { Promotion, PromotionClaim, PromotionClaimRequest } from '@/types/api'

/** Eligible active promotions (frontend.md §5). */
export const PROMOTIONS_QUERY_KEY = ['customer', 'promotions'] as const

/** Lists use a 30 s `staleTime` (frontend.md §5). */
const LIST_STALE_TIME_MS = 30_000

/** E8 `GET /customers/me/promotions` (CUS-04.1/04.2): eligible active offers with per-number claim state (§10 D3). */
export async function fetchPromotions(): Promise<Promotion[]> {
  return api.call(apiClient.GET('/api/v1/customers/me/promotions'))
}

export const promotionsQuery = queryOptions({
  queryKey: PROMOTIONS_QUERY_KEY,
  queryFn: fetchPromotions,
  staleTime: LIST_STALE_TIME_MS,
})

export function usePromotionsQuery() {
  return useQuery(promotionsQuery)
}

/** The E9 path, also the idempotency key's scope (frontend.md §3 Promotions, §4). */
export const promotionClaimPath = (promotionCode: string) => `/customers/me/promotions/${promotionCode}/claims`

export type ClaimPromotionVariables = { promotionCode: string; idempotencyKey: string; body: PromotionClaimRequest }

/** E9 `POST /customers/me/promotions/{code}/claims` (CUS-04.3/04.4, XC-06): one subscription, terms accepted, terms hash echoed. */
export async function postPromotionClaim({
  promotionCode,
  idempotencyKey,
  body,
}: ClaimPromotionVariables): Promise<PromotionClaim> {
  return api.call(
    apiClient.POST('/api/v1/customers/me/promotions/{promotionCode}/claims', {
      params: { path: { promotionCode }, header: { 'Idempotency-Key': idempotencyKey } },
      body,
    }),
  )
}

/** Claim: never optimistic (XC-07); a 201 invalidates the promotions so the per-number state comes from the server. */
export function useClaimPromotion() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: postPromotionClaim,
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: PROMOTIONS_QUERY_KEY, exact: true })
    },
  })
}

/** Invalidates (and so refetches) the promotions, e.g. after a 404/422 on a claim (frontend.md §6). */
export function useRefetchPromotions() {
  const client = useQueryClient()
  return () => void client.invalidateQueries({ queryKey: PROMOTIONS_QUERY_KEY, exact: true })
}

/** "number ending 1234": the last four digits of an MSISDN. */
export const numberEnding = (msisdn: string) => `number ending ${msisdn.slice(-4)}`
