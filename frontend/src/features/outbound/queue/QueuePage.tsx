import { Inbox, RefreshCw } from 'lucide-react'
import { useId, useRef, useState, type ReactNode } from 'react'
import { Link, useLocation } from 'react-router'
import type { DateRangeFilterHandle } from '@/components/data/DateRangeFilter'
import { Pagination } from '@/components/data/Pagination'
import { INVALID_LINK_NOTICE, LIST_PAGE_SIZE } from '@/components/data/useListParams'
import { PageContainer } from '@/components/layout/PageContainer'
import { EmptyState } from '@/components/states/EmptyState'
import { ErrorState } from '@/components/states/ErrorState'
import { LoadingState } from '@/components/states/LoadingState'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { formatDate } from '@/lib/format'
import { cn } from '@/lib/utils'
import { usePermissions } from '@/permissions/usePermissions'
import { QUEUE_SORTS } from '@/lib/outboundText'
import { ApiError } from '@/services/api/errors'
import { useOutboundQueueStore } from '@/stores/outboundQueueStore'
import type { OutboundCasePage, OutboundCaseSummary, QueueSort } from '@/types/api'
import { useChangedBookmarks } from '../bookmarks/queries'
import { ClaimButton, type ActionNotice } from '../case/ClaimButton'
import { QueueFilters } from './QueueFilters'
import { QueueTable } from './QueueTable'
import { useQueueAgents, useQueueCategories, useQueueSearch, useQueueSummary } from './queries'
import { SummaryTiles } from './SummaryTiles'
import { activeTile, EMPTY_FILTERS, useQueueParams } from './useQueueParams'

type QueuePageProps = {
  /** F7: claim button per row (renders nothing when the row can't be claimed). */
  rowActions?: (row: OutboundCaseSummary, report: (notice: ReactNode) => void) => ReactNode
  /** F12: bookmark notice above the queue. */
  notices?: ReactNode
}

/**
 * Queue `/outbound` (04-outbound/frontend.md §3.1; OUT-01, OUT-02, OUT-03.1): summary tiles (filter shortcuts), filters in the URL
 * (mobile in memory only), total + echoed window, sort, paging and the case table. A 400 keeps the last good page (03 §6).
 */
