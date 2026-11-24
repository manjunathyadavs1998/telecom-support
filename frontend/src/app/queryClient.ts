import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/services/api/errors'

/** `/me` is the only session source of truth (frontend.md §5); value `Me | null`, `null` = anonymous. */
export const ME_QUERY_KEY = ['me'] as const

const MAX_QUERY_RETRIES = 2

/** Queries retry only on 5xx or a network failure (`ApiError` status 0), at most twice; never on 4xx (§5). */
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (failureCount >= MAX_QUERY_RETRIES) return false
  return error instanceof ApiError && (error.status === 0 || error.status >= 500)
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: shouldRetryQuery },
      // Mutations never retry and are never optimistic (XC-07).
      mutations: { retry: false },
    },
  })
}

/** The app-wide client; the API client's 401 handler and the auth channel clear this one. */
export const queryClient = createQueryClient()
