import { Can } from '@/permissions/Can'
import { LinesSection } from './LinesSection'
import { ProfileSection } from './ProfileSection'
import { OpenBillsSection, RecentSection } from './RecentSection'

/**
 * Overview `/inbound/customers/:customerId` (03-inbound/frontend.md §3.4; INB-01.3): four independent sections, each with its
 * own query, states and permission, so a failing one never blanks the page. Rendered by `WorkspaceFrame` only once verified.
 */
export function OverviewPage() {
  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <Can allOf={['customer.profile.read']}>
        <ProfileSection />
      </Can>
      <Can allOf={['plan.read']}>
        <LinesSection />
      </Can>
      <Can allOf={['payment.read']}>
        <OpenBillsSection />
      </Can>
      <Can allOf={['case.read']}>
        <RecentSection />
      </Can>
    </div>
  )
}

export { OverviewPage as Component }
