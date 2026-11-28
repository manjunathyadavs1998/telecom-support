import { useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { StatusBadge } from '@/components/data/StatusBadge'
import { statusLabel } from '@/components/data/statusLabel'
import { PageContainer } from '@/components/layout/PageContainer'
import { ErrorState } from '@/components/states/ErrorState'
import { LoadingState } from '@/components/states/LoadingState'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ActivityTimeline } from '@/features/inbound/cases/ActivityTimeline'
import { formatDateTime } from '@/lib/format'
import { cn } from '@/lib/utils'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { Can } from '@/permissions/Can'
import { ApiError } from '@/services/api/errors'
import { BookmarkToggle } from './BookmarkToggle'
import { CaseSidePanels } from './CaseSidePanels'
import { ClaimButton, type ActionNotice } from './ClaimButton'
import { NoteComposer } from './NoteComposer'
import { markSeen, useOutboundCase } from './queries'
import { ReassignDialog } from './ReassignDialog'
import { StatusDialog } from './StatusDialog'

const TONE: Record<ActionNotice['tone'], string> = {
  success: 'border-success/30 bg-success/10',
  warning: 'border-warning/30 bg-warning/10',
  error: 'border-destructive/40 bg-destructive/5 text-destructive',
}

/**
 * Case detail `/outbound/cases/:reference` (04-outbound/frontend.md §3.2–§3.3; OUT-03..OUT-05, OUT-06.1/06.4). Actions come only
 * from `allowedActions` (the server re-checks every one). The skeleton shows only before the first load; refetches never unmount
 * the dialogs or the composer (L2); action notices sit above the refetched part (L3); a 404 drops the cached case (L1).
 */
export function CaseDetailPage() {
  const { reference = '' } = useParams()
  const location = useLocation()
  const queryClient = useQueryClient()
  const query = useOutboundCase(reference)
  const [notice, setNotice] = useState<ActionNotice | null>(null)
  const seenFor = useRef<string | null>(null)
  const back = (location.state as { from?: string } | null)?.from ?? '/outbound'
  const detail = query.data

  // OUT-06.4: mark a bookmarked case seen once per mount, after its first successful load.
  useEffect(() => {
    if (detail?.bookmarked && seenFor.current !== detail.reference) {
      seenFor.current = detail.reference
      void markSeen(queryClient, detail.reference)
    }
  }, [detail?.bookmarked, detail?.reference, queryClient])

  if (!reference) return <NotFoundPage />
  const backLink = (
    <Button asChild variant="outline">
      <Link to={back}>
        <ArrowLeft aria-hidden="true" className="size-4" />
        Back
      </Link>
    </Button>
  )

  const notFound = query.isError && query.error instanceof ApiError && query.error.status === 404
  if (!detail || notFound) {
    if (query.isError) {
      return (
        <PageContainer title={`Case ${reference}`} actions={backLink}>
          <ErrorState
            title={notFound ? "This case isn't in your queue" : "We couldn't load this case"}
            error={query.error}
            onRetry={notFound ? undefined : () => void query.refetch()}
          />
        </PageContainer>
      )
    }
    return (
      <PageContainer title={`Case ${reference}`} actions={backLink}>
        <LoadingState label="Loading case" />
      </PageContainer>
    )
  }

  const actions = detail.allowedActions
  const noActions = !actions.claim && !actions.reassign && actions.transitions.length === 0
  const refreshFailed = query.isError && query.error instanceof ApiError && query.error.status !== 404

  return (
    <PageContainer
      title={`Case ${detail.reference}`}
      description={detail.subject}
      actions={
        <div className="flex flex-wrap gap-2">
          {backLink}
          <Can allOf={['case.bookmark']}>
            <BookmarkToggle detail={detail} onNotice={setNotice} />
          </Can>
        </div>
      }
    >
      <div className="flex flex-wrap items-center gap-2">
        <StatusBadge kind="caseInternal" value={detail.status} />
        <span className="text-sm text-muted-foreground">Customer sees: {statusLabel({ kind: 'case', value: detail.publicStatus })}</span>
        <StatusBadge kind="severity" value={detail.severity} />
        {detail.overdue ? <Badge className="border-destructive/40 bg-destructive/10 text-destructive">Overdue</Badge> : null}
      </div>

      <div className="flex flex-wrap gap-2">
        {actions.claim ? <ClaimButton reference={detail.reference} onNotice={setNotice} /> : null}
        <Can allOf={['case.assign.any']}>
          <ReassignDialog detail={detail} allowed={actions.reassign} />
        </Can>
        <StatusDialog detail={detail} allowed={actions.transitions.length > 0} />
      </div>
      {noActions && !actions.internalNote && !actions.publicComment ? (
        <p className="text-sm text-muted-foreground">
          {detail.assigneeName ? `Owned by ${detail.assigneeName}.` : 'View only. Claim this case to work on it.'}
        </p>
      ) : null}

      <div aria-live="polite">
        {notice ? (
          <Alert className={cn('block', TONE[notice.tone])}>
            <p>{notice.text}</p>
          </Alert>
        ) : null}
        {refreshFailed ? (
          <Alert className="mt-2 block">Couldn&apos;t refresh ({formatDateTime(new Date(query.errorUpdatedAt).toISOString())}). Showing the last loaded details.</Alert>
        ) : null}
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="flex flex-col gap-6 lg:col-span-2">
          <section aria-label="Description" className="flex flex-col gap-2 enter-soft rounded-xl border border-line bg-surface shadow-soft-sm p-4">
            <h2 className="text-base font-semibold">Description</h2>
            <p className="whitespace-pre-wrap break-words">{detail.description}</p>
          </section>
          <section aria-label="Activity" className="flex flex-col gap-3 enter-soft rounded-xl border border-line bg-surface shadow-soft-sm p-4">
            <h2 className="text-base font-semibold">Activity</h2>
            <ActivityTimeline activity={detail.activity} />
          </section>
          <NoteComposer detail={detail} />
        </div>
        <CaseSidePanels detail={detail} />
      </div>
    </PageContainer>
  )
}

export { CaseDetailPage as Component }
