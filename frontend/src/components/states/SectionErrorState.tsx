import { ErrorState } from '@/components/states/ErrorState'
import { ApiError } from '@/services/api/errors'

/** frontend.md §3 403 rule (F2): the copy for a 403 on a secondary query, shown in that section only. */
export const SECTION_FORBIDDEN_TITLE = "This information isn't available for your account"

type SectionErrorStateProps = {
  /** The title for any failure other than 403. */
  title: string
  error: Error
  onRetry: () => void
}

/**
 * The error state of one section fed by a secondary query (frontend.md §3 403 rule, XC-03): a 403 shows
 * {@link SECTION_FORBIDDEN_TITLE} without a retry (retrying cannot help); any other failure shows `title` with a retry.
 */
export function SectionErrorState({ title, error, onRetry }: SectionErrorStateProps) {
  const forbidden = error instanceof ApiError && error.status === 403
  return (
    <ErrorState
      title={forbidden ? SECTION_FORBIDDEN_TITLE : title}
      error={error}
      onRetry={forbidden ? undefined : onRetry}
    />
  )
}
