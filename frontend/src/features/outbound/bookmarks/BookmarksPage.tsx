import { Bookmark as BookmarkIcon } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { DataTable, type DataTableColumn } from '@/components/data/DataTable'
import { Pagination } from '@/components/data/Pagination'
import { StatusBadge } from '@/components/data/StatusBadge'
import { statusLabel } from '@/components/data/statusLabel'
import { useListParams } from '@/components/data/useListParams'
import { PageContainer } from '@/components/layout/PageContainer'
import { EmptyState } from '@/components/states/EmptyState'
import { ErrorState } from '@/components/states/ErrorState'
import { LoadingState } from '@/components/states/LoadingState'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { formatDateTime } from '@/lib/format'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
import type { Bookmark } from '@/types/api'
import { useBookmarks, useRemoveBookmark } from './queries'

const FROM = '/outbound/bookmarks'
const linkClass = 'font-mono font-medium text-primary underline-offset-4 hover:underline'

/**
 * Bookmarks `/outbound/bookmarks` (04-outbound/frontend.md §3.4; OUT-06.2–06.4): newest first, "Updated" marker + count, cases no
 * longer in scope show their reference only (L1), Remove (not optimistic).
 */
export function BookmarksPage() {
  const { params, setPage } = useListParams()
  const query = useBookmarks(params.page)
  const remove = useRemoveBookmark()
  const [removing, setRemoving] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const data = query.data

  const onRemove = async (reference: string) => {
    setRemoving(reference)
    setError(null)
    try {
      await remove.mutateAsync(reference)
      if (data && data.items.length === 1 && params.page > 1) setPage(params.page - 1)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : GENERIC_ERROR_MESSAGE)
    } finally {
      setRemoving(null)
    }
  }

  const removeButton = (reference: string) => (
    <Button type="button" size="sm" variant="outline" disabled={removing === reference} onClick={() => void onRemove(reference)}>
      Remove<span className="sr-only"> {reference}</span>
    </Button>
  )

  const columns: DataTableColumn<Bookmark>[] = [
    {
      id: 'updated',
      header: <span className="sr-only">Updated</span>,
      mobileLabel: 'Updated',
      cell: (b) => (b.changed ? <Badge className="border-primary/30 bg-primary/10">● Updated</Badge> : null),
    },
    {
      id: 'reference',
      header: 'Case ID',
      cell: (b) =>
        b.accessible ? (
          <Link to={`/outbound/cases/${b.reference}`} state={{ from: FROM }} className={linkClass}>
            {b.reference}
          </Link>
        ) : (
          <span className="flex flex-col">
            <span className="font-mono">{b.reference}</span>
            <span className="text-sm text-muted-foreground">No longer in your queue</span>
          </span>
        ),
    },
    { id: 'customer', header: 'Customer', cell: (b) => b.case?.customer.customerName ?? '' },
    {
      id: 'category',
      header: 'Category',
      cell: (b) => (b.case ? (b.case.subcategoryName ? `${b.case.categoryName} › ${b.case.subcategoryName}` : b.case.categoryName) : ''),
    },
    {
      id: 'status',
      header: 'Status',
      cell: (b) =>
        b.case ? (
          <span className="flex flex-col gap-1">
            <StatusBadge kind="caseInternal" value={b.case.status} />
            <span className="text-sm text-muted-foreground">Customer sees: {statusLabel({ kind: 'case', value: b.case.publicStatus })}</span>
          </span>
        ) : null,
    },
    { id: 'severity', header: 'Severity', cell: (b) => (b.case ? <StatusBadge kind="severity" value={b.case.severity} /> : null) },
    { id: 'owner', header: 'Assigned to', cell: (b) => (b.case ? b.case.ownerName ?? 'Unassigned' : '') },
    { id: 'lastUpdated', header: 'Last updated (IST)', mobileLabel: 'Last updated', cell: (b) => (b.case ? formatDateTime(b.case.updatedAt) : '') },
    { id: 'bookmarked', header: 'Bookmarked on', cell: (b) => formatDateTime(b.bookmarkedAt) },
    { id: 'actions', header: <span className="sr-only">Actions</span>, mobileLabel: 'Actions', cell: (b) => removeButton(b.reference) },
  ]

  return (
    <PageContainer title="Bookmarks" description="Cases you follow. Updated ones are marked until you open them.">
      {data ? (
        <output aria-live="polite" className="block text-sm text-muted-foreground">
          {data.changedCount} updated since you last looked
        </output>
      ) : null}
      {error ? (
        <Alert variant="destructive" className="block">
          {error}
        </Alert>
      ) : null}
      {query.isError && !data ? (
        <ErrorState title="We couldn't load your bookmarks" error={query.error} onRetry={() => void query.refetch()} />
      ) : !data ? (
        <LoadingState label="Loading bookmarks" />
      ) : data.totalItems === 0 ? (
        <EmptyState icon={<BookmarkIcon className="size-8" />} title="No bookmarks yet" description="Use Bookmark on a case to follow it." />
      ) : (
        <div className="flex flex-col gap-4">
          <DataTable columns={columns} rows={data.items} caption="Bookmarked cases" busy={query.isPlaceholderData} rowKey={(b) => b.reference} />
          <Pagination page={params.page} totalPages={data.totalPages} totalItems={data.totalItems} size={20} onPage={setPage} />
        </div>
      )}
    </PageContainer>
  )
}

export { BookmarksPage as Component }
