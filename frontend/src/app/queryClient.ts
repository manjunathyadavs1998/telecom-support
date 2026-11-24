import { QueryClient } from '@tanstack/react-query'
import { authKeys } from '@/features/authentication/api'
import { ApiError, onUnauthorized } from '@/services/http'

export function createQueryClient() {
  const client = new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        // Never retry client errors (401/403/404/422); retry transient failures once.
        retry: (count, error) => !(error instanceof ApiError && error.status >= 400 && error.status < 500) && count < 1,
      },
    },
  })
  // Session expired anywhere → mark signed out; AppShell then redirects to /login.
  onUnauthorized(() => client.setQueryData(authKeys.me, null))
  return client
}
