import { Inbox, Plus } from 'lucide-react'
import { useRef, useState } from 'react'
import { Link } from 'react-router'
import { DataTable, type DataTableColumn } from '@/components/data/DataTable'
import type { DateRangeFilterHandle } from '@/components/data/DateRangeFilter'
import { Pagination } from '@/components/data/Pagination'
import { StatusBadge } from '@/components/data/StatusBadge'
import { statusLabel } from '@/components/data/statusLabel'
import { INVALID_LINK_NOTICE, LIST_PAGE_SIZE, useListParams, type ListParams } from '@/components/data/useListParams'
import { EmptyState } from '@/components/states/EmptyState'
import { LoadingState } from '@/components/states/LoadingState'
import { SectionErrorState } from '@/components/states/SectionErrorState'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { formatDateTime } from '@/lib/format'
import { Can } from '@/permissions/Can'
import { ApiError } from '@/services/api/errors'
import type { AgentCasePage, AgentCaseSummary } from '@/types/api'
import { useVerifiedCustomer } from '../workspace/queries'
import { CaseFilters } from './CaseFilters'
import { CASE_LIST_PARAMS, useAgentCasesQuery } from './queries'

const linkClass = 'font-mono font-medium text-primary underline-offset-4 hover:underline'

function columns(customerId: number): DataTableColumn<AgentCaseSummary>[] {
  return [
    {
      id: 'reference',
      header: 'Reference',
      cell: (row) => (
        <Link to={`/inbound/customers/${customerId}/cases/${row.reference}`} className={linkClass}>
          {row.reference}
        </Link>
      ),
    },
    {
      id: 'category',
      header: 'Category',
      cell: (row) => (row.subcategoryName ? `${row.categoryName} › ${row.subcategoryName}` : row.categoryName),
    },
    { id: 'subject', header: 'Subject', cell: (row) => row.subject },
    { id: 'severity', header: 'Severity', cell: (row) => <StatusBadge kind="severity" value={row.severity} /> },
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
    { id: 'owner', header: 'Owner', cell: (row) => row.ownerName ?? 'Unassigned' },
    { id: 'channel', header: 'Channel', cell: (row) => (row.channel === 'INBOUND_AGENT' ? 'Inbound' : 'Portal') },
    { id: 'created', header: 'Created (IST)', mobileLabel: 'Created', cell: (row) => formatDateTime(row.createdAt) },
    { id: 'updated', header: 'Updated (IST)', mobileLabel: 'Updated', cell: (row) => formatDateTime(row.updatedAt) },
  ]
}

function emptyTitle(params: ListParams): string {
  if (params.reference) return `No request ${params.reference} in these dates`
  if (params.from && params.to) return 'No requests in these dates'
  return 'No requests in the last 3 months'
}

function RaiseRequestLink({ customerId, variant = 'default' }: { customerId: number; variant?: 'default' | 'outline' }) {
  return (
    <Can allOf={['case.create.onbehalf', 'customer.profile.read', 'plan.read']}>
      <Button asChild variant={variant}>
        <Link to={`/inbound/customers/${customerId}/cases/new`}>
          <Plus aria-hidden="true" className="size-4" />
          Raise request
        </Link>
      </Button>
    </Can>
  )
}

/**
 * Cases `…/cases` (03-inbound/frontend.md §3.6; INB-02.1, XC-04/05): the customer's requests with dates, reference, status and
 * sort in the URL, 20 per page. A 400 on a filter change keeps the last good page.
 */
export function CaseListPage() {
  const { customerId, verified } = useVerifiedCustomer()
  const { params, apiQuery, apiFilters, invalidNotice, setFilters, setPage } = useListParams(CASE_LIST_PARAMS)
  const query = useAgentCasesQuery(customerId, { ...apiQuery, ...apiFilters }, verified)
  const filterRef = useRef<DateRangeFilterHandle>(null)

  const fresh = query.isSuccess && !query.isPlaceholderData ? query.data : undefined
  const [lastGood, setLastGood] = useState<AgentCasePage | undefined>(fresh)
  if (fresh && fresh !== lastGood) setLastGood(fresh)
  const error = query.isError ? query.error : null
  const filterError = error instanceof ApiError && error.status === 400 ? error : null
  const data = query.isError ? (filterError ? lastGood : undefined) : query.data

  return (
    <div className="flex flex-col gap-4">
      <CaseFilters params={params} setFilters={setFilters} filterRef={filterRef} />
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
        <SectionErrorState title="We couldn't load the requests" error={error} onRetry={() => void query.refetch()} />
      ) : !data ? (
        <LoadingState label="Loading requests" />
      ) : data.totalItems === 0 && params.page === 1 ? (
        <EmptyState
          icon={<Inbox className="size-8" />}
          title={emptyTitle(params)}
          action={
            <div className="flex flex-wrap justify-center gap-2">
              <Button variant="outline" onClick={() => filterRef.current?.openCustomRange()}>
                Change dates
              </Button>
              <RaiseRequestLink customerId={customerId} variant="outline" />
            </div>
          }
        />
      ) : (
        <div className="flex flex-col gap-4">
          {data.items.length > 0 ? (
            <DataTable
              columns={columns(customerId)}
              rows={data.items}
              caption="Service requests"
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

export { CaseListPage as Component }
