import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { FORBIDDEN_ACTION_MESSAGE } from '@/app/queryClient'
import { FormRootError } from '@/components/states/FormRootError'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { formatDateTime } from '@/lib/format'
import { eligibilityText } from '@/lib/inboundText'
import { ApiError, applyProblemToForm, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
import {
  IDEMPOTENCY_MISMATCH_MESSAGE,
  isIdempotencyMismatch,
  useIdempotencyKey,
  useSubmitGuard,
} from '@/services/api/idempotency'
import type { ReactivationEligibility, ReactivationRequest } from '@/types/api'
import { inboundKeys, isVerificationRequired } from '../verificationState'
import { useVerifiedCustomer } from '../workspace/queries'
import { ConfirmReactivationDialog } from './ConfirmReactivationDialog'
import {
  invalidateReactivation,
  reactivationCreatePath,
  reactivationProblem,
  useCreateReactivation,
} from './queries'
import {
  APPROVAL_MAX,
  REACTIVATION_FIELDS,
  reactivationDefaults,
  reactivationSchema,
  REASON_MAX,
  type ReactivationField,
  type ReactivationInput,
} from './reactivationSchema'

const DIRTY_KEY = 'reactivation'
const VERIFY_FIRST = 'Verify the customer again, then submit. What you typed is kept.'
const VERIFIED_AGAIN = 'Verified again. Submit when ready.'
const ALREADY_OPEN = 'A restoration is already open for this account.'
const UNAVAILABLE = 'The provisioning system is unavailable. Nothing was restored. Try again later.'

type Outcome = { kind: 'root'; message: string; error: ApiError | null; reasons?: string[]; retry?: boolean; verify?: boolean }

/** What the panel shows above the eligibility, so it survives the refetch that ends the form (review M3). */
export type ReactivationNotice = { kind: 'success'; message: string } | { kind: 'pending'; reference: string }

type ReactivationFormProps = {
  customerId: number
  eligibility: ReactivationEligibility
  onNotice: (notice: ReactivationNotice) => void
}

/**
 * The restoration request (03-inbound/frontend.md §3.9, §6; INB-04.1–04.4/04.7, XC-07): hours, reason, approval reference →
 * confirm → I14 with an idempotency key on the concrete path. Every outcome in the §3.9 table is shown as the server reports it;
 * nothing claims success before a 201.
 */
export function ReactivationForm({ customerId, eligibility, onNotice }: ReactivationFormProps) {
  const queryClient = useQueryClient()
  const { reverifiedCount, setDirty } = useVerifiedCustomer()
  const maxHours = eligibility.maxHours ?? 1
  const numbers = eligibility.suspendedMsisdns?.length ?? 0
  const create = useCreateReactivation(customerId)
  const guard = useSubmitGuard()
  const idempotency = useIdempotencyKey('REACTIVATION_CREATE', reactivationCreatePath(customerId))
  const schema = useMemo(() => reactivationSchema(maxHours), [maxHours])
  const form = useForm<ReactivationInput, unknown, ReactivationRequest>({
    resolver: zodResolver(schema),
    defaultValues: reactivationDefaults(maxHours),
  })
  const { control, handleSubmit, setError, setFocus, reset, formState } = form
  const hoursText = useWatch({ control, name: 'hours' })
  const [pendingBody, setPendingBody] = useState<ReactivationRequest | null>(null)
  const [outcome, setOutcome] = useState<Outcome | null>(null)
  const rootRef = useRef<HTMLDivElement>(null)
  const [focusRequest, setFocusRequest] = useState(0)
  useEffect(() => {
    if (focusRequest > 0) rootRef.current?.focus()
  }, [focusRequest])

  useEffect(() => {
    setDirty(DIRTY_KEY, formState.isDirty)
    return () => setDirty(DIRTY_KEY, false)
  }, [formState.isDirty, setDirty])

  const [seenReverify, setSeenReverify] = useState(reverifiedCount)
  if (seenReverify !== reverifiedCount) {
    setSeenReverify(reverifiedCount)
    if (outcome?.kind === 'root' && outcome.verify) setOutcome({ kind: 'root', message: VERIFIED_AGAIN, error: null })
  }

  function show(next: Outcome) {
    setOutcome(next)
    setFocusRequest((n) => n + 1)
  }

  function showServerError(error: unknown) {
    if (!(error instanceof ApiError)) return show({ kind: 'root', message: GENERIC_ERROR_MESSAGE, error: null })
    if (isVerificationRequired(error)) return show({ kind: 'root', message: VERIFY_FIRST, error: null, verify: true })
    if (isIdempotencyMismatch(error)) return show({ kind: 'root', message: IDEMPOTENCY_MISMATCH_MESSAGE, error })
    const problem = reactivationProblem(error)
    switch (error.status) {
      case 400: {
        const mapped: ReactivationField[] = []
        applyProblemToForm<ReactivationField>(
          error,
          (name, option) => {
            if (name === 'root.server') show({ kind: 'root', message: option.message, error })
            else {
              setError(name, option)
              mapped.push(name)
            }
          },
          REACTIVATION_FIELDS,
        )
        const first = REACTIVATION_FIELDS.find((field) => mapped.includes(field))
        if (first) setFocus(first)
        return
      }
      case 403:
        return show({ kind: 'root', message: FORBIDDEN_ACTION_MESSAGE, error })
      case 409:
        invalidateReactivation(queryClient, customerId)
        return show({ kind: 'root', message: ALREADY_OPEN, error })
      case 422: {
        if (problem.reference && problem.reactivationStatus === 'FAILED') {
          invalidateReactivation(queryClient, customerId)
          return show({
            kind: 'root',
            message: `The provisioning system rejected the request (${problem.reference}). Nothing was restored.`,
            error,
          })
        }
        const reasons = problem.reasons ?? []
        if (reasons.includes('HOURS_EXCEED_POLICY')) {
          setError('hours', { type: 'server', message: `At most ${maxHours} hours` })
          setFocus('hours')
        }
        const others = reasons.filter((reason) => reason !== 'HOURS_EXCEED_POLICY')
        void queryClient.invalidateQueries({ queryKey: inboundKeys.eligibility(customerId) })
        if (others.length > 0) {
          return show({
            kind: 'root',
            message: "Service can't be restored:",
            error,
            reasons: others.map((reason) => eligibilityText(reason, eligibility)),
          })
        }
        if (reasons.length === 0) show({ kind: 'root', message: error.message, error })
        return
      }
      case 503:
        if (problem.reference && problem.reactivationStatus === 'PENDING') {
          onNotice({ kind: 'pending', reference: problem.reference })
          invalidateReactivation(queryClient, customerId)
          return
        }
        return show({ kind: 'root', message: UNAVAILABLE, error, retry: true })
      default:
        return show({ kind: 'root', message: GENERIC_ERROR_MESSAGE, error })
    }
  }

  const submit = (body: ReactivationRequest) =>
    guard.run(async () => {
      setOutcome(null)
      const idempotencyKey = idempotency.keyFor(body)
      try {
        const created = await create.mutateAsync({ idempotencyKey, body })
        idempotency.succeeded()
        reset(reactivationDefaults(maxHours))
        setDirty(DIRTY_KEY, false)
        onNotice({
          kind: 'success',
          message:
            created.status === 'ACTIVE' && created.expiresAt
              ? `Service restored until ${formatDateTime(created.expiresAt)} (${created.reference}). The customer will be notified by SMS.`
              : `Request ${created.reference} recorded.`,
        })
        invalidateReactivation(queryClient, customerId)
      } catch (error) {
        // Kept on 403 verification-required, 409 and 5xx (a retry is safe); dropped on 400/404/422.
        idempotency.failed(error)
        showServerError(error)
      }
    })

  const onRequest = handleSubmit((body) => setPendingBody(body))
  const busy = guard.busy
  const hoursShown = Number(hoursText) || maxHours

  return (
    <Form {...form}>
      <form noValidate onSubmit={onRequest} aria-busy={busy} className="flex max-w-xl flex-col gap-4">
        {outcome?.kind === 'root' && outcome.message === VERIFIED_AGAIN ? (
          <Alert asChild>
            <output className="block">{VERIFIED_AGAIN}</output>
          </Alert>
        ) : null}
        {outcome?.kind === 'root' && outcome.message !== VERIFIED_AGAIN ? (
          <FormRootError
            ref={rootRef}
            error={outcome.error}
            message={
              outcome.reasons ? (
                <>
                  {outcome.message}
                  <ul className="mt-1 list-disc pl-5">
                    {outcome.reasons.map((reason) => (
                      <li key={reason}>{reason}</li>
                    ))}
                  </ul>
                </>
              ) : (
                outcome.message
              )
            }
          />
        ) : null}

        <FormField
          control={control}
          name="hours"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Hours (1–{maxHours})</FormLabel>
              <FormControl>
                <Input inputMode="numeric" className="sm:w-32" disabled={busy} {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={control}
          name="reason"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Reason</FormLabel>
              <FormControl>
                <Textarea rows={3} maxLength={REASON_MAX} disabled={busy} {...field} />
              </FormControl>
              <p className="text-sm text-muted-foreground">Explain why. Don&apos;t include personal data.</p>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={control}
          name="approvalReference"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Approval reference (optional)</FormLabel>
              <FormControl>
                <Input maxLength={APPROVAL_MAX} autoComplete="off" disabled={busy} {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <div className="flex flex-wrap gap-2">
          <Button type="submit" disabled={busy}>
            {busy ? 'Requesting…' : 'Request restoration'}
          </Button>
          {outcome?.kind === 'root' && outcome.retry ? (
            <Button type="button" variant="outline" disabled={busy} onClick={() => void handleSubmit((body) => submit(body))()}>
              Retry
            </Button>
          ) : null}
        </div>
      </form>
      <ConfirmReactivationDialog
        open={pendingBody !== null}
        numbers={numbers}
        hours={hoursShown}
        onCancel={() => setPendingBody(null)}
        onConfirm={() => {
          const body = pendingBody
          setPendingBody(null)
          if (body) void submit(body)
        }}
      />
    </Form>
  )
}
