import { CreditCard, Receipt } from 'lucide-react'
import { useRef, useState } from 'react'
import { Link } from 'react-router'
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
import { formatDate, formatDateTime, formatInr } from '@/lib/format'
import { ForbiddenPage } from '@/pages/ForbiddenPage'
import { Can } from '@/permissions/Can'
import { ApiError } from '@/services/api/errors'
import type { Payment, PaymentPage } from '@/types/api'
import { PAY_BILL_PATH, paymentMethodLabel, paymentReceiptPath, paymentStatusPath } from './paymentFormat'
import { usePaymentsQuery } from './queries'

/** A payment the server has not finished reconciling: its status page tells the customer what happens next (CUS-07.5). */
const OPEN_STATUSES = new Set<Payment['status']>(['PENDING', 'REVIEW'])

function PaymentAction({ payment }: { payment: Payment }) {
  if (payment.receiptAvailable) {
    return (
      <Link className="font-medium text-primary underline-offset-4 hover:underline" to={paymentReceiptPath(payment.reference)}>
        Receipt<span className="sr-only"> for {payment.reference}</span>
      </Link>
    )
  }
  if (OPEN_STATUSES.has(payment.status)) {
    return (
      <Link className="font-medium text-primary underline-offset-4 hover:underline" to={paymentStatusPath(payment.reference)}>
        View<span className="sr-only"> {payment.reference}</span>
      </Link>
    )
  }
  return <span>—</span>
}

const COLUMNS: DataTableColumn<Payment>[] = [
  { id: 'reference', header: 'Reference', cell: (row) => <span className="font-mono">{row.reference}</span> },
  { id: 'invoice', header: 'Invoice', cell: (row) => <span className="font-mono">{row.invoiceRef}</span> },
  { id: 'date', header: 'Date/time (IST)', mobileLabel: 'Date/time', cell: (row) => formatDateTime(row.initiatedAt) },
  { id: 'amount', header: 'Amount', cell: (row) => formatInr(row.amount) },
  { id: 'method', header: 'Method', cell: (row) => paymentMethodLabel(row.method) },
  { id: 'status', header: 'Status', cell: (row) => <StatusBadge kind="payment" value={row.status} /> },
  { id: 'action', header: 'Action', cell: (row) => <PaymentAction payment={row} /> },
]

function emptyTitle(params: ListParams): string {
  if (params.from && params.to) return `No payments between ${formatDate(params.from)} and ${formatDate(params.to)}`
  return 'No payments in the last 3 months'
}

function PayBillLink() {
  return (
    <Can allOf={['payment.initiate']}>
      <Button asChild>
        <Link to={PAY_BILL_PATH}>
          <CreditCard aria-hidden="true" className="size-4" />
          Pay bill
        </Link>
      </Button>
    </Can>
  )
}

/**
 * Payment history `/customer/payments` (frontend.md §3 Payment history; CUS-07, BR-05/06, XC-04): own payments in the last
 * 3 months (default, no dates sent) or a custom range, 20 per page, newest first, with the same URL and paging rules as the
 * service requests. The status is whatever the server reconciled (CUS-07.5): "Receipt" only when the server says one is
 * available, "View" (status page) for PENDING/REVIEW. A 400 on a filter change keeps the last good page.
 */
export function PaymentHistoryPage() {
  const { params, apiQuery, invalidNotice, setFilters, setPage } = useListParams()
  const query = usePaymentsQuery(apiQuery)
  const filterRef = useRef<DateRangeFilterHandle>(null)

  const fresh = query.isSuccess && !query.isPlaceholderData ? query.data : undefined
  const [lastGood, setLastGood] = useState<PaymentPage | undefined>(fresh)
  if (fresh && fresh !== lastGood) setLastGood(fresh)

  const error = query.isError ? query.error : null
  if (error instanceof ApiError && error.status === 403) return <ForbiddenPage />
  const filterError = error instanceof ApiError && error.status === 400 ? error : null
  const data: PaymentPage | undefined = query.isError ? (filterError ? lastGood : undefined) : query.data

  return (
    <PageContainer title="Payment history" description="Your payments and their status." actions={<PayBillLink />}>
      <div className="enter-soft rounded-xl border border-line bg-surface shadow-soft-sm p-4">
        <DateRangeFilter ref={filterRef} value={params} onApply={(next) => setFilters(next)} onReset={() => setFilters({})} />
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
        <LoadingState label="Loading payments" />
      ) : error && !filterError ? (
        <ErrorState title="We couldn't load your payments" error={error} onRetry={() => void query.refetch()} />
      ) : !data ? null : data.totalItems === 0 && params.page === 1 ? (
        <EmptyState
          icon={<Receipt className="size-8" />}
          title={emptyTitle(params)}
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
              caption="Payments, newest first"
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
export { PaymentHistoryPage as Component }
