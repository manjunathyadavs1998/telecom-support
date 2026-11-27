import { Smartphone } from 'lucide-react'
import { PageContainer } from '@/components/layout/PageContainer'
import { EmptyState } from '@/components/states/EmptyState'
import { ErrorState } from '@/components/states/ErrorState'
import { LoadingState } from '@/components/states/LoadingState'
import { ForbiddenPage } from '@/pages/ForbiddenPage'
import { Can } from '@/permissions/Can'
import { ApiError } from '@/services/api/errors'
import type { Subscription } from '@/types/api'
import { primaryFirst, useSubscriptionsQuery } from './queries'
import { SubscriptionCard } from './SubscriptionCard'
import { UpgradeOptions } from './UpgradeOptions'

/** Upgrade controls are hidden while a change is pending and when the status isn't ACTIVE (frontend.md §3 My plan). */
const canUpgrade = (subscription: Subscription) => subscription.status === 'ACTIVE' && !subscription.pendingPlanChange

function SubscriptionList({ subscriptions }: { subscriptions: readonly Subscription[] }) {
  if (subscriptions.length === 0) {
    return (
      <EmptyState
        icon={<Smartphone className="size-8" />}
        title="No plans on your account"
        description="When a number is added to your account, its plan appears here."
      />
    )
  }
  return (
    <div className="grid gap-6 xl:grid-cols-2">
      {primaryFirst(subscriptions).map((subscription) => (
        <SubscriptionCard key={subscription.id} subscription={subscription}>
          {canUpgrade(subscription) ? (
            <Can allOf={['plan.change']}>
              <UpgradeOptions subscription={subscription} />
            </Can>
          ) : null}
        </SubscriptionCard>
      ))}
    </div>
  )
}

/**
 * My plan `/customer/plan` (frontend.md §3 My plan; CUS-03.1–03.5): one card per subscription, primary first, with upgrade
 * options and plan change (`plan.change`). A 403 on the subscriptions query (the page's primary query) → `ForbiddenPage`; any
 * other error → `ErrorState` with retry.
 */
export function PlanPage() {
  const query = useSubscriptionsQuery()
  if (query.isError && query.error instanceof ApiError && query.error.status === 403) return <ForbiddenPage />
  return (
    <PageContainer title="My plan" description="Your numbers, their plans and benefits.">
      {query.isPending ? (
        <LoadingState label="Loading your plans" />
      ) : query.isError ? (
        <ErrorState title="We couldn't load your plans" error={query.error} onRetry={() => void query.refetch()} />
      ) : (
        <SubscriptionList subscriptions={query.data} />
      )}
    </PageContainer>
  )
}

/** `route.lazy` entry (pageRegistry). */
export { PlanPage as Component }
