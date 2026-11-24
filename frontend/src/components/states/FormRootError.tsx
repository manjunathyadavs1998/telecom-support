import type { ApiError } from '@/services/api/errors'

/** A form's non-field server error (frontend.md §4, §6 `root.server`): announced via `role="alert"`, with the correlation id. */
export function FormRootError({ error }: { error?: ApiError }) {
  if (!error) return null
  return (
    <div role="alert" className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm text-destructive">
      <p>{error.message}</p>
      {error.correlationId ? (
        <p className="mt-1 text-foreground">
          Correlation id: <code className="font-mono">{error.correlationId}</code>
        </p>
      ) : null}
    </div>
  )
}
