import { useQueryClient } from '@tanstack/react-query'
import { Loader2 } from 'lucide-react'
import { useEffect, useRef, type ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { StatusBadge } from '@/components/data/StatusBadge'
import { PageContainer } from '@/components/layout/PageContainer'
import { ErrorState } from '@/components/states/ErrorState'
import { LoadingState } from '@/components/states/LoadingState'
import { Button } from '@/components/ui/button'
import { formatDateTime, formatInr } from '@/lib/format'
import { ForbiddenPage } from '@/pages/ForbiddenPage'
import { Can } from '@/permissions/Can'
import { ApiError } from '@/services/api/errors'
import { dropIdempotencyRecordsFor } from '@/services/api/idempotency'
import type { Payment } from '@/types/api'
import { PaymentNotFound } from './PaymentNotFound'
import { PAY_BILL_PATH, PAYMENT_HISTORY_PATH, paymentMethodLabel, paymentReceiptPath } from './paymentFormat'
import { TERMINAL_PAYMENT_STATUSES, usePaymentStatusQuery, usePollWindow } from './paymentPolling'
import { INVOICES_QUERY_KEY, PAYMENT_INITIATE_PATH, PAYMENTS_QUERY_KEY } from './queries'

// frontend.md §3 Payment status copy.
export const CONFIRMING_MESSAGE = 'Confirming your payment with the provider…'
export const STILL_WAITING_MESSAGE =
  "Still waiting for confirmation. Don't pay again: we'll update your payment history once the provider confirms."
export const POLL_FAILED_NOTICE = "We couldn't check just now. Retrying…"
/** The same notice once the window is over and nothing retries automatically. */
export const POLL_FAILED_IDLE_NOTICE = "We couldn't check just now."
export const MONEY_TAKEN_NOTE =
  'If money was taken from your account, it will appear in your payment history within 24 hours. Check there before paying again.'
export const EXPIRED_MESSAGE = 'The payment session expired before it was completed.'
export const REVIEW_MESSAGE =
  "Payment under review: we received a result that needs checking. Don't pay again for this bill; the status will update in your payment history."

const HOME_PATH = '/customer'
const NEW_REQUEST_PATH = '/customer/requests/new'

/** FAILED reason (frontend.md §3): only `gateway_unavailable` is named; any other reason stays generic. */
function failureMessage(reason: string | null | undefined): string {
  return reason === 'gateway_unavailable' ? 'The payment provider was unavailable' : 'The payment was not completed'
}

function Details({ payment }: { payment: Payment }) {
  const rows: { term: string; value: ReactNode }[] = [
    { term: 'Reference', value: <span className="font-mono">{payment.reference}</span> },
    { term: 'Invoice', value: <span className="font-mono">{payment.invoiceRef}</span> },
    { term: 'Amount', value: formatInr(payment.amount) },
    { term: 'Status', value: <StatusBadge kind="payment" value={payment.status} /> },
  ]
  if (payment.status === 'SUCCEEDED') {
    rows.push({ term: 'Method', value: paymentMethodLabel(payment.method) })
    if (payment.completedAt) rows.push({ term: 'Completed (IST)', value: formatDateTime(payment.completedAt) })
  }
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
      {rows.map(({ term, value }) => (
        <div key={term} className="contents">
          <dt className="text-muted-foreground">{term}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  )
}

function LinkButton({ to, children, variant }: { to: string; children: ReactNode; variant?: 'outline' }) {
  return (
    <Button asChild variant={variant}>
      <Link to={to}>{children}</Link>
    </Button>
  )
}

type ResultProps = {
  payment: Payment
  windowOpen: boolean
  refetchFailed: boolean
  onCheckAgain: () => void
}

/**
 * One payment as the server last confirmed it (XC-07). Progress text sits in a polite live region; a result (anything but
 * PENDING) leaves that region empty and moves focus to its heading when it replaces PENDING on screen (frontend.md §3).
 */
function PaymentResult({ payment, windowOpen, refetchFailed, onCheckAgain }: ResultProps) {
  const headingRef = useRef<HTMLHeadingElement>(null)
  const previousStatus = useRef(payment.status)
  useEffect(() => {
    if (previousStatus.current === 'PENDING' && payment.status !== 'PENDING') headingRef.current?.focus()
    previousStatus.current = payment.status
  }, [payment.status])

  const pending = payment.status === 'PENDING'
  const progress = pending ? (windowOpen ? CONFIRMING_MESSAGE : STILL_WAITING_MESSAGE) : ''
  const heading = (text: string) => (
    <h2 ref={headingRef} tabIndex={-1} className="text-lg font-semibold focus-visible:outline-none">
      {text}
    </h2>
  )

  let result: ReactNode = null
  let actions: ReactNode = null
  switch (payment.status) {
    case 'PENDING':
      if (!windowOpen) {
        actions = (
          <>
            <Button type="button" onClick={onCheckAgain}>
              Check again
            </Button>
            <LinkButton to={PAYMENT_HISTORY_PATH} variant="outline">
              Payment history
            </LinkButton>
          </>
        )
      }
      break
    case 'SUCCEEDED':
      result = heading('Payment received')
      actions = (
        <>
          {payment.receiptAvailable ? <LinkButton to={paymentReceiptPath(payment.reference)}>View receipt</LinkButton> : null}
          <LinkButton to={HOME_PATH} variant="outline">
            Back to home
          </LinkButton>
        </>
      )
      break
    case 'FAILED':
      result = (
        <>
          {heading('Payment failed')}
          <p>{failureMessage(payment.failureReason)}.</p>
          <p>{MONEY_TAKEN_NOTE}</p>
        </>
      )
      actions = <LinkButton to={PAY_BILL_PATH}>Try again</LinkButton>
      break
    case 'EXPIRED':
      result = (
        <>
          {heading('Payment expired')}
          <p>{EXPIRED_MESSAGE}</p>
          <p>{MONEY_TAKEN_NOTE}</p>
        </>
      )
      actions = <LinkButton to={PAY_BILL_PATH}>Try again</LinkButton>
      break
    case 'REVIEW':
      result = (
        <>
          {heading('Payment under review')}
          <p>{REVIEW_MESSAGE}</p>
        </>
      )
      actions = (
        <>
          <LinkButton to={PAYMENT_HISTORY_PATH}>Payment history</LinkButton>
          <Can allOf={['case.create']}>
            <LinkButton to={NEW_REQUEST_PATH} variant="outline">
              Contact support
            </LinkButton>
          </Can>
        </>
      )
      break
  }

  return (
    <section aria-label={`Payment ${payment.reference}`} className="flex flex-col gap-4 enter-soft rounded-xl border border-line bg-surface shadow-soft-sm p-4">
      {/* <output>: a polite live region (implicit `status` role); phrasing content only. */}
      <output aria-live="polite" className="flex flex-col gap-2">
        {pending ? (
          <span className="flex items-center gap-2 font-medium">
            {windowOpen ? <Loader2 aria-hidden="true" className="size-5 animate-spin text-primary" /> : null}
            {progress}
          </span>
        ) : null}
        {pending && refetchFailed ? (
          <span className="text-sm text-warning">{windowOpen ? POLL_FAILED_NOTICE : POLL_FAILED_IDLE_NOTICE}</span>
        ) : null}
      </output>
      {result ? <div className="flex flex-col gap-2">{result}</div> : null}
      <Details payment={payment} />
      {actions ? <div className="flex flex-wrap gap-2">{actions}</div> : null}
    </section>
  )
}

/**
 * Payment status `/customer/payments/:reference` (frontend.md §3 Payment status; CUS-08.3–08.5, XC-07): the gateway return
 * URL (E4). Only E16 decides what is shown; the browser return and its query string are ignored. Polls every 3 s for 2 min
 * while PENDING, then offers "Check again". A failed poll keeps the last confirmed status with a notice. A terminal status
 * drops the stored E15 record; leaving PENDING (REVIEW too) refreshes the payment lists (and the open invoices on success).
 */
export function PaymentStatusPage() {
  const { reference = '' } = useParams()
  const client = useQueryClient()
  const pollWindow = usePollWindow()
  const query = usePaymentStatusQuery(reference, pollWindow.open)
  const status = query.data?.status

  useEffect(() => {
    if (status === undefined || status === 'PENDING') return
    // Leaving PENDING (REVIEW included) changes the history row, so the lists refresh (review FC6); only a terminal status
    // drops the stored E15 record.
    void client.invalidateQueries({ queryKey: PAYMENTS_QUERY_KEY })
    if (!TERMINAL_PAYMENT_STATUSES.has(status)) return
    dropIdempotencyRecordsFor('PAYMENT_INITIATE', PAYMENT_INITIATE_PATH, reference)
    if (status === 'SUCCEEDED') void client.invalidateQueries({ queryKey: INVOICES_QUERY_KEY })
  }, [status, reference, client])

  const checkAgain = () => {
    pollWindow.restart()
    void query.refetch()
  }

  const error = query.error
  if (!query.data && error instanceof ApiError) {
    if (error.status === 403) return <ForbiddenPage />
  }

  return (
    <PageContainer title={reference} description="Payment status">
      {query.data ? (
        <PaymentResult
          payment={query.data}
          windowOpen={pollWindow.open}
          refetchFailed={query.isRefetchError}
          onCheckAgain={checkAgain}
        />
      ) : error instanceof ApiError && error.status === 404 ? (
        <PaymentNotFound error={error} />
      ) : error ? (
        <ErrorState title="We couldn't load this payment" error={error} onRetry={() => void query.refetch()} />
      ) : (
        <LoadingState label="Loading your payment" />
      )}
    </PageContainer>
  )
}

/** `route.lazy` entry (pageRegistry). */
export { PaymentStatusPage as Component }
