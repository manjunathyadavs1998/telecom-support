import { History, TriangleAlert } from 'lucide-react'
import { useRef, useState } from 'react'
import { DataTable, type DataTableColumn } from '@/components/data/DataTable'
import { DateRangeFilter, type DateRangeFilterHandle } from '@/components/data/DateRangeFilter'
import { Pagination } from '@/components/data/Pagination'
import { StatusBadge } from '@/components/data/StatusBadge'
import { INVALID_LINK_NOTICE, LIST_PAGE_SIZE, useListParams } from '@/components/data/useListParams'
import { EmptyState } from '@/components/states/EmptyState'
import { LoadingState } from '@/components/states/LoadingState'
import { SectionErrorState } from '@/components/states/SectionErrorState'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { formatDateTime } from '@/lib/format'
import { endReasonText, failureReasonText } from '@/lib/inboundText'
import { ApiError } from '@/services/api/errors'
import type { Reactivation, ReactivationPage } from '@/types/api'
import { historyRowId, useReactivationHistoryQuery } from './queries'

/** §3.9 History status detail (honest copy, INB-04.4/04.5, BR-08). */
function statusDetail(row: Reactivation): string {
  switch (row.status) {
    case 'PENDING':
      return 'Restoration not confirmed yet; checked automatically'
    case 'ACTIVE':
      return row.expiresAt ? `Until ${formatDateTime(row.expiresAt)}` : 'Restored'
    case 'EXPIRING':
      return 'Time is up; suspension is being confirmed with the provider'
    case 'EXPIRED':
    case 'ENDED':
      return endReasonText(row.endReason)
    case 'FAILED':
      return failureReasonText(row.failureReason)
    default:
      return ''
  }
}

const COLUMNS: DataTableColumn<Reactivation>[] = [
  {
    id: 'reference',
    header: 'Reference',
    cell: (row) => (
      <span id={historyRowId(row.reference)} className="font-mono">
        {row.reference}
      </span>
    ),
  },
  {
    id: 'status',
    header: 'Status',
    cell: (row) => (
      <span className="flex flex-col gap-1">
        <StatusBadge kind="reactivation" value={row.status} />
        <span className="text-sm text-muted-foreground">{statusDetail(row)}</span>
        {row.needsAttention ? (
          <Badge className="gap-1 border-warning/30 bg-warning/10 text-warning-foreground">
            <TriangleAlert aria-hidden="true" className="size-3.5" />
            Needs attention: operations alerted; automatic checks continue
          </Badge>
        ) : null}
      </span>
    ),
  },
  { id: 'numbers', header: 'Numbers', cell: (row) => row.msisdns.join(', ') },
  { id: 'hours', header: 'Hours', cell: (row) => String(row.requestedHours) },
  { id: 'by', header: 'Requested by', cell: (row) => row.requestedByName },
  { id: 'requested', header: 'Requested (IST)', mobileLabel: 'Requested', cell: (row) => formatDateTime(row.createdAt) },
  {
    id: 'active',
    header: 'Active from–until',
    cell: (row) =>
      row.activatedAt ? `${formatDateTime(row.activatedAt)} – ${row.expiresAt ? formatDateTime(row.expiresAt) : '…'}` : '—',
  },
  {
    id: 'ended',
    header: 'Ended / outcome',
    cell: (row) => (row.endedAt ? formatDateTime(row.endedAt) : row.status === 'FAILED' ? 'Not restored' : '—'),
  },
]

/**
 * Reactivation history (03-inbound/frontend.md §3.9; INB-04.4/04.5): every inbound agent; 3-month default window, URL filters
 * and paging; polls while page 1 holds an in-flight row (OQ 3).
 */
export function ReactivationHistory({ customerId, enabled }: { customerId: number; enabled: boolean }) {
  const { params, apiQuery, invalidNotice, setFilters, setPage } = useListParams()
  const query = useReactivationHistoryQuery(customerId, apiQuery, enabled)
  const filterRef = useRef<DateRangeFilterHandle>(null)

  const fresh = query.isSuccess && !query.isPlaceholderData ? query.data : undefined
  const [lastGood, setLastGood] = useState<ReactivationPage | undefined>(fresh)
  if (fresh && fresh !== lastGood) setLastGood(fresh)
  const error = query.isError ? query.error : null
  const filterError = error instanceof ApiError && error.status === 400 ? error : null
  const data = query.isError ? (filterError ? lastGood : undefined) : query.data

  return (
    <div className="flex flex-col gap-4">
      <DateRangeFilter ref={filterRef} value={params} onApply={(next) => setFilters(next)} onReset={() => setFilters({})} />
      {invalidNotice ? (
        <Alert asChild>
          <output className="block">{INVALID_LINK_NOTICE}</output>
        </Alert>
      ) : null}
      {filterError ? (
        <Alert variant="destructive" className="block">
          {filterError.message}
        </Alert>
      ) : null}
      {error && !filterError ? (
        <SectionErrorState title="We couldn't load the history" error={error} onRetry={() => void query.refetch()} />
      ) : !data ? (
        <LoadingState label="Loading history" />
      ) : data.totalItems === 0 && params.page === 1 ? (
        <EmptyState
          icon={<History className="size-8" />}
          title={params.from && params.to ? 'No restorations in these dates' : 'No restorations in the last 3 months'}
          action={
            <Button variant="outline" onClick={() => filterRef.current?.openCustomRange()}>
              Change dates
            </Button>
          }
        />
      ) : (
        <div className="flex flex-col gap-4">
          {data.items.length > 0 ? (
            <DataTable
              columns={COLUMNS}
              rows={data.items}
              caption="Temporary restorations, newest first"
              busy={query.isPlaceholderData}
              rowKey={(row) => row.reference}
            />
          ) : null}
          <Pagination page={params.page} totalPages={data.totalPages} totalItems={data.totalItems} size={LIST_PAGE_SIZE} onPage={setPage} />
        </div>
      )}
    </div>
  )
}
