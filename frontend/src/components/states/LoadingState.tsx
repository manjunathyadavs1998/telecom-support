import { Skeleton } from '@/components/ui/skeleton'

type LoadingStateProps = {
  /** Announced to screen readers only (sr-only), e.g. "Loading cases". */
  label: string
  variant?: 'skeleton' | 'spinner'
}

function Spinner() {
  return (
    <span
      aria-hidden="true"
      className="inline-block size-8 animate-spin rounded-full border-4 border-primary/20 border-t-primary"
    />
  )
}

/**
 * Loading state (frontend.md §4, XC-03): an `<output>` (implicit `status` role, polite live region) with `aria-busy` and a
 * label for screen readers only. Content is phrasing-only (`<span>`s) because `<output>` holds phrasing content.
 */
export function LoadingState({ label, variant = 'skeleton' }: LoadingStateProps) {
  return (
    <output aria-live="polite" aria-busy="true" className="block w-full p-4">
      <span className="sr-only">{label}</span>
      {variant === 'spinner' ? (
        <span className="flex justify-center">
          <Spinner />
        </span>
      ) : (
        <span aria-hidden="true" className="block space-y-3">
          <Skeleton className="w-1/3" />
          <Skeleton className="w-full" />
          <Skeleton className="w-5/6" />
        </span>
      )}
    </output>
  )
}

/** Session bootstrap spinner (§3 Application shell): fills the viewport. */
export function FullPageSpinner({ label = 'Loading' }: { label?: string }) {
  return (
    <output aria-live="polite" aria-busy="true" className="flex min-h-screen items-center justify-center bg-background">
      <span className="sr-only">{label}</span>
      <Spinner />
    </output>
  )
}
