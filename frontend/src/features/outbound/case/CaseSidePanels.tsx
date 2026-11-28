import { useId, type ReactNode } from 'react'
import { StatusBadge } from '@/components/data/StatusBadge'
import { formatDateTime } from '@/lib/format'
import { caseTypeText, channelText, closureTypeText, preferredContactText } from '@/lib/outboundText'
import type { AccountStatus, OutboundCaseDetail } from '@/types/api'

function Panel({ title, children }: { title: string; children: ReactNode }) {
  const id = useId()
  return (
    <section aria-labelledby={id} className="flex flex-col gap-3 enter-soft rounded-xl border border-line bg-surface shadow-soft-sm p-4">
      <h2 id={id} className="text-base font-semibold">
        {title}
      </h2>
      <dl className="flex flex-col gap-2 text-sm">{children}</dl>
    </section>
  )
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col">
      <dt className="text-muted-foreground">{label}</dt>
      <dd>{children}</dd>
    </div>
  )
}

/** Customer, case and resolution facts (04-outbound/frontend.md §3.2; OUT-04, OUT-04.2, OUT-04.4). No email or address. */
export function CaseSidePanels({ detail }: { detail: OutboundCaseDetail }) {
  return (
    <div className="flex flex-col gap-4">
      <Panel title="Customer">
        <Row label="Name">{detail.customer.customerName}</Row>
        <Row label="Customer ref">{detail.customer.customerRef}</Row>
        <Row label="Account">
          <span className="flex flex-wrap items-center gap-2">
            {detail.customer.accountRef}
            {detail.accountStatus ? <StatusBadge kind="account" value={detail.accountStatus as AccountStatus} /> : null}
          </span>
        </Row>
        <Row label="Contact number">
          {detail.contactMsisdn ? (
            <a href={`tel:${detail.contactMsisdn}`} className="font-medium text-primary hover:underline">
              {detail.contactMsisdn}
            </a>
          ) : (
            <span className="flex flex-col">
              <span>{detail.customer.maskedMsisdn}</span>
              <span className="text-muted-foreground">Full number shown to the assigned agent only</span>
            </span>
          )}
        </Row>
        <Row label="Preferred contact">{preferredContactText(detail.preferredContact)}</Row>
      </Panel>
      <Panel title="Case">
        <Row label="Category">
          {detail.subcategoryName ? `${detail.categoryName} › ${detail.subcategoryName}` : detail.categoryName}
        </Row>
        <Row label="Type">{caseTypeText(detail.caseType)}</Row>
        <Row label="Created by">
          {detail.creatorName} via {channelText(detail.channel)}
        </Row>
        <Row label="Created (IST)">{formatDateTime(detail.createdAt)}</Row>
        <Row label="Last updated (IST)">{formatDateTime(detail.updatedAt)}</Row>
        <Row label="Response due (IST)">{formatDateTime(detail.responseDueAt)}</Row>
        <Row label="SLA due (IST)">
          {formatDateTime(detail.slaDueAt)}
          {detail.overdue ? <span className="ml-2 font-medium text-destructive">Overdue</span> : null}
        </Row>
        <Row label="Owner">
          {detail.assigneeName
            ? `${detail.assigneeName}${detail.teamName ? ` · ${detail.teamName}` : ''}`
            : `Unassigned${detail.teamName ? ` · ${detail.teamName} queue` : ''}`}
        </Row>
        {detail.reopenCount && detail.reopenCount > 0 ? <Row label="Reopened">{`${detail.reopenCount} times`}</Row> : null}
      </Panel>
      {detail.resolvedAt ? (
        <Panel title="Resolution">
          <Row label="Resolved (IST)">
            {formatDateTime(detail.resolvedAt)}
            {detail.resolvedByName ? ` by ${detail.resolvedByName}` : ''}
          </Row>
          {detail.resolutionRemarks ? (
            <Row label="Remarks (internal — not shown to the customer)">
              <span className="whitespace-pre-wrap break-words">{detail.resolutionRemarks}</span>
            </Row>
          ) : null}
          {detail.closedAt ? <Row label="Closed (IST)">{formatDateTime(detail.closedAt)}</Row> : null}
          {detail.closureType ? <Row label="Closure">{closureTypeText(detail.closureType)}</Row> : null}
        </Panel>
      ) : null}
    </div>
  )
}
