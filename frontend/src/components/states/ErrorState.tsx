import { useState } from 'react'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'

type ErrorStateProps = {
  title: string
  error: ApiError | Error
  onRetry?: () => void
}

/**
 * Error state (frontend.md §4, XC-03, NFR-OBS): an `alert` with the server's `detail`, a copyable correlation id when the
 * server sent one, and a retry action. A non-API error shows a generic message (never a stack trace or internal text).
 */
export function ErrorState({ title, error, onRetry }: ErrorStateProps) {
  const detail = error instanceof ApiError ? error.message : GENERIC_ERROR_MESSAGE
  const correlationId = error instanceof ApiError ? error.correlationId : null
  const [copied, setCopied] = useState(false)

  async function copyCorrelationId() {
    if (!correlationId) return
    try {
      await navigator.clipboard.writeText(correlationId)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  return (
    <div role="alert" className="flex flex-col items-center gap-3 px-4 py-10 text-center">
      <h2 className="text-lg font-semibold text-destructive">{title}</h2>
      <p className="max-w-prose text-foreground">{detail}</p>
      {correlationId ? (
        <p className="flex flex-wrap items-center justify-center gap-2 text-sm text-muted-foreground">
          <span>
            Correlation id: <code className="font-mono text-foreground">{correlationId}</code>
          </span>
          <button
            type="button"
            onClick={() => void copyCorrelationId()}
            aria-label="Copy correlation id"
            className="rounded-md border border-muted-foreground/40 px-2 py-1 text-foreground hover:bg-muted-foreground/10"
          >
            Copy
          </button>
          <span aria-live="polite">{copied ? 'Copied' : ''}</span>
        </p>
      ) : null}
      {onRetry ? (
        <button
          type="button"
          onClick={onRetry}
          className="mt-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
        >
          Try again
        </button>
      ) : null}
    </div>
  )
}
