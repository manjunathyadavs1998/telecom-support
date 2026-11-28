import { useId, type ReactNode } from 'react'
import { Can } from '@/permissions/Can'
import { useVerifiedCustomer } from '../workspace/queries'
import { EligibilityPanel } from './EligibilityPanel'
import { ReactivationForm } from './ReactivationForm'
import { ReactivationHistory } from './ReactivationHistory'

function Section({ title, children }: { title: string; children: ReactNode }) {
  const headingId = useId()
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4 rounded-lg border bg-white p-4 md:p-6">
      <h2 id={headingId} className="text-lg font-semibold text-foreground">
        {title}
      </h2>
      {children}
    </section>
  )
}

/**
 * Reactivation `…/reactivation` (03-inbound/frontend.md §3.9; INB-04): eligibility and the request for reactivation approvers
 * (`service.reactivate`), history for every inbound agent.
 */
export function ReactivationPage() {
  const { customerId, verified } = useVerifiedCustomer()
  return (
    <div className="flex flex-col gap-6">
      <Section title="Temporary restoration">
        <Can
          allOf={['service.reactivate']}
          fallback={<p className="text-muted-foreground">Only reactivation approvers can restore service</p>}
        >
          <EligibilityPanel
            customerId={customerId}
            enabled={verified}
            renderForm={(eligibility) => <ReactivationForm customerId={customerId} eligibility={eligibility} />}
          />
        </Can>
      </Section>
      <Section title="History">
        <ReactivationHistory customerId={customerId} enabled={verified} />
      </Section>
    </div>
  )
}

export { ReactivationPage as Component }
