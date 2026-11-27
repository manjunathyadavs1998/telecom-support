import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { Receipt } from 'lucide-react'
import { useCallback, useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Link } from 'react-router'
import { z } from 'zod'
import { PageContainer } from '@/components/layout/PageContainer'
import { EmptyState } from '@/components/states/EmptyState'
import { ErrorState } from '@/components/states/ErrorState'
import { FormRootError } from '@/components/states/FormRootError'
import { LoadingState } from '@/components/states/LoadingState'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormField, FormItem, FormMessage } from '@/components/ui/form'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { formatDate, formatInr } from '@/lib/format'
import { ForbiddenPage } from '@/pages/ForbiddenPage'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
import { IDEMPOTENCY_MISMATCH_MESSAGE, isIdempotencyMismatch, useIdempotencyKey, useSubmitGuard } from '@/services/api/idempotency'
import type { Invoice } from '@/types/api'
import { PAYMENT_HISTORY_PATH, paymentStatusPath } from './paymentFormat'
import { TERMINAL_PAYMENT_STATUSES } from './paymentPolling'
import {
  INVOICES_QUERY_KEY,
  fetchPayment,
  PAYMENT_INITIATE_PATH,
  paymentQueryKey,
  useInitiatePayment,
  useOpenInvoicesQuery,
  useStoredPaymentQuery,
} from './queries'
import { redirectTo } from './redirect'

export const PAYMENT_METHODS_INFO = "You'll choose UPI, card, net banking or wallet on the secure payment page."
export const AMOUNT_CHANGED_MESSAGE = 'The amount due has changed.'
export const BILL_GONE_MESSAGE = 'This bill is no longer available.'
export const PROVIDER_UNAVAILABLE_MESSAGE =
  "We couldn't reach the payment provider. Try again in a few minutes. If a payment was started, it will appear in your payment history once confirmed."
export const REDIRECT_REFUSED_TITLE = "We couldn't open the secure payment page"
export const UNDER_REVIEW_MESSAGE = "A payment for this bill is under review. Don't pay again — we'll update you."

const linkClass = 'font-medium underline underline-offset-4'

/** frontend.md §3 Pay bill 409 (a duplicate attempt; a payment known to be under review shows `UnderReviewAlert`, erratum FE1). */
function stillOpenMessage(reference: string | undefined): ReactNode {
  return (
    <>
      A payment for this bill is still open. If you left the payment page, continue it from{' '}
      {reference ? (
        <Link className={linkClass} to={paymentStatusPath(reference)}>
          {reference}
        </Link>
      ) : (
        <Link className={linkClass} to={PAYMENT_HISTORY_PATH}>
          Payment history
        </Link>
      )}
      , or try again in about 15 minutes.
    </>
  )
}

/** Erratum FE1: shown instead of the Pay button while the bill's saved payment is REVIEW (also after a 409 for it). */
function UnderReviewAlert({ reference }: { reference: string }) {
  return (
    <Alert className="flex-col">
      <p>{UNDER_REVIEW_MESSAGE}</p>
      <Link className={linkClass} to={paymentStatusPath(reference)}>
        View payment {reference}
      </Link>
    </Alert>
  )
}

// frontend.md §6 Pay bill: `invoiceId` required.
const payBillSchema = z.object({ invoiceId: z.string().min(1, 'Choose a bill to pay.') })
type PayBillValues = z.infer<typeof payBillSchema>

type RootProblem = { message: ReactNode; error: ApiError | null; retry?: boolean }

/** Oldest due date first (ISO `YYYY-MM-DD` strings compare in date order); the first one is preselected (§3 Pay bill). */
function oldestDueFirst(invoices: Invoice[]): Invoice[] {
  return [...invoices].sort((a, b) => (a.dueDate < b.dueDate ? -1 : a.dueDate > b.dueDate ? 1 : a.id - b.id))
}

