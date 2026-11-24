import { Inbox, RefreshCw, ServerCrash } from 'lucide-react'
import type { ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { ApiError } from '@/services/http'
import { errorMessage } from '@/utils/errors'

/** Standard loading / empty / error states (XC-03). Every page uses these instead of ad-hoc markup. */

export function LoadingState({ variant = 'cards', rows = 3, label = 'Loading' }: { variant?: 'cards' | 'table' | 'form' | 'inline'; rows?: number; label?: string }) {
  return (
    <div role="status" aria-live="polite" className="w-full">
      <span className="sr-only">{label}…</span>
      {variant === 'inline' && <Skeleton className="h-5 w-40" />}
      {variant === 'cards' && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: rows }, (_, i) => (
            <Skeleton key={i} className="h-32 rounded-xl" />
          ))}
        </div>
      )}
      {variant === 'table' && (
        <div className="space-y-2">
          <Skeleton className="h-9 w-full" />
          {Array.from({ length: rows }, (_, i) => (
            <Skeleton key={i} className="h-11 w-full" />
          ))}
        </div>
      )}
      {variant === 'form' && (
        <div className="space-y-4">
          {Array.from({ length: rows }, (_, i) => (
            <div key={i} className="space-y-2">
              <Skeleton className="h-4 w-28" />
              <Skeleton className="h-10 w-full" />
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export function EmptyState({ title, description, action, icon }: { title: string; description?: string; action?: ReactNode; icon?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 rounded-xl border border-dashed border-border bg-white px-6 py-10 text-center">
      <div className="rounded-full bg-slate-100 p-3 text-slate-500">{icon ?? <Inbox className="h-6 w-6" aria-hidden />}</div>
      <p className="font-medium text-slate-800">{title}</p>
      {description && <p className="max-w-md text-sm text-muted">{description}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  )
}

export function ErrorState({ error, onRetry, title = 'We could not load this information' }: { error: unknown; onRetry?: () => void; title?: string }) {
  const correlationId = error instanceof ApiError ? error.correlationId : undefined
  return (
    <div role="alert" className="flex flex-col items-center gap-2 rounded-xl border border-red-200 bg-red-50/60 px-6 py-8 text-center">
      <ServerCrash className="h-6 w-6 text-danger" aria-hidden />
      <p className="font-medium text-slate-800">{title}</p>
      <p className="text-sm text-slate-600">{errorMessage(error)}</p>
      {correlationId && <p className="text-xs text-muted">Reference: {correlationId}</p>}
      {onRetry && (
        <Button variant="secondary" size="sm" onClick={onRetry} className="mt-2">
          <RefreshCw className="h-4 w-4" aria-hidden /> Try again
        </Button>
      )}
    </div>
  )
}
