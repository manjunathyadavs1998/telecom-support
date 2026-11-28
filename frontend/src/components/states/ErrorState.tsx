import { useState } from 'react'
import { Button } from '@/components/ui/button'
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
    <div role="alert" className="enter-soft flex flex-col items-center gap-3 px-4 py-12 text-center">
      <h2 className="text-lg font-semibold text-destructive">{title}</h2>
      <p className="max-w-prose text-foreground">{detail}</p>
      {correlationId ? (
        <p className="flex flex-wrap items-center justify-center gap-2 text-sm text-muted-foreground">
          <span>
            Correlation id: <code className="font-mono text-foreground">{correlationId}</code>
          </span>
          <Button variant="outline" size="sm" onClick={() => void copyCorrelationId()} aria-label="Copy correlation id">
            Copy
          </Button>
          <span aria-live="polite">{copied ? 'Copied' : ''}</span>
        </p>
      ) : null}
      {onRetry ? (
        <Button onClick={onRetry} className="mt-2">
          Try again
        </Button>
      ) : null}
    </div>
  )
}
