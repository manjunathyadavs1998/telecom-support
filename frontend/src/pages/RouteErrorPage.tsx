import { useEffect } from 'react'
import { PageContainer } from '@/components/layout/PageContainer'
import { ErrorState } from '@/components/states/ErrorState'
import { ApiError } from '@/services/api/errors'

type RouteErrorPageProps = {
  /** The router's error (`useRouteError()` in the `errorElement`, F14). */
  error: unknown
  onRetry: () => void
}

/**
 * Router `errorElement` (frontend.md §3 System pages, OQ 8): `ErrorState` with retry and the correlation id when the error
 * came from the API; logs to `console.error`; never shows a stack trace or internal message.
 */
export function RouteErrorPage({ error, onRetry }: RouteErrorPageProps) {
  useEffect(() => {
    console.error('Route error', error instanceof ApiError ? { status: error.status, correlationId: error.correlationId } : error)
  }, [error])

  const shown = error instanceof ApiError ? error : new Error('Route error')
  return (
    <PageContainer title="Something went wrong">
      <ErrorState title="This page couldn't be shown" error={shown} onRetry={onRetry} />
    </PageContainer>
  )
}
