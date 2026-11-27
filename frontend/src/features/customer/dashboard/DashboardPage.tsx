import { PageContainer } from '@/components/layout/PageContainer'
import { BillsCard, IdentityCard, PlanCard, PromotionBanner, QuickLinks, RecentRequestsCard } from './DashboardCards'

/**
 * Customer dashboard `/customer` (frontend.md §3 Dashboard; CUS-01): independent cards, each with its own query, permission
 * gate and loading/empty/error state, so a failing card (incl. 403) never blanks the page (CUS-01.2). Own data only, enforced
 * by the server (CUS-01.3). One column < `md`, two ≥ `md`.
 */
export function DashboardPage() {
  return (
    <PageContainer title="Home" description="Your account at a glance.">
      <div className="grid gap-6 md:grid-cols-2">
        <IdentityCard />
        <PromotionBanner />
        <PlanCard />
        <QuickLinks />
        <RecentRequestsCard />
        <BillsCard />
      </div>
    </PageContainer>
  )
}

/** `route.lazy` entry (pageRegistry). */
export { DashboardPage as Component }
