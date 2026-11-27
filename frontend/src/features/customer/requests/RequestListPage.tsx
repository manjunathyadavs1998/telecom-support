import { zodResolver } from '@hookform/resolvers/zod'
import { Inbox, Plus } from 'lucide-react'
import { useId, useMemo, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router'
import { z } from 'zod'
import { DataTable, type DataTableColumn } from '@/components/data/DataTable'
import { DateRangeFilter, type DateRangeFilterHandle } from '@/components/data/DateRangeFilter'
import { Pagination } from '@/components/data/Pagination'
import { StatusBadge } from '@/components/data/StatusBadge'
import { INVALID_LINK_NOTICE, LIST_PAGE_SIZE, useListParams, type ListParams } from '@/components/data/useListParams'
import { PageContainer } from '@/components/layout/PageContainer'
import { EmptyState } from '@/components/states/EmptyState'
import { ErrorState } from '@/components/states/ErrorState'
import { LoadingState } from '@/components/states/LoadingState'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { formatDate, formatDateTime } from '@/lib/format'
import { ForbiddenPage } from '@/pages/ForbiddenPage'
import { Can } from '@/permissions/Can'
import { ApiError } from '@/services/api/errors'
import type { CasePage, CaseSummary } from '@/types/api'
import { REQUEST_LIST_PARAMS, requestReferenceSchema, useCasesQuery } from './queries'

export const REFERENCE_SEARCH_HINT = 'Searches within the selected dates'

const NEW_REQUEST_PATH = '/customer/requests/new'

// frontend.md §6 Reference search: an empty box clears the search.
const searchSchema = z.object({ reference: z.union([z.literal(''), requestReferenceSchema]) })
type SearchValues = z.infer<typeof searchSchema>

const COLUMNS: DataTableColumn<CaseSummary>[] = [
  { id: 'reference', header: 'Reference', cell: (row) => <span className="font-mono">{row.reference}</span> },
  {
    id: 'category',
    header: 'Category',
    cell: (row) => (row.subcategoryName ? `${row.categoryName} › ${row.subcategoryName}` : row.categoryName),
  },
  { id: 'created', header: 'Created (IST)', mobileLabel: 'Created', cell: (row) => formatDateTime(row.createdAt) },
  { id: 'severity', header: 'Severity', cell: (row) => <StatusBadge kind="severity" value={row.severity} /> },
  { id: 'status', header: 'Status', cell: (row) => <StatusBadge kind="case" value={row.status} /> },
]

/** The reference search (frontend.md §3 Service requests, §6; OQ 6): searched inside the selected dates. */
function ReferenceSearch({ value, onSearch }: { value?: string; onSearch: (reference?: string) => void }) {
  const hintId = useId()
  const values = useMemo(() => ({ reference: value ?? '' }), [value])
  const form = useForm<SearchValues>({ resolver: zodResolver(searchSchema), values })
  const onSubmit = form.handleSubmit(
    ({ reference }) => onSearch(reference || undefined),
    () => undefined,
  )
  return (
    <Form {...form}>
      <form noValidate aria-label="Search requests" onSubmit={onSubmit} className="flex flex-col gap-1">
        <div className="flex flex-col gap-2 sm:flex-row sm:items-start">
          <FormField
            control={form.control}
            name="reference"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Reference</FormLabel>
                <FormControl aria-describedby={hintId}>
                  <Input placeholder="SR-20261125-000042" autoComplete="off" className="sm:w-60" {...field} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
          <div className="flex gap-2 sm:mt-7">
            <Button type="submit" variant="outline">
              Search
            </Button>
            {value ? (
              <Button type="button" variant="ghost" onClick={() => onSearch(undefined)}>
                Clear search
              </Button>
            ) : null}
          </div>
        </div>
        <p id={hintId} className="text-sm text-muted-foreground">
          {REFERENCE_SEARCH_HINT}
        </p>
      </form>
    </Form>
  )
}

function emptyTitle(params: ListParams): string {
  if (params.reference) return `No request ${params.reference} in these dates`
  if (params.from && params.to) return `No requests between ${formatDate(params.from)} and ${formatDate(params.to)}`
  return 'No requests in the last 3 months'
}

function NewRequestLink({ variant = 'default' }: { variant?: 'default' | 'outline' }) {
  return (
    <Can allOf={['case.create']}>
      <Button asChild variant={variant}>
        <Link to={NEW_REQUEST_PATH}>
          <Plus aria-hidden="true" className="size-4" />
          New request
        </Link>
      </Button>
    </Can>
  )
}

/**
 * Service requests `/customer/requests` (frontend.md §3 Service requests; CUS-05, BR-05/06, XC-04): the own requests in the last
 * 3 months (default, no dates sent) or a custom range, a reference search inside the window, 20 per page, newest first. Filters
 * and the 1-based page live in the URL. A 400 on a filter change shows the server's message under the filters and keeps the
 * last good page; a 403 → `ForbiddenPage`; any other error → `ErrorState` with retry.
 */
export function RequestListPage() {
  const { params, apiQuery, invalidNotice, setFilters, setPage } = useListParams(REQUEST_LIST_PARAMS)
  const query = useCasesQuery(apiQuery)
  const filterRef = useRef<DateRangeFilterHandle>(null)

  // The last page the server answered for the current or an earlier filter: kept on screen after a 400 (§3).
  const fresh = query.isSuccess && !query.isPlaceholderData ? query.data : undefined
  const [lastGood, setLastGood] = useState<CasePage | undefined>(fresh)
  if (fresh && fresh !== lastGood) setLastGood(fresh)

  const error = query.isError ? query.error : null
  if (error instanceof ApiError && error.status === 403) return <ForbiddenPage />
  const filterError = error instanceof ApiError && error.status === 400 ? error : null
  const data: CasePage | undefined = query.isError ? (filterError ? lastGood : undefined) : query.data

  const range = params.from && params.to ? { from: params.from, to: params.to } : {}

  return (
    <PageContainer title="Service requests" description="Your requests and their status." actions={<NewRequestLink />}>
      <div className="flex flex-col gap-4 rounded-lg border bg-white p-4 lg:flex-row lg:items-start lg:justify-between">
        <DateRangeFilter
          ref={filterRef}
          value={params}
          onApply={(next) => setFilters({ ...next, reference: params.reference })}
          onReset={() => setFilters({ reference: params.reference })}
        />
        <ReferenceSearch value={params.reference} onSearch={(reference) => setFilters({ ...range, reference })} />
      </div>
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
      {query.isPending ? (
        <LoadingState label="Loading requests" />
      ) : error && !filterError ? (
        <ErrorState title="We couldn't load your requests" error={error} onRetry={() => void query.refetch()} />
      ) : !data ? null : data.totalItems === 0 && params.page === 1 ? (
        <EmptyState
          icon={<Inbox className="size-8" />}
          title={emptyTitle(params)}
          action={
            <div className="flex flex-wrap justify-center gap-2">
              <Button variant="outline" onClick={() => filterRef.current?.openCustomRange()}>
                Change dates
              </Button>
              <NewRequestLink />
            </div>
          }
        />
      ) : (
        <div className="flex flex-col gap-4">
          {data.items.length > 0 ? (
            <DataTable
              columns={COLUMNS}
              rows={data.items}
              caption="Service requests, newest first"
              busy={query.isPlaceholderData}
              rowKey={(row) => row.reference}
            />
          ) : null}
          <Pagination
            page={params.page}
            totalPages={data.totalPages}
            totalItems={data.totalItems}
            size={LIST_PAGE_SIZE}
            onPage={setPage}
          />
        </div>
      )}
    </PageContainer>
  )
}

/** `route.lazy` entry (pageRegistry). */
export { RequestListPage as Component }
