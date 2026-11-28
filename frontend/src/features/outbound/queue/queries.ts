import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api, apiClient } from '@/services/api/client'
import type { ApiError } from '@/services/api/errors'
import { useOutboundQueueStore } from '@/stores/outboundQueueStore'
import type { OutboundCasePage, QueueAgent, QueueCategory, QueueSummary } from '@/types/api'
import { toSearchBody, type QueueParams } from './useQueueParams'

/** Lists use a 30 s `staleTime` (04-outbound/frontend.md §5); the case detail is always fresh. */
export const OUTBOUND_STALE_TIME_MS = 30_000

/**
 * Query keys (§4). The queue key holds the URL filters and the mobile store's `seq`, never the number itself (NFR-PRIV).
 */
export const outboundKeys = {
  all: ['outbound'] as const,
  summary: (window: { from?: string; to?: string }) => ['outbound', 'summary', window] as const,
  queue: (params: QueueParams, mobileSeq: number) => ['outbound', 'queue', params, mobileSeq] as const,
  queueAll: ['outbound', 'queue'] as const,
  case: (reference: string) => ['outbound', 'case', reference] as const,
  categories: ['outbound', 'categories'] as const,
  agents: ['outbound', 'agents'] as const,
  bookmarks: (page: number | 'changed') => ['outbound', 'bookmarks', page] as const,
  bookmarksAll: ['outbound', 'bookmarks'] as const,
}

/** O1 (OUT-01): counts for the scope and the created window (default the last 3 months). */
export function useQueueSummary(window: { from?: string; to?: string }) {
  return useQuery<QueueSummary, ApiError>({
    queryKey: outboundKeys.summary(window),
    queryFn: () =>
      api.call(apiClient.GET('/api/v1/outbound/case-summary', { params: { query: window.from && window.to ? window : {} } })),
    staleTime: OUTBOUND_STALE_TIME_MS,
  })
}

/**
 * O2 (OUT-02): POST so the mobile number is only in the body. The previous page stays while the next loads (paging only:
 * the queue holds no data that a verification loss would remove).
 */
export function useQueueSearch(params: QueueParams) {
  const seq = useOutboundQueueStore((state) => state.seq)
  return useQuery<OutboundCasePage, ApiError>({
    queryKey: outboundKeys.queue(params, seq),
    queryFn: () => {
      const msisdn = useOutboundQueueStore.getState().msisdn
      return api.call(apiClient.POST('/api/v1/outbound/case-searches', { body: toSearchBody(params, msisdn) }))
    },
    staleTime: OUTBOUND_STALE_TIME_MS,
    placeholderData: keepPreviousData,
  })
}

/** O3 (R13): category tree for the filter. */
export function useQueueCategories() {
  return useQuery<QueueCategory[], ApiError>({
    queryKey: outboundKeys.categories,
    queryFn: () => api.call(apiClient.GET('/api/v1/outbound/case-categories')),
    staleTime: 5 * 60_000,
  })
}

/** O4 (R13): the "Assigned to" agents (own team, or all for supervisors); also the reassign targets. */
export function useQueueAgents() {
  return useQuery<QueueAgent[], ApiError>({
    queryKey: outboundKeys.agents,
    queryFn: () => api.call(apiClient.GET('/api/v1/outbound/agents')),
    staleTime: 5 * 60_000,
  })
}
