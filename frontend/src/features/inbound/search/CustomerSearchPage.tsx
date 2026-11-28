import { SearchX } from 'lucide-react'
import { useId, useState } from 'react'
import { Link } from 'react-router'
import { StatusBadge } from '@/components/data/StatusBadge'
import { PageContainer } from '@/components/layout/PageContainer'
import { EmptyState } from '@/components/states/EmptyState'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useCustomerMatch } from '../verificationState'
import { CustomerSearchForm, NO_MATCH_MESSAGE } from './CustomerSearchForm'

/** Whole minutes left until `verifiedUntil`, or 0 when it has passed or is absent. */
function minutesLeft(verifiedUntil: string | null | undefined): number {
  if (!verifiedUntil) return 0
  const ms = Date.parse(verifiedUntil) - Date.now()
  return Number.isFinite(ms) && ms > 0 ? Math.ceil(ms / 60_000) : 0
}

/** The masked hit (§3.1): never more than `CustomerMatch` before verification (NFR-PRIV). */
function MatchCard({ customerId }: { customerId: number }) {
  const headingId = useId()
  const { data: match } = useCustomerMatch(customerId)
  if (!match) return null
  const left = minutesLeft(match.verifiedUntil)
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4 enter-soft rounded-xl border border-line bg-surface shadow-soft-sm p-4 md:p-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h2 id={headingId} className="text-lg font-semibold text-foreground">
            {match.maskedName}
          </h2>
          <p className="text-muted-foreground">
            {match.maskedMsisdn} · Customer {match.customerRef}
          </p>
        </div>
        {left > 0 ? (
          <Badge className="border-success/30 bg-success/10 text-success-foreground">Verified · {left} min left</Badge>
        ) : null}
      </div>
      <dl className="flex flex-wrap gap-x-6 gap-y-2">
        <div className="flex items-center gap-2">
          <dt className="text-sm text-muted-foreground">Customer</dt>
          <dd>
            <StatusBadge kind="customer" value={match.customerStatus} />
          </dd>
        </div>
        <div className="flex items-center gap-2">
          <dt className="text-sm text-muted-foreground">Account</dt>
          <dd>
            <StatusBadge kind="account" value={match.accountStatus} />
          </dd>
        </div>
        <div className="flex items-center gap-2">
          <dt className="text-sm text-muted-foreground">Line</dt>
          <dd>
            <StatusBadge kind="subscription" value={match.subscriptionStatus} />
          </dd>
        </div>
      </dl>
      <div>
        <Button asChild>
          <Link to={`/inbound/customers/${match.customerId}`}>{left > 0 ? 'Open workspace' : 'Verify identity'}</Link>
        </Button>
      </div>
    </section>
  )
}

type Result = { kind: 'none' } | { kind: 'empty' } | { kind: 'hit'; customerId: number }

/** `/inbound` (03-inbound/frontend.md §3.1; INB-01.1, INB-01.4): find a customer by exact mobile number. */
export function CustomerSearchPage() {
  const [result, setResult] = useState<Result>({ kind: 'none' })
  return (
    <PageContainer title="Customer search" description="Find the caller by their mobile number, then verify their identity.">
      <section aria-label="Search" className="enter-soft rounded-xl border border-line bg-surface shadow-soft-sm p-4 md:p-6">
        <CustomerSearchForm
          onResult={(match) => setResult(match ? { kind: 'hit', customerId: match.customerId } : { kind: 'empty' })}
        />
      </section>
      <div aria-live="polite">
        {result.kind === 'empty' ? (
          <EmptyState
            icon={<SearchX className="size-8" />}
            title={NO_MATCH_MESSAGE}
            description="Check the number with the caller. Closed lines are not shown."
          />
        ) : null}
        {result.kind === 'hit' ? <MatchCard customerId={result.customerId} /> : null}
      </div>
    </PageContainer>
  )
}

export { CustomerSearchPage as Component }
