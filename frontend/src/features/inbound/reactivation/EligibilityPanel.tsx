import { useQueryClient } from '@tanstack/react-query'
import { Ban } from 'lucide-react'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { LoadingState } from '@/components/states/LoadingState'
import { SectionErrorState } from '@/components/states/SectionErrorState'
import { Alert } from '@/components/ui/alert'
import { eligibilityText, suspensionReasonText } from '@/lib/inboundText'
import type { ReactivationEligibility } from '@/types/api'
import { historyRowId, invalidateReactivation, useEligibilityQuery, useReactivationHistoryQuery } from './queries'
import type { ReactivationNotice } from './ReactivationForm'

const linkClass = 'font-medium text-primary underline-offset-4 hover:underline'
const FIRST_HISTORY_PAGE = { page: 0, size: 20 }

type EligibilityPanelProps = {
  customerId: number
  enabled: boolean
  /** The request form (F14), rendered only when eligible and nothing is pending; it reports success/pending via `onNotice`. */
  renderForm?: (eligibility: ReactivationEligibility, onNotice: (notice: ReactivationNotice) => void) => ReactNode
}

function Facts({ eligibility }: { eligibility: ReactivationEligibility }) {
  return (
    <dl className="grid gap-3 sm:grid-cols-3">
      <div className="flex flex-col gap-0.5">
        <dt className="text-sm text-muted-foreground">Suspension reason</dt>
        <dd>{eligibility.suspensionReason ? suspensionReasonText(eligibility.suspensionReason) : '—'}</dd>
      </div>
      <div className="flex flex-col gap-0.5">
        <dt className="text-sm text-muted-foreground">Suspended numbers</dt>
        <dd>{eligibility.suspendedMsisdns?.length ? eligibility.suspendedMsisdns.join(', ') : '—'}</dd>
      </div>
      <div className="flex flex-col gap-0.5">
        <dt className="text-sm text-muted-foreground">Maximum hours</dt>
        <dd>{eligibility.maxHours ?? '—'}</dd>
      </div>
    </dl>
  )
}

/**
 * Eligibility (03-inbound/frontend.md §3.9; INB-04.1/04.2/04.6, I13): the facts, then either the reasons in plain words (no form)
 * or the request form. A prohibited reason (Fraud/Legal/Regulatory) is a danger alert: it can never be overridden.
 *
 * The success / pending notice lives here, not in the form, so the eligibility refetch that follows a request (now "already
 * open") cannot remove it (review M3). While pending, page 1 of the history is watched; when that reference leaves PENDING the
 * eligibility is invalidated and decides again (F5). The last eligibility is kept in memory while verification is lost, so the
 * form stays mounted with its input and idempotency key behind the re-verify dialog (review H1).
 */
export function EligibilityPanel({ customerId, enabled, renderForm }: EligibilityPanelProps) {
  const queryClient = useQueryClient()
  const query = useEligibilityQuery(customerId, enabled)
  const [snapshot, setSnapshot] = useState(query.data)
  if (query.data && query.data !== snapshot) setSnapshot(query.data)
  const eligibility = query.data ?? snapshot

  const [notice, setNotice] = useState<ReactivationNotice | null>(null)
  const noticeRef = useRef<HTMLDivElement>(null)
  const [focusRequest, setFocusRequest] = useState(0)
  useEffect(() => {
    if (focusRequest > 0) noticeRef.current?.focus()
  }, [focusRequest])
  const showNotice = (next: ReactivationNotice) => {
    setNotice(next)
    setFocusRequest((n) => n + 1)
  }

  const pendingReference = notice?.kind === 'pending' ? notice.reference : null
  const history = useReactivationHistoryQuery(customerId, FIRST_HISTORY_PAGE, enabled && pendingReference !== null)
  const settled = pendingReference
    ? history.data?.items.find((item) => item.reference === pendingReference && item.status !== 'PENDING')
    : undefined
  const [settledReference, setSettledReference] = useState<string | null>(null)
  if (settled && pendingReference !== null && settledReference !== pendingReference) {
    setSettledReference(pendingReference)
    setNotice(null)
  }
  useEffect(() => {
    if (settledReference !== null) invalidateReactivation(queryClient, customerId)
  }, [settledReference, queryClient, customerId])

  if (query.isError && !eligibility) {
    return <SectionErrorState title="We couldn't check eligibility" error={query.error} onRetry={() => void query.refetch()} />
  }
  if (!eligibility) return <LoadingState label="Checking eligibility" />

  const prohibited = eligibility.reasons.includes('PROHIBITED_REASON')
  return (
    <div className="flex flex-col gap-4">
      {notice?.kind === 'success' ? (
        <Alert ref={noticeRef} tabIndex={-1} className="block border-success/30 bg-success/10">
          <output className="block">{notice.message}</output>
        </Alert>
      ) : null}
      {notice?.kind === 'pending' ? (
        <Alert ref={noticeRef} tabIndex={-1} className="block border-warning/30 bg-warning/10">
          <output className="block">
            No answer from the provisioning system yet. Request {notice.reference} is pending and is checked automatically.
            Don&apos;t submit again.
          </output>
        </Alert>
      ) : null}
      <Facts eligibility={eligibility} />
      {eligibility.eligible ? (
        notice?.kind === 'pending' ? null : renderForm?.(eligibility, showNotice)
      ) : prohibited ? (
        <Alert variant="destructive" className="flex items-start gap-2">
          <Ban aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <p>{eligibilityText('PROHIBITED_REASON', eligibility)}</p>
        </Alert>
      ) : (
        <Alert className="block">
          <p className="font-medium">Service can&apos;t be restored right now:</p>
          <ul className="mt-1 list-disc pl-5">
            {eligibility.reasons.map((reason) => (
              <li key={reason}>
                {reason === 'OPEN_REQUEST' && eligibility.openRequestReference ? (
                  <>
                    A restoration is already open:{' '}
                    <a href={`#${historyRowId(eligibility.openRequestReference)}`} className={linkClass}>
                      {eligibility.openRequestReference}
                    </a>
                  </>
                ) : (
                  eligibilityText(reason, eligibility)
                )}
              </li>
            ))}
          </ul>
        </Alert>
      )}
    </div>
  )
}