export function QueuePageView({ rowActions, notices }: QueuePageProps) {
  const location = useLocation()
  const sortId = useId()
  const { params, invalidNotice, setFilters, setPage } = useQueueParams()
  const msisdn = useOutboundQueueStore((state) => state.msisdn)
  const summary = useQueueSummary({ from: params.from, to: params.to })
  const query = useQueueSearch(params)
  const categories = useQueueCategories()
  const agents = useQueueAgents()
  const createdRef = useRef<DateRangeFilterHandle>(null)
  const [notice, setNotice] = useState<ReactNode>(null)

  const fresh = query.isSuccess && !query.isPlaceholderData ? query.data : undefined
  const [lastGood, setLastGood] = useState<OutboundCasePage | undefined>(fresh)
  if (fresh && fresh !== lastGood) setLastGood(fresh)
  const error = query.isError ? query.error : null
  const filterError = error instanceof ApiError && error.status === 400 ? error : null
  const data = query.isError ? (filterError ? lastGood : undefined) : query.data

  const { page: _page, ...filters } = params
  const anyFilter =
    params.status.length + params.severity.length > 0 || !!params.category || !!params.assignee || !!params.ref ||
    params.overdue || !!msisdn || !!(params.ufrom && params.uto) || !!(params.from && params.to)
  const from = location.pathname + location.search
  const created = data?.filters.created

  return (
    <PageContainer title="Case queue" description="Cases in your queue: your own, your team's and the unassigned pool.">
      {summary.data ? (
        <section aria-label="Summary" className="flex flex-col gap-2">
          <SummaryTiles
            summary={summary.data}
            active={activeTile(filters)}
            onShortcut={(shortcut) => setFilters({ ...filters, ...shortcut })}
          />
          <p className="text-sm text-muted-foreground">
            Counts for cases created {formatDate(summary.data.window.from)} – {formatDate(summary.data.window.to)} (IST)
          </p>
        </section>
      ) : summary.isError ? (
        <Alert variant="destructive" className="block">
          We couldn&apos;t load the counts.
        </Alert>
      ) : (
        <LoadingState label="Loading counts" />
      )}

      {notices}
      <div aria-live="polite">{notice}</div>

      <QueueFilters
        params={params}
        setFilters={setFilters}
        categories={categories.data ?? []}
        agents={agents.data ?? []}
        createdRef={createdRef}
      />
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

      <div className="flex flex-wrap items-end justify-between gap-3">
        <p className="text-sm text-muted-foreground">
          {data ? `${data.totalItems} matching ${data.totalItems === 1 ? 'case' : 'cases'}` : ' '}
          {created ? ` · Created ${formatDate(created.from)} – ${formatDate(created.to)}${params.from ? '' : ' (default)'}` : ''}
        </p>
        <div className="flex items-end gap-2">
          <div className="flex flex-col gap-1">
            <Label htmlFor={sortId}>Sort</Label>
            <Select value={params.sort} onValueChange={(value) => setFilters({ ...filters, sort: value as QueueSort })}>
              <SelectTrigger id={sortId} className="w-48">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {QUEUE_SORTS.map((sort) => (
                  <SelectItem key={sort.value} value={sort.value}>
                    {sort.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              void query.refetch()
              void summary.refetch()
            }}
          >
            <RefreshCw aria-hidden="true" className="size-4" />
            Refresh
          </Button>
        </div>
      </div>

      {error && !filterError ? (
        <ErrorState title="We couldn't load the queue" error={error} onRetry={() => void query.refetch()} />
      ) : !data ? (
        <LoadingState label="Loading cases" />
      ) : data.totalItems === 0 && params.page === 1 ? (
        <EmptyState
          icon={<Inbox className="size-8" />}
          title={anyFilter ? 'No cases match these filters' : 'No cases in your queue in the last 3 months'}
          action={
            anyFilter ? (
              <Button
                variant="outline"
                onClick={() => {
                  useOutboundQueueStore.getState().clear()
                  setFilters(EMPTY_FILTERS)
                }}
              >
                Clear all
              </Button>
            ) : (
              <Button variant="outline" onClick={() => createdRef.current?.openCustomRange()}>
                Change dates
              </Button>
            )
          }
        />
      ) : (
        <div className="flex flex-col gap-4">
          {data.items.length > 0 ? (
            <QueueTable
              rows={data.items}
              busy={query.isPlaceholderData}
              from={from}
              actions={rowActions ? (row) => rowActions(row, setNotice) : undefined}
            />
          ) : null}
          <Pagination page={params.page} totalPages={data.totalPages} totalItems={data.totalItems} size={LIST_PAGE_SIZE} onPage={setPage} />
        </div>
      )}
    </PageContainer>
  )
}

const NOTICE_TONE: Record<ActionNotice['tone'], string> = {
  success: 'border-success/30 bg-success/10',
  warning: 'border-warning/30 bg-warning/10',
  error: 'border-destructive/40 bg-destructive/5 text-destructive',
}

/** §3.3: a row is claimable when unassigned, not resolved/closed, and the actor holds `case.assign` (server re-checks). */
function claimable(row: OutboundCaseSummary): boolean {
  return row.assignedUserId == null && row.status !== 'RESOLVED' && row.status !== 'CLOSED'
}

/** The route's page: row claim (F7) with its notice above the refetched table (L3). */
export function QueuePage() {
  const location = useLocation()
  const { can } = usePermissions()
  const rowActions = can(['case.assign'])
    ? (row: OutboundCaseSummary, report: (notice: ReactNode) => void) =>
        claimable(row) ? (
          <ClaimButton
            reference={row.reference}
            size="sm"
            onNotice={(n) =>
              report(
                <Alert className={cn('block', NOTICE_TONE[n.tone])}>
                  <p>
                    {n.text}
                    {n.reference ? (
                      <>
                        {' '}
                        <Link
                          to={`/outbound/cases/${n.reference}`}
                          state={{ from: location.pathname + location.search }}
                          className="font-medium text-primary hover:underline"
                        >
                          Open
                        </Link>
                      </>
                    ) : null}
                  </p>
                </Alert>,
              )
            }
          />
        ) : null
    : undefined
  return <QueuePageView rowActions={rowActions} notices={<BookmarkNotice />} />
}

/** §3.4 / OQ 5: "{n} bookmarked case(s) updated" above the queue, from O12 `changedCount`; no nav badge. */
function BookmarkNotice() {
  const { can } = usePermissions()
  const changed = useChangedBookmarks(can(['case.bookmark']))
  const n = changed.data?.changedCount ?? 0
  if (n === 0) return null
  return (
    <Alert asChild className="block">
      <output>
        {n} bookmarked {n === 1 ? 'case' : 'cases'} updated since you last looked.{' '}
        <Link to="/outbound/bookmarks" className="font-medium text-primary hover:underline">
          View bookmarks
        </Link>
      </output>
    </Alert>
  )
}

export { QueuePage as Component }
