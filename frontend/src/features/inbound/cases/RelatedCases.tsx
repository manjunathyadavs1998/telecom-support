import { Link } from 'react-router'
import { StatusBadge } from '@/components/data/StatusBadge'
import { formatDateTime } from '@/lib/format'
import type { RelatedCase } from '@/types/api'

/** Possible duplicates (03-inbound/frontend.md §3.7; INB-02.3): ≤ 5 rows, for information only. */
export function RelatedCases({ customerId, cases }: { customerId: number; cases: readonly RelatedCase[] }) {
  if (cases.length === 0) return <p className="text-muted-foreground">No related requests found</p>
  return (
    <div className="flex flex-col gap-3">
      <p className="text-sm text-muted-foreground">Same category within 30 days. For information only.</p>
      <ul className="flex flex-col divide-y">
        {cases.slice(0, 5).map((item) => (
          <li key={item.reference} className="flex flex-col gap-1 py-2 first:pt-0 last:pb-0">
            <div className="flex flex-wrap items-center gap-2">
              <Link
                to={`/inbound/customers/${customerId}/cases/${item.reference}`}
                className="font-mono font-medium text-primary underline-offset-4 hover:underline"
              >
                {item.reference}
              </Link>
              <StatusBadge kind="caseInternal" value={item.status} />
            </div>
            <span className="text-sm">{item.subject}</span>
            <span className="text-sm text-muted-foreground">Created {formatDateTime(item.createdAt)}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}
