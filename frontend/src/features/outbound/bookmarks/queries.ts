import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { OWN_FORBIDDEN_HANDLING } from '@/app/queryClient'
import { api, apiClient } from '@/services/api/client'
import type { ApiError } from '@/services/api/errors'
import type { BookmarkPage } from '@/types/api'
import { OUTBOUND_STALE_TIME_MS, outboundKeys } from '../queue/queries'

/** O12 (R12; OUT-06.2–06.4): the actor's bookmarks, newest first; `page` is 1-based here. */
export function useBookmarks(page: number, size = 20) {
  return useQuery<BookmarkPage, ApiError>({
    queryKey: outboundKeys.bookmarks(page),
    queryFn: () => api.call(apiClient.GET('/api/v1/outbound/bookmarks', { params: { query: { page: page - 1, size } } })),
    staleTime: OUTBOUND_STALE_TIME_MS,
    placeholderData: keepPreviousData,
  })
}

/** The queue's notice (§3.4, OQ 5): only `changedCount` is needed, so one row; refetched on focus. */
export function useChangedBookmarks(enabled: boolean) {
  return useQuery<BookmarkPage, ApiError>({
    queryKey: outboundKeys.bookmarks('changed'),
    queryFn: () => api.call(apiClient.GET('/api/v1/outbound/bookmarks', { params: { query: { page: 0, size: 1 } } })),
    enabled,
    staleTime: OUTBOUND_STALE_TIME_MS,
    refetchOnWindowFocus: true,
  })
}

/** O11 remove (R11). */
export function useRemoveBookmark() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (reference: string) =>
      api.call(apiClient.DELETE('/api/v1/outbound/cases/{reference}/bookmark', { params: { path: { reference } } })),
    meta: OWN_FORBIDDEN_HANDLING,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: outboundKeys.bookmarksAll })
      void queryClient.invalidateQueries({ queryKey: outboundKeys.queueAll })
    },
  })
}
