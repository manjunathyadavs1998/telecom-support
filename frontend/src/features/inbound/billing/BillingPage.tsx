import { Receipt, Wallet } from 'lucide-react'
import { useId, useRef, useState, type ReactNode } from 'react'
import { DataTable, type DataTableColumn } from '@/components/data/DataTable'
import { DateRangeFilter, type DateRangeFilterHandle } from '@/components/data/DateRangeFilter'
import { Pagination } from '@/components/data/Pagination'
import { StatusBadge } from '@/components/data/StatusBadge'
import { INVALID_LINK_NOTICE, LIST_PAGE_SIZE, useListParams, type ListParams } from '@/components/data/useListParams'
import { EmptyState } from '@/components/states/EmptyState'
import { LoadingState } from '@/components/states/LoadingState'
import { SectionErrorState } from '@/components/states/SectionErrorState'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { paymentMethodLabel } from '@/features/customer/payments/paymentFormat'
import { formatDate, formatDateTime, formatInr } from '@/lib/format'
import { ApiError } from '@/services/api/errors'
import type { Invoice, Payment, PaymentPage } from '@/types/api'
import { useAgentInvoicesQuery, useVerifiedCustomer } from '../workspace/queries'
import { useAgentPaymentsQuery } from './queries'

function Section({ title, children }: { title: string; children: ReactNode }) {
  const headingId = useId()
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4 enter-soft rounded-xl border border-line bg-surface shadow-soft-sm p-4 md:p-6">
      <h2 id={headingId} className="text-lg font-semibold text-foreground">
        {title}
      </h2>
      {children}
    </section>
  )
}

const INVOICE_COLUMNS: DataTableColumn<Invoice>[] = [
  { id: 'invoice', header: 'Invoice', cell: (row) => <span className="font-mono">{row.invoiceRef}</span> },
  { id: 'period', header: 'Period', cell: (row) => `${formatDate(row.periodStart)} – ${formatDate(row.periodEnd)}` },
  { id: 'issued', header: 'Issued', cell: (row) => formatDate(row.issueDate) },
  { id: 'due', header: 'Due', cell: (row) => formatDate(row.dueDate) },
  { id: 'amount', header: 'Amount', cell: (row) => formatInr(row.amountDue) },
  { id: 'paid', header: 'Paid', cell: (row) => formatInr(row.amountPaid) },
  { id: 'outstanding', header: 'Outstanding', cell: (row) => formatInr(row.outstanding) },
  { id: 'status', header: 'Status', cell: (row) => (row.status === 'PAID' ? 'Paid' : 'Open') },
]

// §3.5: no receipt, pay or retry actions for an agent (backend R7).
const PAYMENT_COLUMNS: DataTableColumn<Payment>[] = [
  { id: 'reference', header: 'Reference', cell: (row) => <span className="font-mono">{row.reference}</span> },
  { id: 'invoice', header: 'Invoice', cell: (row) => <span className="font-mono">{row.invoiceRef}</span> },
  { id: 'date', header: 'Date/time (IST)', mobileLabel: 'Date/time', cell: (row) => formatDateTime(row.initiatedAt) },
  { id: 'amount', header: 'Amount', cell: (row) => formatInr(row.amount) },
  { id: 'method', header: 'Method', cell: (row) => paymentMethodLabel(row.method) },
  { id: 'status', header: 'Status', cell: (row) => <StatusBadge kind="payment" value={row.status} /> },
]

function InvoicesSection() {
  const { customerId, verified } = useVerifiedCustomer()
  const query = useAgentInvoicesQuery(customerId, undefined, verified)
  return (
    <Section title="Invoices">
      {query.isError ? (
        <SectionErrorState title="We couldn't load the invoices" error={query.error} onRetry={() => void query.refetch()} />
      ) : !query.data ? (
        <LoadingState label="Loading invoices" />
      ) : query.data.length === 0 ? (
        <EmptyState icon={<Receipt className="size-8" />} title="No invoices yet" />
      ) : (
        <DataTable columns={INVOICE_COLUMNS} rows={query.data} caption="Latest 12 invoices" rowKey={(row) => String(row.id)} />
      )}
    </Section>
  )
}

function emptyPaymentsTitle(params: ListParams): string {
  if (params.from && params.to) return `No payments between ${formatDate(params.from)} and ${formatDate(params.to)}`
  return 'No payments in the last 3 months'
}

function PaymentsSection() {
  const { customerId, verified } = useVerifiedCustomer()
  const { params, apiQuery, invalidNotice, setFilters, setPage } = useListParams()
  const query = useAgentPaymentsQuery(customerId, apiQuery, verified)
  const filterRef = useRef<DateRangeFilterHandle>(null)

  // A 400 on a filter change keeps the last good page (02 Payment history rules).
  const fresh = query.isSuccess && !query.isPlaceholderData ? query.data : undefined
  const [lastGood, setLastGood] = useState<PaymentPage | undefined>(fresh)
  if (fresh && fresh !== lastGood) setLastGood(fresh)
  const error = query.isError ? query.error : null
  const filterError = error instanceof ApiError && error.status === 400 ? error : null
  const data = query.isError ? (filterError ? lastGood : undefined) : query.data

  return (
    <Section title="Payments">
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
        <SectionErrorState title="We couldn't load the payments" error={error} onRetry={() => void query.refetch()} />
      ) : !data ? (
        <LoadingState label="Loading payments" />
      ) : data.totalItems === 0 && params.page === 1 ? (
        <EmptyState
          icon={<Wallet className="size-8" />}
          title={emptyPaymentsTitle(params)}
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
              columns={PAYMENT_COLUMNS}
              rows={data.items}
              caption="Payments, newest first"
              busy={query.isPlaceholderData}
              rowKey={(row) => row.reference}
            />
          ) : null}
          <Pagination page={params.page} totalPages={data.totalPages} totalItems={data.totalItems} size={LIST_PAGE_SIZE} onPage={setPage} />
        </div>
      )}
    </Section>
  )
}

/** Billing `…/billing` (03-inbound/frontend.md §3.5; INB-01.3, BR-05/06): invoices and payments, read-only. */
export function BillingPage() {
  return (
    <div className="flex flex-col gap-6">
      <InvoicesSection />
      <PaymentsSection />
    </div>
  )
}

export { BillingPage as Component }
