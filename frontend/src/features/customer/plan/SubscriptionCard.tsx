import { ChevronDown, Info } from 'lucide-react'
import { useId, useState, type ReactNode } from 'react'
import { StatusBadge } from '@/components/data/StatusBadge'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import type { Benefit, Subscription } from '@/types/api'
import { pendingChangeText, perMonth } from './planFormat'

/** Benefit groups in display order (frontend.md §3 My plan, CUS-03.2). */
const BENEFIT_GROUPS: { type: Benefit['type']; label: string }[] = [
  { type: 'VOICE', label: 'Voice' },
  { type: 'DATA', label: 'Data' },
  { type: 'SMS', label: 'SMS' },
  { type: 'ROAMING', label: 'Roaming' },
  { type: 'OTT', label: 'OTT' },
  { type: 'OTHER', label: 'Other' },
]

/** The key benefits as "Label: value" items (collapsed card and upgrade comparison). */
export function KeyBenefits({ benefits }: { benefits: readonly Benefit[] }) {
  const key = benefits.filter((benefit) => benefit.key)
  if (key.length === 0) return null
  return (
    <ul className="flex flex-col gap-0.5 text-sm">
      {key.map((benefit) => (
        <li key={`${benefit.type}-${benefit.label}`}>
          <span className="text-muted-foreground">{benefit.label}:</span> {benefit.value}
        </li>
      ))}
    </ul>
  )
}

/** All benefits grouped Voice · Data · SMS · Roaming · OTT · Other; only non-empty groups are shown (CUS-03.2). */
function BenefitGroups({ benefits }: { benefits: readonly Benefit[] }) {
  const groups = BENEFIT_GROUPS.map((group) => ({
    ...group,
    items: benefits.filter((benefit) => benefit.type === group.type),
  })).filter((group) => group.items.length > 0)
  if (groups.length === 0) return <p className="text-sm text-muted-foreground">No benefits are listed for this plan.</p>
  return (
    <div className="grid gap-4 sm:grid-cols-2">
      {groups.map((group) => (
        <div key={group.type} className="flex flex-col gap-1">
          <h4 className="text-sm font-semibold text-foreground">{group.label}</h4>
          <ul className="flex flex-col gap-0.5 text-sm">
            {group.items.map((benefit) => (
              <li key={`${benefit.label}-${benefit.value}`}>
                <span className="text-muted-foreground">{benefit.label}:</span> {benefit.value}
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  )
}

type SubscriptionCardProps = {
  subscription: Subscription
  /** The upgrade area (frontend.md §3 My plan, CUS-03.4); the page decides whether it is shown. */
  children?: ReactNode
}

/**
 * One subscription (frontend.md §3 My plan; CUS-03.1/03.2): collapsed plan name, monthly rental, status, billing cycle and key
 * benefits; "View details" is a disclosure listing all benefits by group. A pending plan change is shown as an info alert. No
 * usage (CUS-03.3).
 */
export function SubscriptionCard({ subscription, children }: SubscriptionCardProps) {
  const headingId = useId()
  const detailsId = useId()
  const [expanded, setExpanded] = useState(false)
  const { plan } = subscription
  const pending = pendingChangeText(subscription)

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4 rounded-lg border bg-white p-4 md:p-6">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="flex flex-col gap-0.5">
          <h2 id={headingId} className="text-lg font-semibold text-foreground">
            {plan.name}
          </h2>
          <p className="text-sm text-muted-foreground">
            {subscription.msisdn}
            {subscription.primary ? ' · Primary number' : null}
          </p>
        </div>
        <StatusBadge kind="subscription" value={subscription.status} />
      </div>
      <dl className="grid gap-3 sm:grid-cols-2">
        <div className="flex flex-col gap-0.5">
          <dt className="text-sm text-muted-foreground">Monthly rental</dt>
          <dd className="font-medium text-foreground">{perMonth(plan.monthlyRental)}</dd>
        </div>
        <div className="flex flex-col gap-0.5">
          <dt className="text-sm text-muted-foreground">Billing cycle</dt>
          <dd className="text-foreground">Day {subscription.billingCycleDay} of each month</dd>
        </div>
      </dl>
      <KeyBenefits benefits={plan.benefits} />
      {pending ? (
        <Alert asChild>
          <p>
            <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-primary" />
            <span>{pending}</span>
          </p>
        </Alert>
      ) : null}
      <div className="flex flex-col gap-3">
        <Button
          type="button"
          variant="outline"
          className="self-start"
          aria-expanded={expanded}
          aria-controls={detailsId}
          onClick={() => setExpanded((open) => !open)}
        >
          View details
          <ChevronDown aria-hidden="true" className={cn('size-4 transition-transform', expanded && 'rotate-180')} />
        </Button>
        <div id={detailsId} hidden={!expanded} className="flex flex-col gap-3">
          {plan.description ? <p className="text-sm text-foreground">{plan.description}</p> : null}
          <h3 className="sr-only">All benefits</h3>
          <BenefitGroups benefits={plan.benefits} />
        </div>
      </div>
      {children}
    </section>
  )
}
