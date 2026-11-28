import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useId, useState } from 'react'
import { toast } from 'sonner'
import { FORBIDDEN_ACTION_MESSAGE } from '@/app/queryClient'
import { FormRootError } from '@/components/states/FormRootError'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
import { useSubmitGuard } from '@/services/api/idempotency'
import { useInboundSessionStore } from '@/stores/inboundSessionStore'
import type { IdentityCheckMethod } from '@/types/api'
import { CustomerSearchForm, NO_MATCH_MESSAGE } from '../search/CustomerSearchForm'
import { inboundKeys, useCustomerMatch, type CachedCheck } from '../verificationState'
import { KbaForm } from './KbaForm'
import { OtpStep } from './OtpStep'
import { useStartIdentityCheck } from './queries'
import { checkAttemptsRemaining, checkReason, type KbaValues, secondsUntil, useRootFocus, type RootProblem } from './verifySchemas'

export const VERIFIED_TOAST = 'Customer verified for 30 minutes'
const SEARCH_EXPIRED = 'The search expired. Enter the number again.'
const SEND_FAILED = "We couldn't send the code. Try again or use security questions."
const KBA_MISMATCH = "The answers don't match our records."
const NO_OTP_DESTINATION = "This customer has no mobile number we can send a code to. Ask the security questions instead."
const KBA_UNAVAILABLE = "Security questions aren't available for this customer. Send a code instead."
/** "Send a new code" cooldown after every code sent (as 02). */
const RESEND_COOLDOWN_MS = 30_000
/** Used only if a 429 arrives without a usable `Retry-After`. */
const DEFAULT_RETRY_AFTER_SEC = 60

type Step =
  | { kind: 'search'; notice: string | null }
  | { kind: 'method'; notice: string | null }
  | { kind: 'otp'; check: CachedCheck }

type VerifyPanelProps = {
  customerId: number
  /** VERIFIED: the check is already in the cache (`inboundKeys.check`); the caller renders the workspace / closes the dialog. */
  onVerified: (check: CachedCheck) => void
}

/**
 * The identity check (03-inbound/frontend.md §3.2; INB-01.2, NFR-SEC): Search step when there is no fresh lookup token (reload,
 * long call; the hit must be this customer), then Method → OTP (I2 + I3) or KBA (I2). Used in place of the workspace and inside
 * `ReverifyDialog`. Codes and answers never reach toasts, logs, keys or storage.
 */
