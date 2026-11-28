import { Inbox, Receipt } from 'lucide-react'
import { useId, type ReactNode } from 'react'
import { Link } from 'react-router'
import { StatusBadge } from '@/components/data/StatusBadge'
import { EmptyState } from '@/components/states/EmptyState'
import { LoadingState } from '@/components/states/LoadingState'
import { SectionErrorState } from '@/components/states/SectionErrorState'
import { formatDate, formatDateTime, formatInr } from '@/lib/format'
import { useAgentCasesQuery, useAgentInvoicesQuery, useVerifiedCustomer } from './queries'

/** Overview requests card size (OQ 8). */
const RECENT_SIZE = 5
const RECENT_QUERY = { page: 0, size: RECENT_SIZE }
const linkClass = 'font-medium text-primary underline-offset-4 hover:underline'

function Card({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  const headingId = useId()
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4 rounded-lg border bg-white p-4 md:p-6">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={headingId} className="text-lg font-semibold text-foreground">
          {title}
        </h2>
        {action}
      </div>
      {children}
    </section>
  )
}

/** Overview Open bills (03-inbound/frontend.md §3.4, I7 `status=OPEN`). */
export function OpenBillsSection() {
  const { customerId, verified } = useVerifiedCustomer()
  const query = useAgentInvoicesQuery(customerId, 'OPEN', verified)
  return (
    <Card
      title="Open bills"
      action={
        <Link to={`/inbound/customers/${customerId}/billing`} className={linkClass}>
          View billing
        </Link>
      }
    >
      {query.isError ? (
        <SectionErrorState title="We couldn't load the bills" error={query.error} onRetry={() => void query.refetch()} />
      ) : !query.data ? (
        <LoadingState label="Loading bills" />
      ) : query.data.length === 0 ? (
        <EmptyState icon={<Receipt className="size-8" />} title="No open bills" />
      ) : (
        <ul className="flex flex-col divide-y">
          {query.data.map((invoice) => (
            <li key={invoice.id} className="flex flex-wrap items-center justify-between gap-2 py-2 first:pt-0 last:pb-0">
              <span className="flex flex-col">
                <span className="font-medium">{invoice.invoiceRef}</span>
                <span className="text-sm text-muted-foreground">Due {formatDate(invoice.dueDate)}</span>
              </span>
              <span className="font-medium">{formatInr(invoice.outstanding)}</span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

/** Overview Recent requests (§3.4, I9 page 0 of 5 in the default 3-month window). */
export function RecentSection() {
  const { customerId, verified } = useVerifiedCustomer()
  const query = useAgentCasesQuery(customerId, RECENT_QUERY, verified)
  const base = `/inbound/customers/${customerId}/cases`
  return (
    <Card
      title="Recent requests"
      action={
        <Link to={base} className={linkClass}>
          View all
        </Link>
      }
    >
      {query.isError ? (
        <SectionErrorState title="We couldn't load the requests" error={query.error} onRetry={() => void query.refetch()} />
      ) : !query.data ? (
        <LoadingState label="Loading requests" />
      ) : query.data.items.length === 0 ? (
        <EmptyState icon={<Inbox className="size-8" />} title="No requests in the last 3 months" />
      ) : (
        <ul className="flex flex-col divide-y">
          {query.data.items.map((item) => (
            <li key={item.reference} className="flex flex-col gap-1 py-2 first:pt-0 last:pb-0">
              <div className="flex flex-wrap items-center gap-2">
                <Link to={`${base}/${item.reference}`} className={linkClass}>
                  {item.reference}
                </Link>
                <StatusBadge kind="caseInternal" value={item.status} />
                <StatusBadge kind="severity" value={item.severity} />
              </div>
              <span className="text-sm text-muted-foreground">
                {item.categoryName}
                {item.subcategoryName ? ` › ${item.subcategoryName}` : ''} · Updated {formatDateTime(item.updatedAt)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}
