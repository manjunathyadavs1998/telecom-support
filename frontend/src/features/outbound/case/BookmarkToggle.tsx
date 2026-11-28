import { Bookmark } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { caseProblemText } from '@/lib/outboundText'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
import { useSubmitGuard } from '@/services/api/idempotency'
import type { OutboundCaseDetail } from '@/types/api'
import type { ActionNotice } from './ClaimButton'
import { problemReason, useBookmarkToggle } from './queries'

/** Bookmark toggle (O10/O11; 04-outbound/frontend.md §3.3; OUT-06.1): `aria-pressed`, disabled while pending, not optimistic. */
export function BookmarkToggle({ detail, onNotice }: { detail: OutboundCaseDetail; onNotice: (notice: ActionNotice) => void }) {
  const toggle = useBookmarkToggle(detail.reference)
  const guard = useSubmitGuard()
  const on = detail.bookmarked

  const run = () =>
    guard.run(async () => {
      try {
        await toggle.mutateAsync(!on)
      } catch (error) {
        const reason = problemReason(error)
        onNotice({
          tone: 'error',
          text: (reason && caseProblemText(reason)) ?? (error instanceof ApiError ? error.message : GENERIC_ERROR_MESSAGE),
        })
      }
    })

  return (
    <Button type="button" variant="outline" aria-pressed={on} disabled={guard.busy} onClick={() => void run()}>
      <Bookmark aria-hidden="true" className={on ? 'size-4 fill-current' : 'size-4'} />
      {on ? 'Bookmarked' : 'Bookmark'}
    </Button>
  )
}