function InvoiceDetails({ invoice }: { invoice: Invoice }) {
  const rows: [string, string][] = [
    ['Bill period', `${formatDate(invoice.periodStart)} – ${formatDate(invoice.periodEnd)}`],
    ['Issued', formatDate(invoice.issueDate)],
    ['Due', formatDate(invoice.dueDate)],
    ['Amount due', formatInr(invoice.amountDue)],
    ['Paid', formatInr(invoice.amountPaid)],
    ['Outstanding', formatInr(invoice.outstanding)],
  ]
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
      {rows.map(([term, value]) => (
        <div key={term} className="contents">
          <dt className="text-muted-foreground">{term}</dt>
          <dd className={term === 'Outstanding' ? 'font-semibold' : undefined}>{value}</dd>
        </div>
      ))}
    </dl>
  )
}

/** The form for the loaded open invoices; mounted only once they are known, so the preselection is a default value. */
function PayForm({ invoices, onRefetchInvoices }: { invoices: Invoice[]; onRefetchInvoices: () => void }) {
  const client = useQueryClient()
  const initiate = useInitiatePayment()
  const guard = useSubmitGuard()
  const choiceLabelId = useId()
  const form = useForm<PayBillValues>({
    resolver: zodResolver(payBillSchema),
    defaultValues: { invoiceId: String(invoices[0]!.id) },
  })
  const selectedId = useWatch({ control: form.control, name: 'invoiceId' })
  const selected = invoices.find((invoice) => String(invoice.id) === selectedId)
  // A refetch (404/422) may remove the chosen bill: fall back to the oldest due one.
  useEffect(() => {
    if (!selected) form.setValue('invoiceId', String(invoices[0]!.id))
  }, [selected, invoices, form])

  // frontend.md §4: the E15 key persists per invoice in sessionStorage (scope `invoiceId`) to resume an abandoned checkout.
  const idempotency = useIdempotencyKey('PAYMENT_INITIATE', PAYMENT_INITIATE_PATH, { persist: true, scope: selectedId })
  // Bumped whenever the stored record may have changed: the re-render re-reads it from sessionStorage.
  const [, setRecordVersion] = useState(0)
  const storedReference = idempotency.storedRecord()?.reference
  const storedPayment = useStoredPaymentQuery(storedReference)
  const storedStatus = storedPayment.data?.status

  // Abandoned checkout (F3): only a PENDING payment can be continued. A terminal status (SUCCEEDED/FAILED/EXPIRED, the same set
  // as the status page) drops the record, so "Pay" uses a fresh key. REVIEW is not terminal: the record is kept (erratum FE1).
  useEffect(() => {
    if (storedStatus === undefined || !TERMINAL_PAYMENT_STATUSES.has(storedStatus)) return
    idempotency.reset() // this render already offers no "Continue"; the next one reads no record
  }, [storedStatus, idempotency])

  const [redirecting, setRedirecting] = useState(false)
  const [redirectRefused, setRedirectRefused] = useState(false)
  const [root, setRoot] = useState<RootProblem | null>(null)
  const rootRef = useRef<HTMLDivElement>(null)
  const [rootFocusRequest, setRootFocusRequest] = useState(0)
  useEffect(() => {
    if (rootFocusRequest > 0) rootRef.current?.focus()
  }, [rootFocusRequest])
  const reviewRef = useRef<HTMLDivElement>(null)
  const [reviewFocusRequest, setReviewFocusRequest] = useState(0)
  useEffect(() => {
    if (reviewFocusRequest > 0) reviewRef.current?.focus()
  }, [reviewFocusRequest])

  // bfcache (F4): back from the payment page, the restored page is usable again and shows fresh data.
  const refetchStoredPayment = useCallback(() => {
    void client.invalidateQueries({ queryKey: paymentQueryKey(idempotency.storedRecord()?.reference ?? '') })
  }, [client, idempotency])
  useEffect(() => {
    const onPageShow = (event: PageTransitionEvent) => {
      if (!event.persisted) return
      setRedirecting(false)
      guard.reset()
      setRecordVersion((n) => n + 1)
      onRefetchInvoices()
      refetchStoredPayment()
    }
    window.addEventListener('pageshow', onPageShow)
    return () => window.removeEventListener('pageshow', onPageShow)
  }, [guard, onRefetchInvoices, refetchStoredPayment])

  function showRoot(problem: RootProblem) {
    setRoot(problem)
    setRootFocusRequest((n) => n + 1)
  }

  /** Erratum FE1: after a 409, re-reads the saved payment; true when it is under review (its alert then replaces Pay). */
  async function savedPaymentUnderReview(): Promise<boolean> {
    const reference = idempotency.storedRecord()?.reference
    if (reference === undefined) return false
    try {
      const payment = await client.fetchQuery({
        queryKey: paymentQueryKey(reference),
        queryFn: () => fetchPayment(reference),
        staleTime: 0,
      })
      return payment.status === 'REVIEW'
    } catch {
      return false
    }
  }

  async function showServerError(error: unknown) {
    if (!(error instanceof ApiError)) {
      showRoot({ message: GENERIC_ERROR_MESSAGE, error: null })
      return
    }
    if (error.status === 403) return // the global mutation handler shows the forbidden toast (01 §7)
    if (isIdempotencyMismatch(error)) {
      showRoot({ message: IDEMPOTENCY_MISMATCH_MESSAGE, error })
      return
    }
    switch (error.status) {
      case 409:
        if (await savedPaymentUnderReview()) {
          setRecordVersion((n) => n + 1)
          setReviewFocusRequest((n) => n + 1)
          return
        }
        showRoot({ message: stillOpenMessage(idempotency.storedRecord()?.reference), error })
        return
      case 422:
        showRoot({ message: AMOUNT_CHANGED_MESSAGE, error })
        onRefetchInvoices()
        return
      case 404:
        showRoot({ message: BILL_GONE_MESSAGE, error })
        onRefetchInvoices()
        return
      case 503:
        showRoot({ message: PROVIDER_UNAVAILABLE_MESSAGE, error, retry: true })
        return
      default:
        showRoot({ message: GENERIC_ERROR_MESSAGE, error })
    }
  }

  const pay = (invoice: Invoice) =>
    guard.run(async () => {
      setRoot(null)
      // The server's `outstanding` string verbatim: no arithmetic on amounts (F8).
      const body = { invoiceId: invoice.id, expectedAmount: invoice.outstanding }
      const idempotencyKey = idempotency.keyFor(body)
      try {
        const initiated = await initiate.mutateAsync({ idempotencyKey, body })
        idempotency.succeeded(initiated.reference)
        setRecordVersion((n) => n + 1)
        if (redirectTo(initiated.redirectUrl)) setRedirecting(true)
        else setRedirectRefused(true)
      } catch (error) {
        idempotency.failed(error)
        setRecordVersion((n) => n + 1)
        await showServerError(error)
      }
    })

  const onSubmit = form.handleSubmit(
    () => {
      if (selected) void pay(selected)
    },
    () => undefined,
  )

  if (redirectRefused) {
    return <ErrorState title={REDIRECT_REFUSED_TITLE} error={new Error('refused redirect')} />
  }

  const busy = guard.busy || redirecting
  const canContinue = storedReference !== undefined && storedStatus === 'PENDING'
  const underReview = storedReference !== undefined && storedStatus === 'REVIEW'

  return (
    <Form {...form}>
      <form noValidate onSubmit={onSubmit} aria-label="Pay bill" aria-busy={busy} className="flex flex-col gap-4">
        <FormRootError ref={rootRef} message={root?.message} error={root?.error ?? null} />
        {underReview ? (
          <div ref={reviewRef} tabIndex={-1} className="outline-none">
            <UnderReviewAlert reference={storedReference} />
          </div>
        ) : null}
        {root?.retry && selected && !underReview ? (
          <div>
            <Button type="button" variant="outline" disabled={busy} onClick={() => void pay(selected)}>
              Try again
            </Button>
          </div>
        ) : null}
        {canContinue && selected ? (
          <Alert className="flex-col">
            <p>You started a payment for this bill ({storedReference}).</p>
            <Button type="button" disabled={busy} onClick={() => void pay(selected)}>
              Continue payment
            </Button>
          </Alert>
        ) : null}
        {invoices.length > 1 ? (
          <FormField
            control={form.control}
            name="invoiceId"
            render={({ field }) => (
              <FormItem>
                <p id={choiceLabelId} className="text-sm font-medium">
                  Which bill?
                </p>
                <FormControl>
                  <RadioGroup
                    aria-labelledby={choiceLabelId}
                    value={field.value}
                    onValueChange={(value) => {
                      setRoot(null)
                      field.onChange(value)
                    }}
                    disabled={busy}
                    ref={field.ref}
                  >
                    {invoices.map((invoice) => {
                      const itemId = `${choiceLabelId}-${invoice.id}`
                      return (
                        <div key={invoice.id} className="flex items-center gap-2">
                          <RadioGroupItem id={itemId} value={String(invoice.id)} />
                          <Label htmlFor={itemId}>
                            {invoice.invoiceRef} · due {formatDate(invoice.dueDate)} · {formatInr(invoice.outstanding)}
                          </Label>
                        </div>
                      )
                    })}
                  </RadioGroup>
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
        ) : null}
        {selected ? (
          <section aria-label={`Bill ${selected.invoiceRef}`} className="flex flex-col gap-2 rounded-lg border bg-white p-4">
            <h2 className="font-semibold">
              Bill <span className="font-mono">{selected.invoiceRef}</span>
            </h2>
            <InvoiceDetails invoice={selected} />
          </section>
        ) : null}
        <p className="text-sm text-muted-foreground">{PAYMENT_METHODS_INFO}</p>
        {selected && !underReview ? (
          <div>
            <Button type="submit" disabled={busy}>
              {busy ? 'Opening the payment page…' : `Pay ${formatInr(selected.outstanding)}`}
            </Button>
          </div>
        ) : null}
      </form>
    </Form>
  )
}

/**
 * Pay bill `/customer/payments/pay` (frontend.md §3 Pay bill; CUS-08.1/08.2/08.4, NFR-REL, NFR-SEC): the open invoices with
 * the server's outstanding amount, oldest due first; "Pay" starts E15 with a per-invoice key persisted in sessionStorage and
 * sends the browser to the checkout (same-origin or allowlisted only). A stored PENDING payment offers "Continue payment"; a stored
 * REVIEW payment shows the "under review" alert instead of Pay (erratum FE1).
 */
export function PayBillPage() {
  const client = useQueryClient()
  const invoices = useOpenInvoicesQuery()
  const refetchInvoices = useCallback(() => void client.invalidateQueries({ queryKey: INVOICES_QUERY_KEY }), [client])
  const sorted = useMemo(() => oldestDueFirst(invoices.data ?? []), [invoices.data])

  const error = invoices.isError ? invoices.error : null
  if (error instanceof ApiError && error.status === 403) return <ForbiddenPage />

  return (
    <PageContainer title="Pay bill" description="Pay the outstanding amount of a bill.">
      {invoices.isPending ? (
        <LoadingState label="Loading your bills" />
      ) : error ? (
        <ErrorState title="We couldn't load your bills" error={error} onRetry={() => void invoices.refetch()} />
      ) : sorted.length === 0 ? (
        <EmptyState
          icon={<Receipt className="size-8" />}
          title="You have nothing to pay right now"
          action={
            <Button asChild variant="outline">
              <Link to={PAYMENT_HISTORY_PATH}>Payment history</Link>
            </Button>
          }
        />
      ) : (
        <PayForm invoices={sorted} onRefetchInvoices={refetchInvoices} />
      )}
    </PageContainer>
  )
}

/** `route.lazy` entry (pageRegistry). */
export { PayBillPage as Component }
