import { MutationCache, QueryClient, type Mutation } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ApiError } from '@/services/api/errors'

/** `/me` is the only session source of truth (frontend.md §5); value `Me | null`, `null` = anonymous. */
export const ME_QUERY_KEY = ['me'] as const

const MAX_QUERY_RETRIES = 2

export const FORBIDDEN_ACTION_MESSAGE = "You don't have permission to do that."

/**
 * Mutation `meta` flag for a mutation that shows its own 403 (login: root form error after the CSRF retry), so the global
 * forbidden toast stays out of the way.
 */
export const OWN_FORBIDDEN_HANDLING = { ownForbiddenHandling: true } as const

/** Queries retry only on 5xx or a network failure (`ApiError` status 0), at most twice; never on 4xx (§5). */
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (failureCount >= MAX_QUERY_RETRIES) return false
  return error instanceof ApiError && (error.status === 0 || error.status >= 500)
}

/** An error toast with a user-safe message and, when known, the correlation id (NFR-OBS); never the mutation variables. */
export function showErrorToast(message: string, correlationId: string | null | undefined): void {
  toast.error(message, correlationId ? { description: `Correlation id: ${correlationId}` } : undefined)
}

/**
 * 403 on a mutation (frontend.md §7 "403 on an API call", XC-02, XC-07): a toast with the correlation id; no cache change and
 * no navigation. Query 403s are not handled here: the page renders `ForbiddenPage` (or `ErrorState`), no toast.
 */
export function onMutationError(error: unknown, mutation: Pick<Mutation<unknown, unknown, unknown>, 'meta'>): void {
  if (!(error instanceof ApiError) || error.status !== 403) return
  if (mutation.meta?.ownForbiddenHandling === true) return
  showErrorToast(FORBIDDEN_ACTION_MESSAGE, error.correlationId)
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    mutationCache: new MutationCache({
      onError: (error, _variables, _onMutateResult, mutation) => onMutationError(error, mutation),
    }),
    defaultOptions: {
      queries: { retry: shouldRetryQuery },
      // Mutations never retry and are never optimistic (XC-07).
      mutations: { retry: false },
    },
  })
}

/** The app-wide client; the API client's 401 handler and the auth channel clear this one. */
export const queryClient = createQueryClient()
