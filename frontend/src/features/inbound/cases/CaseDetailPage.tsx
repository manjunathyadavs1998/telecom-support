import { ArrowLeft } from 'lucide-react'
import { useEffect, useId, type ReactNode } from 'react'
import { Link } from 'react-router'
import { StatusBadge } from '@/components/data/StatusBadge'
import { statusLabel } from '@/components/data/statusLabel'
import { APP_TITLE } from '@/components/layout/PageContainer'
import { ErrorState } from '@/components/states/ErrorState'
import { LoadingState } from '@/components/states/LoadingState'
import { SectionErrorState } from '@/components/states/SectionErrorState'
import { Button } from '@/components/ui/button'
import { formatDateTime } from '@/lib/format'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { ApiError } from '@/services/api/errors'
import { useInboundParams } from '../useInboundParams'
import { useVerifiedCustomer } from '../workspace/queries'
import { ActivityTimeline } from './ActivityTimeline'
import { useAgentCaseQuery } from './queries'
import { RelatedCases } from './RelatedCases'

const CONTACT_LABEL = { PHONE: 'Phone', SMS: 'SMS', EMAIL: 'Email' } as const

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

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="text-foreground">{children}</dd>
    </div>
  )
}

function BackToCases({ customerId }: { customerId: number }) {
  return (
    <Button asChild variant="outline">
      <Link to={`/inbound/customers/${customerId}/cases`}>
        <ArrowLeft aria-hidden="true" className="size-4" />
        Back to cases
      </Link>
    </Button>
  )
}

/** Case detail `…/cases/:reference` (03-inbound/frontend.md §3.7; INB-02.2–02.4, BR-04). */
export function CaseDetailPage() {
  const params = useInboundParams()
  const { customerId, verified } = useVerifiedCustomer()
  const reference = params?.reference ?? ''
  const query = useAgentCaseQuery(customerId, reference, verified && reference !== '')

  useEffect(() => {
    if (reference) document.title = `Request ${reference} · ${APP_TITLE}`
  }, [reference])

  if (!params?.reference) return <NotFoundPage />
  if (query.isError) {
    if (query.error instanceof ApiError && query.error.status === 404) {
      return (
        <div className="flex flex-col items-center gap-4">
          <ErrorState title="Request not found for this customer" error={query.error} />
          <BackToCases customerId={customerId} />
        </div>
      )
    }
    return <SectionErrorState title="We couldn't load the request" error={query.error} onRetry={() => void query.refetch()} />
  }
  const item = query.data
  if (!item) return <LoadingState label="Loading request" />

  return (
    <div className="flex flex-col gap-6">
      <div>
        <BackToCases customerId={customerId} />
      </div>
      <Section title={`Request ${item.reference}`}>
        <div className="flex flex-wrap items-center gap-2">
          <StatusBadge kind="caseInternal" value={item.status} />
          <span className="text-sm text-muted-foreground">Customer sees: {statusLabel({ kind: 'case', value: item.publicStatus })}</span>
          <StatusBadge kind="severity" value={item.severity} />
        </div>
        <dl className="grid gap-4 sm:grid-cols-2">
          <Field label="Subject">{item.subject}</Field>
          <Field label="Category">
            {item.subcategoryName ? `${item.categoryName} › ${item.subcategoryName}` : item.categoryName}
          </Field>
          <Field label="Type">{item.caseType === 'COMPLAINT' ? 'Complaint' : 'Request'}</Field>
          <Field label="Owner">{item.ownerName ?? 'Unassigned'}</Field>
          <Field label="Created by">
            {item.createdByName} ({item.channel === 'INBOUND_AGENT' ? 'Inbound agent' : 'Customer portal'})
          </Field>
          <Field label="Created (IST)">{formatDateTime(item.createdAt)}</Field>
          <Field label="Updated (IST)">{formatDateTime(item.updatedAt)}</Field>
          {item.slaDueAt ? <Field label="SLA due (IST)">{formatDateTime(item.slaDueAt)}</Field> : null}
          <Field label="Preferred contact">{CONTACT_LABEL[item.preferredContact] ?? item.preferredContact}</Field>
          <Field label="Contact number">{item.contactMsisdn}</Field>
        </dl>
        <div className="flex flex-col gap-1">
          <h3 className="text-sm text-muted-foreground">Description</h3>
          <p className="whitespace-pre-wrap break-words">{item.description}</p>
        </div>
      </Section>
      <Section title="Activity">
        <ActivityTimeline activity={item.activity} />
      </Section>
      <Section title="Possible duplicates">
        <RelatedCases customerId={customerId} cases={item.possibleDuplicates} />
      </Section>
    </div>
  )
}

export { CaseDetailPage as Component }
