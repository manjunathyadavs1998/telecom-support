import { QueryClientProvider } from '@tanstack/react-query'
import { useEffect, type ReactNode } from 'react'
import { applySessionChangedElsewhere, meQuery } from '@/features/authentication/meQuery'
import { authChannel } from '@/services/api/authChannel'
import { queryClient } from './queryClient'

/**
 * App-wide providers and the session bootstrap (frontend.md §7): `GET /auth/csrf` once, then `/me` (meQuery awaits the
 * CSRF step). A `login`/`logout` message from another tab clears the cache and re-bootstraps `/me`. Always the app-wide
 * `queryClient`, because the API client's 401 handler clears that one.
 */
export function AppProviders({ children }: { children: ReactNode }) {
  useEffect(() => {
    void queryClient.prefetchQuery(meQuery)
    return authChannel.subscribe(() => applySessionChangedElsewhere(queryClient))
  }, [])

  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}
