import { Smartphone } from 'lucide-react'
import { useId } from 'react'
import { StatusBadge } from '@/components/data/StatusBadge'
import { EmptyState } from '@/components/states/EmptyState'
import { LoadingState } from '@/components/states/LoadingState'
import { SectionErrorState } from '@/components/states/SectionErrorState'
import { Badge } from '@/components/ui/badge'
import { primaryFirst } from '@/features/customer/plan/queries'
import { formatDate, formatInr } from '@/lib/format'
import { useAgentSubscriptionsQuery, useVerifiedCustomer } from './queries'

/** Overview Lines and plans (03-inbound/frontend.md §3.4, I6; INB-01.3): read-only. */
export function LinesSection() {
  const headingId = useId()
  const { customerId, verified } = useVerifiedCustomer()
  const query = useAgentSubscriptionsQuery(customerId, verified)

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4 enter-soft rounded-xl border border-line bg-surface shadow-soft-sm p-4 md:p-6">
      <h2 id={headingId} className="text-lg font-semibold text-foreground">
        Lines and plans
      </h2>
      {query.isError ? (
        <SectionErrorState title="We couldn't load the lines" error={query.error} onRetry={() => void query.refetch()} />
      ) : !query.data ? (
        <LoadingState label="Loading lines" />
      ) : query.data.length === 0 ? (
        <EmptyState icon={<Smartphone className="size-8" />} title="No lines on this account" />
      ) : (
        <ul className="flex flex-col divide-y">
          {primaryFirst(query.data).map((line) => {
            const keyBenefits = line.plan.benefits.filter((benefit) => benefit.key)
            return (
              <li key={line.id} className="flex flex-col gap-2 py-3 first:pt-0 last:pb-0">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{line.msisdn}</span>
                  {line.primary ? <Badge className="border-line-strong bg-surface-2 text-text-2">Primary</Badge> : null}
                  <StatusBadge kind="subscription" value={line.status} />
                </div>
                <p>
                  {line.plan.name} · {formatInr(line.plan.monthlyRental)}/month
                </p>
                {keyBenefits.length > 0 ? (
                  <p className="text-sm text-muted-foreground">
                    {keyBenefits.map((benefit) => `${benefit.label}: ${benefit.value}`).join(' · ')}
                  </p>
                ) : null}
                <p className="text-sm text-muted-foreground">Billing day {line.billingCycleDay}</p>
                {line.pendingPlanChange?.status === 'SCHEDULED' ? (
                  <p className="text-sm">
                    Plan change to {line.pendingPlanChange.toPlanCode} on {formatDate(line.pendingPlanChange.effectiveDate)}
                  </p>
                ) : null}
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
