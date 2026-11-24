import type { Ref } from 'react'
import type { ApiError } from '@/services/api/errors'

type FormRootErrorProps = {
  /** The server error; its `message` is shown unless `message` is given, and its correlation id when it has one. */
  error?: ApiError | null
  /** A form-specific text (e.g. login 401/429) shown instead of `error.message`. */
  message?: string
  /** Forms move focus here after a failed submit with a server error (frontend.md §3 Login a11y). */
  ref?: Ref<HTMLDivElement>
}

/** A form's non-field server error (frontend.md §4, §6 `root.server`): announced via `role="alert"`, with the correlation id. */
export function FormRootError({ error, message, ref }: FormRootErrorProps) {
  const text = message ?? error?.message
  if (!text) return null
  return (
    <div
      ref={ref}
      role="alert"
      tabIndex={-1}
      className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm text-destructive focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
    >
      <p>{text}</p>
      {error?.correlationId ? (
        <p className="mt-1 text-foreground">
          Correlation id: <code className="font-mono">{error.correlationId}</code>
        </p>
      ) : null}
    </div>
  )
}
