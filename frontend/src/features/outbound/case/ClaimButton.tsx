import { useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { GENERIC_ERROR_MESSAGE, ApiError } from '@/services/api/errors'
import { useSubmitGuard } from '@/services/api/idempotency'
import { outboundKeys } from '../queue/queries'
import { invalidateLists, problemReason, useClaimCase } from './queries'

export type ActionNotice = { tone: 'success' | 'warning' | 'error'; text: string; reference?: string }

type ClaimButtonProps = {
  reference: string
  /** Reports the outcome to the page's notice region (above the refetched part, L3). */
  onNotice: (notice: ActionNotice) => void
  size?: 'sm' | 'default'
}

/**
 * Claim (O6; 04-outbound/frontend.md §3.3): no confirmation (self-assignment, OQ 6); never optimistic. A lost race (409
 * `already_assigned`) says so and refreshes, so the row shows the new owner.
 */
export function ClaimButton({ reference, onNotice, size = 'default' }: ClaimButtonProps) {
  const queryClient = useQueryClient()
  const claim = useClaimCase(reference)
  const guard = useSubmitGuard()

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: outboundKeys.case(reference) })
    invalidateLists(queryClient)
  }

  const run = () =>
    guard.run(async () => {
      try {
        await claim.mutateAsync()
        onNotice({ tone: 'success', text: `${reference} is now assigned to you.`, reference })
      } catch (error) {
        const reason = problemReason(error)
        if (reason === 'already_assigned') {
          onNotice({ tone: 'warning', text: `Someone else claimed ${reference} first. The case has been refreshed.` })
        } else if (reason === 'case_closed') {
          onNotice({ tone: 'warning', text: `${reference} is resolved or closed and can't be claimed.` })
        } else if (error instanceof ApiError && error.status === 404) {
          onNotice({ tone: 'warning', text: `${reference} is no longer in your queue.` })
        } else if (error instanceof ApiError && error.status === 403) {
          onNotice({ tone: 'error', text: "You can't claim cases." })
        } else {
          onNotice({ tone: 'error', text: error instanceof ApiError ? error.message : GENERIC_ERROR_MESSAGE })
        }
        refresh()
      }
    })

  return (
    <Button type="button" size={size === 'sm' ? 'sm' : undefined} onClick={() => void run()} disabled={guard.busy}>
      {guard.busy ? 'Claiming…' : 'Claim'}
      <span className="sr-only"> {reference}</span>
    </Button>
  )
}
