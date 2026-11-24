import type { ReactNode, Ref } from 'react'
import { Alert } from '@/components/ui/alert'
import type { ApiError } from '@/services/api/errors'

type FormRootErrorProps = {
  /** The server error; its `message` is shown unless `message` is given, and its correlation id when it has one. */
  error?: ApiError | null
  /** A form-specific text (e.g. login 401/429) shown instead of `error.message`. Keep it static: it is a live region. */
  message?: ReactNode
  /** Forms move focus here after a failed submit with a server error (frontend.md §3 Login a11y). */
  ref?: Ref<HTMLDivElement>
}

/** A form's non-field server error (frontend.md §4, §6 `root.server`): a destructive `Alert` (`role="alert"`) with the correlation id. */
export function FormRootError({ error, message, ref }: FormRootErrorProps) {
  const text = message ?? error?.message
  if (!text) return null
  return (
    <Alert
      ref={ref}
      variant="destructive"
      tabIndex={-1}
      className="block focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
    >
      <p>{text}</p>
      {error?.correlationId ? (
        <p className="mt-1 text-foreground">
          Correlation id: <code className="font-mono">{error.correlationId}</code>
        </p>
      ) : null}
    </Alert>
  )
}
