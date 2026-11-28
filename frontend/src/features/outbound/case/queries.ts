import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { OWN_FORBIDDEN_HANDLING } from '@/app/queryClient'
import { api, apiClient } from '@/services/api/client'
import { ApiError } from '@/services/api/errors'
import type {
  CaseActivity,
  CaseProblem,
  ClosureType,
  CaseStatus,
  NoteRequest,
  OutboundCaseDetail,
} from '@/types/api'
import { outboundKeys } from '../queue/queries'

/** The `CaseProblem.reason` of a 409/422, checked at runtime. */
export function problemReason(error: unknown): string | undefined {
  if (!(error instanceof ApiError) || error.problem === null) return undefined
  const reason = (error.problem as CaseProblem).reason
  return typeof reason === 'string' ? reason : undefined
}

/** Invalidate the lists a case write can change (§3.3): queue, summary and bookmarks. */
export function invalidateLists(queryClient: QueryClient) {
  void queryClient.invalidateQueries({ queryKey: outboundKeys.queueAll })
  void queryClient.invalidateQueries({ queryKey: ['outbound', 'summary'] })
  void queryClient.invalidateQueries({ queryKey: outboundKeys.bookmarksAll })
}

/**
 * O5 (R4): always fresh (`staleTime` 0). A 404 (the case left the actor's scope) is shown by the page as not found even when an
 * older copy is cached, so the old details are never displayed (L1; review H1: the query must not remove its own entry).
 */
export function useOutboundCase(reference: string) {
  return useQuery<OutboundCaseDetail, ApiError>({
    queryKey: outboundKeys.case(reference),
    queryFn: () => api.call(apiClient.GET('/api/v1/outbound/cases/{reference}', { params: { path: { reference } } })),
    staleTime: 0,
  })
}

/** Shared mutation options: never optimistic, own 403 handling, the returned detail replaces the cache. */
function useCaseMutation<V>(reference: string, fn: (vars: V) => Promise<OutboundCaseDetail>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: fn,
    meta: OWN_FORBIDDEN_HANDLING,
    onSuccess: (detail) => {
      queryClient.setQueryData(outboundKeys.case(reference), detail)
      invalidateLists(queryClient)
    },
  })
}

/** O6 claim (R5). */
export function useClaimCase(reference: string) {
  return useCaseMutation<void>(reference, () =>
    api.call(apiClient.POST('/api/v1/outbound/cases/{reference}/claim', { params: { path: { reference } } })),
  )
}

/** O7 reassign (R6): `version` as read at submit time. */
export function useReassignCase(reference: string) {
  return useCaseMutation<{ assigneeUserId: number; reason: string; version: number }>(reference, (body) =>
    api.call(apiClient.POST('/api/v1/outbound/cases/{reference}/assignment', { params: { path: { reference } }, body })),
  )
}

/** O8 status change (R7–R9). */
export function useChangeStatus(reference: string) {
  return useCaseMutation<{ toStatus: CaseStatus; remarks: string | null; closureType: ClosureType | null; version: number }>(
    reference,
    (body) =>
      api.call(apiClient.POST('/api/v1/outbound/cases/{reference}/status', { params: { path: { reference } }, body })),
  )
}

/** O9 note (R10), idempotent (`CASE_NOTE_ADD`); 201 → the detail is refetched (the response is the entry only). */
export function useAddNote(reference: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ body, idempotencyKey }: { body: NoteRequest; idempotencyKey: string }): Promise<CaseActivity> =>
      api.call(
        apiClient.POST('/api/v1/outbound/cases/{reference}/notes', {
          params: { path: { reference }, header: { 'Idempotency-Key': idempotencyKey } },
          body,
        }),
      ),
    meta: OWN_FORBIDDEN_HANDLING,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: outboundKeys.case(reference) })
      invalidateLists(queryClient)
    },
  })
}

/** O10 / O11 bookmark toggle (R11); not optimistic. */
export function useBookmarkToggle(reference: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (on: boolean) => {
      const path = { params: { path: { reference } } }
      await api.call(on ? apiClient.PUT('/api/v1/outbound/cases/{reference}/bookmark', path)
        : apiClient.DELETE('/api/v1/outbound/cases/{reference}/bookmark', path))
      return on
    },
    meta: OWN_FORBIDDEN_HANDLING,
    onSuccess: (on) => {
      queryClient.setQueryData<OutboundCaseDetail>(outboundKeys.case(reference), (old) =>
        old ? { ...old, bookmarked: on } : old)
      void queryClient.invalidateQueries({ queryKey: outboundKeys.bookmarksAll })
      void queryClient.invalidateQueries({ queryKey: outboundKeys.queueAll })
    },
  })
}

/** O13 mark seen (R12): 404 (not bookmarked) is ignored. */
export async function markSeen(queryClient: QueryClient, reference: string) {
  try {
    const result = await apiClient.POST('/api/v1/outbound/bookmarks/{reference}/seen', { params: { path: { reference } } })
    if (result.response.ok) void queryClient.invalidateQueries({ queryKey: outboundKeys.bookmarksAll })
  } catch {
    // review L: a network failure only means the "Updated" marker stays until the next open
  }
}
