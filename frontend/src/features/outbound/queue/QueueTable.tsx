import { Bookmark } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { DataTable, type DataTableColumn } from '@/components/data/DataTable'
import { StatusBadge } from '@/components/data/StatusBadge'
import { statusLabel } from '@/components/data/statusLabel'
import { Badge } from '@/components/ui/badge'
import { formatDateTime } from '@/lib/format'
import type { OutboundCaseSummary } from '@/types/api'

const linkClass = 'font-mono font-medium text-primary underline-offset-4 hover:underline'

type QueueTableProps = {
  rows: OutboundCaseSummary[]
  busy: boolean
  /** Where the detail's back link returns (the queue URL with its filters, §2). */
  from: string
  /** Row actions (claim, F7). */
  actions?: (row: OutboundCaseSummary) => ReactNode
}

/** The queue rows (04-outbound/frontend.md §3.1 columns; OUT-01.1, OUT-04.4): masked mobile only, IST times, text badges. */
export function QueueTable({ rows, busy, from, actions }: QueueTableProps) {
  const columns: DataTableColumn<OutboundCaseSummary>[] = [
    {
      id: 'reference',
      header: 'Case ID',
      cell: (row) => (
        <Link to={`/outbound/cases/${row.reference}`} state={{ from }} className={linkClass}>
          {row.reference}
        </Link>
      ),
    },
    {
      id: 'customer',
      header: 'Customer',
      cell: (row) => (
        <span className="flex flex-col">
          <span>{row.customer.customerName}</span>
          <span className="text-sm text-muted-foreground">
            {row.customer.customerRef} · {row.customer.maskedMsisdn}
          </span>
        </span>
      ),
    },
    {
      id: 'category',
      header: 'Category',
      cell: (row) => (row.subcategoryName ? `${row.categoryName} › ${row.subcategoryName}` : row.categoryName),
    },
    { id: 'severity', header: 'Severity', cell: (row) => <StatusBadge kind="severity" value={row.severity} /> },
    { id: 'created', header: 'Created (IST)', mobileLabel: 'Created', cell: (row) => formatDateTime(row.createdAt) },
    { id: 'owner', header: 'Assigned to', cell: (row) => row.ownerName ?? 'Unassigned' },
    {
      id: 'status',
      header: 'Status',
      cell: (row) => (
        <span className="flex flex-col gap-1">
          <StatusBadge kind="caseInternal" value={row.status} />
          <span className="text-sm text-muted-foreground">
            Customer sees: {statusLabel({ kind: 'case', value: row.publicStatus })}
          </span>
        </span>
      ),
    },
    { id: 'updated', header: 'Last updated (IST)', mobileLabel: 'Last updated', cell: (row) => formatDateTime(row.updatedAt) },
    {
      id: 'sla',
      header: 'SLA',
      cell: (row) =>
        row.overdue ? (
          <Badge className="border-destructive/40 bg-destructive/10 text-destructive">Overdue</Badge>
        ) : row.slaDueAt ? (
          `Due ${formatDateTime(row.slaDueAt)}`
        ) : (
          '—'
        ),
    },
    {
      id: 'actions',
      header: <span className="sr-only">Actions</span>,
      mobileLabel: 'Actions',
      cell: (row) => (
        <span className="flex items-center gap-2">
          {row.bookmarked ? (
            <span className="inline-flex items-center gap-1 text-muted-foreground">
              <Bookmark aria-hidden="true" className="size-4 fill-current" />
              <span className="sr-only">Bookmarked</span>
            </span>
          ) : null}
          {actions?.(row)}
          <Link to={`/outbound/cases/${row.reference}`} state={{ from }} className="text-sm font-medium text-primary hover:underline">
            Open<span className="sr-only"> {row.reference}</span>
          </Link>
        </span>
      ),
    },
  ]
  return <DataTable columns={columns} rows={rows} caption="Case queue" busy={busy} rowKey={(row) => row.reference} />
}
