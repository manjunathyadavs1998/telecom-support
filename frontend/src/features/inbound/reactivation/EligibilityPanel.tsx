import { Ban } from 'lucide-react'
import type { ReactNode } from 'react'
import { LoadingState } from '@/components/states/LoadingState'
import { SectionErrorState } from '@/components/states/SectionErrorState'
import { Alert } from '@/components/ui/alert'
import { eligibilityText, suspensionReasonText } from '@/lib/inboundText'
import type { ReactivationEligibility } from '@/types/api'
import { historyRowId, useEligibilityQuery } from './queries'

const linkClass = 'font-medium text-primary underline-offset-4 hover:underline'

type EligibilityPanelProps = {
  customerId: number
  enabled: boolean
  /** The request form (F14), rendered only when the server says the account is eligible. */
  renderForm?: (eligibility: ReactivationEligibility) => ReactNode
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
 */
export function EligibilityPanel({ customerId, enabled, renderForm }: EligibilityPanelProps) {
  const query = useEligibilityQuery(customerId, enabled)
  if (query.isError) {
    return <SectionErrorState title="We couldn't check eligibility" error={query.error} onRetry={() => void query.refetch()} />
  }
  const eligibility = query.data
  if (!eligibility) return <LoadingState label="Checking eligibility" />

  const prohibited = eligibility.reasons.includes('PROHIBITED_REASON')
  return (
    <div className="flex flex-col gap-4">
      <Facts eligibility={eligibility} />
      {eligibility.eligible ? (
        renderForm?.(eligibility)
      ) : prohibited ? (
        <Alert variant="destructive" className="flex items-start gap-2">
          <Ban aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <p>{eligibilityText('PROHIBITED_REASON', eligibility)}</p>
        </Alert>
      ) : (
        <Alert className="block">
          <p className="font-medium">Service can't be restored right now:</p>
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
