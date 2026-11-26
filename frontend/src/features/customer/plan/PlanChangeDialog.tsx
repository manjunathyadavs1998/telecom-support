import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { FormRootError } from '@/components/states/FormRootError'
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { formatDate, formatInr } from '@/lib/format'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
import {
  IDEMPOTENCY_MISMATCH_MESSAGE,
  isIdempotencyMismatch,
  useSubmitGuard,
  type IdempotencyKey,
} from '@/services/api/idempotency'
import type { PlanOption, Subscription } from '@/types/api'
import { perMonth } from './planFormat'
import { planChangeBody, useSchedulePlanChange } from './queries'

/** 409: the same key is still being processed by the server (00-overview §7); the key is kept, so a retry is safe. */
export const STILL_PROCESSING_MESSAGE = 'Your plan change is still processing. Try again in a moment.'

type RootProblem = { message: string; error: ApiError | null }

/** What a failed E7 does (frontend.md §6 Plan change + common rule). */
type Outcome = { kind: 'toast-only' } | { kind: 'quote-rejected'; error: ApiError } | ({ kind: 'root' } & RootProblem)

function outcomeOf(error: unknown): Outcome {
  if (!(error instanceof ApiError)) return { kind: 'root', message: GENERIC_ERROR_MESSAGE, error: null }
  if (error.status === 403) return { kind: 'toast-only' } // the global mutation handler shows the forbidden toast (01 §7)
  if (isIdempotencyMismatch(error)) return { kind: 'root', message: IDEMPOTENCY_MISMATCH_MESSAGE, error }
  if (error.status === 422) return { kind: 'quote-rejected', error }
  if (error.status === 409) return { kind: 'root', message: STILL_PROCESSING_MESSAGE, error }
  return { kind: 'root', message: GENERIC_ERROR_MESSAGE, error }
}

type PlanChangeDialogProps = {
  subscription: Subscription
  /** The chosen quote; the dialog is open while it is set. */
  option: PlanOption | null
  /** Scoped to this subscription's E7 path (frontend.md §4); owned by the caller so it survives closing the dialog. */
  idempotency: IdempotencyKey
  onClose: () => void
  /** A business 422 (stale quote, suspended or closed account, change already scheduled): the caller shows it and refetches. */
  onQuoteRejected: (error: ApiError) => void
  /** Lets the caller move focus elsewhere instead of back to the "Choose" button when the dialog closes. */
  onCloseAutoFocus?: (event: Event) => void
}

/**
 * Plan change confirmation (frontend.md §3 My plan, §6; CUS-03.5, XC-06, XC-07, NFR-REL): "Change to {name}?" with the effective
 * date, the charge (shown even when ₹0.00) and the new rental. Nothing is sent before Confirm; one request per click burst
 * (submit guard); success is reported only after the server's 201. The caller keys it by quote, so each quote starts clean.
 */
export function PlanChangeDialog({
  subscription,
  option,
  idempotency,
  onClose,
  onQuoteRejected,
  onCloseAutoFocus,
}: PlanChangeDialogProps) {
  const schedule = useSchedulePlanChange()
  const guard = useSubmitGuard()
  const [root, setRoot] = useState<RootProblem | null>(null)
  const rootRef = useRef<HTMLDivElement>(null)
  const [rootFocusRequest, setRootFocusRequest] = useState(0)
  useEffect(() => {
    if (rootFocusRequest > 0) rootRef.current?.focus()
  }, [rootFocusRequest])

  const busy = guard.busy

  function confirm() {
    if (!option) return
    const body = planChangeBody(option)
    void guard.run(async () => {
      setRoot(null)
      const idempotencyKey = idempotency.keyFor(body)
      try {
        const change = await schedule.mutateAsync({ subscriptionId: subscription.id, idempotencyKey, body })
        idempotency.succeeded()
        toast.success(`Plan change scheduled for ${formatDate(change.effectiveDate)}`)
        onClose()
      } catch (error) {
        idempotency.failed(error)
        const outcome = outcomeOf(error)
        if (outcome.kind === 'toast-only') return
        if (outcome.kind === 'quote-rejected') {
          onClose()
          onQuoteRejected(outcome.error)
          return
        }
        setRoot({ message: outcome.message, error: outcome.error })
        setRootFocusRequest((n) => n + 1)
      }
    })
  }

  const quote = option
  return (
    <AlertDialog
      open={option !== null}
      onOpenChange={(open) => {
        if (!open && !busy) onClose()
      }}
    >
      <AlertDialogContent aria-busy={busy} onCloseAutoFocus={onCloseAutoFocus}>
        {quote ? (
          <>
            <AlertDialogHeader>
              <AlertDialogTitle>Change to {quote.plan.name}?</AlertDialogTitle>
              <AlertDialogDescription>
                The new plan applies to {subscription.msisdn} from the effective date.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <dl className="grid gap-3 sm:grid-cols-3">
              <div className="flex flex-col gap-0.5">
                <dt className="text-sm text-muted-foreground">Effective date</dt>
                <dd className="font-medium">{formatDate(quote.effectiveDate)}</dd>
              </div>
              <div className="flex flex-col gap-0.5">
                <dt className="text-sm text-muted-foreground">Charge</dt>
                <dd className="font-medium">{formatInr(quote.charge)}</dd>
              </div>
              <div className="flex flex-col gap-0.5">
                <dt className="text-sm text-muted-foreground">New rental</dt>
                <dd className="font-medium">{perMonth(quote.plan.monthlyRental)}</dd>
              </div>
            </dl>
            <FormRootError ref={rootRef} message={root?.message} error={root?.error ?? null} />
            <AlertDialogFooter>
              <AlertDialogCancel disabled={busy}>Cancel</AlertDialogCancel>
              <Button type="button" onClick={confirm} disabled={busy}>
                {busy ? 'Confirming…' : 'Confirm change'}
              </Button>
            </AlertDialogFooter>
          </>
        ) : null}
      </AlertDialogContent>
    </AlertDialog>
  )
}
