import { Printer } from 'lucide-react'
import { Link, useParams } from 'react-router'
import { PageContainer } from '@/components/layout/PageContainer'
import { ErrorState } from '@/components/states/ErrorState'
import { LoadingState } from '@/components/states/LoadingState'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { formatDateTime, formatInr } from '@/lib/format'
import { ForbiddenPage } from '@/pages/ForbiddenPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { ApiError } from '@/services/api/errors'
import type { Receipt } from '@/types/api'
import { PAYMENT_HISTORY_PATH, paymentMethodLabel, paymentStatusPath } from './paymentFormat'
import { useReceiptQuery } from './queries'

export const RECEIPT_UNAVAILABLE_MESSAGE = 'A receipt is available only for successful payments.'

function ReceiptDocument({ receipt }: { receipt: Receipt }) {
  const rows: [string, string, boolean?][] = [
    ['Customer', receipt.customerName],
    ['Account', receipt.accountRef, true],
    ['Invoice', receipt.invoiceRef, true],
    ['Payment reference', receipt.paymentReference, true],
    ['Transaction id', receipt.gatewayTransactionId, true],
    ['Method', paymentMethodLabel(receipt.method)],
    ['Amount', formatInr(receipt.amount)],
    ['Paid at (IST)', formatDateTime(receipt.paidAt)],
  ]
  return (
    <section
      aria-label={`Receipt for ${receipt.paymentReference}`}
      className="flex max-w-xl flex-col gap-4 rounded-lg border bg-white p-6 print:max-w-none print:border-0 print:p-0"
    >
      <h2 className="text-lg font-semibold">Payment receipt</h2>
      <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-sm">
        {rows.map(([term, value, mono]) => (
          <div key={term} className="contents">
            <dt className="text-muted-foreground">{term}</dt>
            <dd className={mono ? 'font-mono' : undefined}>{value}</dd>
          </div>
        ))}
      </dl>
    </section>
  )
}

/**
 * Receipt `/customer/payments/:reference/receipt` (frontend.md §3 Receipt; CUS-07.4, CUS-08.5): E17 rendered as a printable
 * page; "Print or save as PDF" opens the browser's print dialog (OQ 1). The shell's sidebar and header and the page's buttons
 * are `print:hidden`. A payment that did not succeed (422) links to its status page.
 */
export function ReceiptPage() {
  const { reference = '' } = useParams()
  const query = useReceiptQuery(reference)
  const error = query.error

  if (error instanceof ApiError && error.status === 403) return <ForbiddenPage />
  if (error instanceof ApiError && error.status === 404) return <NotFoundPage />

  const actions = query.data ? (
    <div className="flex flex-wrap gap-2 print:hidden">
      <Button type="button" onClick={() => window.print()}>
        <Printer aria-hidden="true" className="size-4" />
        Print or save as PDF
      </Button>
      <Button asChild variant="outline">
        <Link to={PAYMENT_HISTORY_PATH}>Payment history</Link>
      </Button>
    </div>
  ) : undefined

  return (
    <PageContainer title="Receipt" description={`Payment ${reference}`} actions={actions}>
      {query.data ? (
        <ReceiptDocument receipt={query.data} />
      ) : error instanceof ApiError && error.status === 422 ? (
        <Alert className="flex-col">
          <p>{RECEIPT_UNAVAILABLE_MESSAGE}</p>
          <Link className="font-medium underline underline-offset-4" to={paymentStatusPath(reference)}>
            View the payment status
          </Link>
        </Alert>
      ) : error ? (
        <ErrorState title="We couldn't load this receipt" error={error} onRetry={() => void query.refetch()} />
      ) : (
        <LoadingState label="Loading your receipt" />
      )}
    </PageContainer>
  )
}

/** `route.lazy` entry (pageRegistry). */
export { ReceiptPage as Component }