export function VerifyPanel({ customerId, onVerified }: VerifyPanelProps) {
  const queryClient = useQueryClient()
  const headingId = useId()
  const { data: match } = useCustomerMatch(customerId)
  const freshToken = useInboundSessionStore((state) => state.freshToken)
  const dropToken = useInboundSessionStore((state) => state.dropToken)
  const [step, setStep] = useState<Step>(() =>
    freshToken(customerId) ? { kind: 'method', notice: null } : { kind: 'search', notice: null },
  )
  const [method, setMethod] = useState<IdentityCheckMethod>('OTP')
  const [noMatch, setNoMatch] = useState(false)
  const [now, setNow] = useState(() => Date.now())
  const [cooldownUntil, setCooldownUntil] = useState(0)
  const [throttledUntil, setThrottledUntil] = useState(0)
  const [throttledFor, setThrottledFor] = useState(0)
  const [sendProblem, setSendProblem] = useState<RootProblem | null>(null)
  const start = useStartIdentityCheck(customerId)
  const sendGuard = useSubmitGuard()
  const rootFocus = useRootFocus()

  // One clock for the code expiry, resend cooldown and 429 countdowns.
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])

  const throttledSeconds = secondsUntil(throttledUntil, now)
  const throttled = throttledSeconds > 0

  function verified(check: CachedCheck) {
    queryClient.setQueryData<CachedCheck | null>(inboundKeys.check(customerId), check)
    toast.success(VERIFIED_TOAST)
    onVerified(check)
  }

  function throttle(error: ApiError) {
    const seconds = error.retryAfterSec ?? DEFAULT_RETRY_AFTER_SEC
    const at = Date.now()
    setNow(at)
    setThrottledFor(seconds)
    setThrottledUntil(at + seconds * 1000)
  }

  function backToSearch() {
    dropToken(customerId)
    setSendProblem(null)
    setStep({ kind: 'search', notice: SEARCH_EXPIRED })
  }

  /**
   * The shared I2 failures (§3.2 table). Returns the root problem to show, or `null` when the step changed instead (404 →
   * Search; a method the customer can't use → the other method with a notice) or the 429 countdown shows.
   */
  function startFailure(error: unknown): RootProblem | null {
    if (!(error instanceof ApiError)) return { message: GENERIC_ERROR_MESSAGE, error: null }
    switch (error.status) {
      case 404:
        backToSearch()
        return null
      case 429:
        throttle(error)
        return null
      case 403:
        return { message: FORBIDDEN_ACTION_MESSAGE, error }
      case 503:
        return { message: SEND_FAILED, error }
      case 422: {
        const reason = checkReason(error)
        if (reason === 'no_otp_destination') {
          setMethod('KBA')
          setStep({ kind: 'method', notice: NO_OTP_DESTINATION })
          return null
        }
        if (reason === 'kba_unavailable') {
          setMethod('OTP')
          setStep({ kind: 'method', notice: KBA_UNAVAILABLE })
          return null
        }
        if (reason === 'kba_mismatch') {
          const left = checkAttemptsRemaining(error)
          const attempts = left === undefined ? '' : ` ${left} ${left === 1 ? 'attempt' : 'attempts'} left this hour.`
          return { message: `${KBA_MISMATCH}${attempts}`, error }
        }
        return { message: error.message, error }
      }
      default:
        return { message: error.status === 400 ? error.message : GENERIC_ERROR_MESSAGE, error }
    }
  }

  /** I2 OTP: first send and "Send a new code" (a new check each time). */
  const sendCode = () =>
    sendGuard.run(async () => {
      setSendProblem(null)
      const lookupToken = freshToken(customerId)
      if (!lookupToken) {
        backToSearch()
        return
      }
      try {
        const check = await start.mutateAsync({ method: 'OTP', lookupToken })
        const at = Date.now()
        setNow(at)
        setCooldownUntil(at + RESEND_COOLDOWN_MS)
        setStep({ kind: 'otp', check })
      } catch (error) {
        const problem = startFailure(error)
        if (problem) {
          setSendProblem(problem)
          rootFocus.focus()
        }
      } finally {
        start.reset()
      }
    })

  /** I2 KBA: 201 is already VERIFIED. */
  async function submitKba(values: KbaValues): Promise<{ root: RootProblem | null; fields?: ApiError }> {
    const lookupToken = freshToken(customerId)
    if (!lookupToken) {
      backToSearch()
      return { root: null }
    }
    try {
      const check = await start.mutateAsync({ method: 'KBA', lookupToken, ...values })
      verified(check)
      return { root: null }
    } catch (error) {
      if (error instanceof ApiError && error.status === 400) return { root: null, fields: error }
      return { root: startFailure(error) }
    } finally {
      start.reset()
    }
  }

  const throttleAlert = throttled ? (
    <Alert variant="destructive">
      <p>
        Too many verification attempts for this customer.
        <span className="sr-only"> Try again in {Math.ceil(throttledFor / 60)} min.</span>
      </p>
      <p aria-live="off" className="mt-1 text-foreground">
        Try again in {Math.ceil(throttledSeconds / 60)} min.
      </p>
    </Alert>
  ) : null

  return (
    <section aria-labelledby={headingId} className="flex max-w-xl flex-col gap-4 rounded-lg border bg-white p-4 md:p-6">
      <div className="flex flex-col gap-1">
        <h2 id={headingId} className="text-lg font-semibold text-foreground">
          Verify the caller&apos;s identity
        </h2>
        {match ? (
          <p className="text-muted-foreground">
            {match.maskedName} · {match.maskedMsisdn} · Customer {match.customerRef}
          </p>
        ) : null}
      </div>
      {throttleAlert}

      {step.kind === 'search' ? (
        <div className="flex flex-col gap-3">
          <p className="text-sm text-muted-foreground">
            {step.notice ?? "Enter the caller's mobile number to start the identity check."}
          </p>
          <CustomerSearchForm
            expectCustomerId={customerId}
            onResult={(hit) => {
              setNoMatch(hit === null)
              if (hit) setStep({ kind: 'method', notice: null })
            }}
          />
          {noMatch ? (
            <output className="text-sm text-muted-foreground">{NO_MATCH_MESSAGE}</output>
          ) : null}
        </div>
      ) : null}

      {step.kind === 'method' ? (
        <div className="flex flex-col gap-4">
          {step.notice ? (
            <Alert>
              <p>{step.notice}</p>
            </Alert>
          ) : null}
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-2 text-sm font-medium">How will you verify the caller?</legend>
            <RadioGroup value={method} onValueChange={(value) => setMethod(value as IdentityCheckMethod)}>
              <div className="flex items-center gap-2">
                <RadioGroupItem id={`${headingId}-otp`} value="OTP" />
                <Label htmlFor={`${headingId}-otp`}>Send a code to the customer&apos;s primary mobile</Label>
              </div>
              <div className="flex items-center gap-2">
                <RadioGroupItem id={`${headingId}-kba`} value="KBA" />
                <Label htmlFor={`${headingId}-kba`}>Ask security questions</Label>
              </div>
            </RadioGroup>
          </fieldset>
          {method === 'OTP' ? (
            <div className="flex flex-col gap-3">
              <FormRootError ref={rootFocus.ref} message={sendProblem?.message} error={sendProblem?.error} />
              <div>
                <Button type="button" onClick={() => void sendCode()} disabled={sendGuard.busy || throttled}>
                  {sendGuard.busy ? 'Sending…' : 'Send code'}
                </Button>
              </div>
            </div>
          ) : (
            <KbaForm throttled={throttled} onSubmit={submitKba} />
          )}
        </div>
      ) : null}

      {step.kind === 'otp' ? (
        <OtpStep
          customerId={customerId}
          check={step.check}
          now={now}
          cooldownUntil={cooldownUntil}
          throttled={throttled}
          resendBusy={sendGuard.busy}
          resendProblem={sendProblem}
          onResend={() => void sendCode()}
          onCheckGone={() => setStep({ kind: 'method', notice: 'The code is no longer valid. Send a new code.' })}
          onVerified={verified}
        />
      ) : null}
    </section>
  )
}
