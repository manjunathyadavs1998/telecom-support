import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { FormRootError } from '@/components/states/FormRootError'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
import { useSubmitGuard } from '@/services/api/idempotency'
import { FORBIDDEN_ACTION_MESSAGE } from '@/app/queryClient'
import type { CachedCheck } from '../verificationState'
import { useConfirmIdentityCheck } from './queries'
import { checkAttemptsRemaining, checkReason, otpSchema, type OtpInput, mmss, secondsUntil, useRootFocus, type RootProblem } from './verifySchemas'

type OtpStepProps = {
  customerId: number
  check: CachedCheck
  /** Client clock (ticks every second) and the resend cooldown / 429 deadlines. */
  now: number
  cooldownUntil: number
  throttled: boolean
  /** A resend in flight or a start error, owned by the panel (it re-runs I2). */
  resendBusy: boolean
  resendProblem: RootProblem | null
  onResend: () => void
  /** 404 on confirm: the check is gone → back to Method (§3.2). */
  onCheckGone: () => void
  onVerified: (check: CachedCheck) => void
}

/**
 * OTP confirm (03-inbound/frontend.md §3.2): "Code sent to … · expires in mm:ss", 6-digit input → I3. 422 `wrong_code` with
 * attempts left → field; `expired`/`locked` (or no attempts left) → root `detail`, input disabled, only "Send a new code"
 * helps (30 s cooldown). The code is never kept (mutation `gcTime: 0`, `reset`) or shown in toasts (NFR-SEC).
 */
export function OtpStep(props: OtpStepProps) {
  const { customerId, check, now, cooldownUntil, throttled, resendBusy, resendProblem, onResend, onCheckGone, onVerified } = props
  const confirm = useConfirmIdentityCheck(customerId)
  const guard = useSubmitGuard()
  const form = useForm<OtpInput>({ resolver: zodResolver(otpSchema), defaultValues: { code: '' } })
  const { control, handleSubmit, setError, setFocus } = form
  const [root, setRoot] = useState<RootProblem | null>(null)
  const [locked, setLocked] = useState(false)
  const [lockedCheckId, setLockedCheckId] = useState(check.id)
  const rootFocus = useRootFocus()

  // A new code (new check id) unlocks the input and clears the old problem.
  if (lockedCheckId !== check.id) {
    setLockedCheckId(check.id)
    setLocked(false)
    setRoot(null)
    form.reset({ code: '' })
  }

  const expiresAt = check.expiresAt ? Date.parse(check.expiresAt) - check.serverOffsetMs : now
  const expiresIn = secondsUntil(expiresAt, now)
  const cooldown = secondsUntil(cooldownUntil, now)
  const busy = guard.busy || resendBusy

  const onSubmit = handleSubmit((input) =>
    guard.run(async () => {
      setRoot(null)
      try {
        const verified = await confirm.mutateAsync({ checkId: check.id, code: input.code })
        onVerified(verified)
        return
      } catch (error) {
        if (!(error instanceof ApiError)) {
          setRoot({ message: GENERIC_ERROR_MESSAGE, error: null })
        } else if (error.status === 404) {
          onCheckGone()
          return
        } else if (error.status === 422) {
          const left = checkAttemptsRemaining(error)
          if (checkReason(error) === 'wrong_code' && left !== undefined && left > 0) {
            setError('code', { type: 'server', message: `Incorrect code. ${left} ${left === 1 ? 'attempt' : 'attempts'} left.` })
            setFocus('code')
            return
          }
          setLocked(true)
          setRoot({ message: error.message, error })
        } else if (error.status === 400) {
          const violation = error.fieldErrors.find((item) => item.field === 'code')
          if (violation) {
            setError('code', { type: 'server', message: violation.message })
            setFocus('code')
            return
          }
          setRoot({ message: error.message, error })
        } else if (error.status === 403) {
          setRoot({ message: FORBIDDEN_ACTION_MESSAGE, error })
        } else {
          setRoot({ message: GENERIC_ERROR_MESSAGE, error })
        }
        rootFocus.focus()
      } finally {
        confirm.reset()
      }
    }),
  )

  const shown = resendProblem ?? root
  const expiryAnnouncement =
    expiresIn === 0 ? 'The code has expired. Send a new code.' : expiresIn <= 60 ? 'Less than 1 minute left to enter the code.' : ''

  return (
    <Form {...form}>
      <form noValidate onSubmit={onSubmit} aria-busy={busy} className="flex flex-col gap-4">
        <p className="text-sm">
          Code sent to <strong className="break-all">{check.maskedDestination ?? 'the primary mobile'}</strong>
          <span aria-hidden="true"> · </span>
          <span className="text-muted-foreground">{expiresIn > 0 ? `expires in ${mmss(expiresIn)}` : 'expired'}</span>
        </p>
        <p aria-live="polite" className="sr-only">
          {expiryAnnouncement}
        </p>
        <FormRootError ref={rootFocus.ref} message={shown?.message} error={shown?.error} />
        <FormField
          control={control}
          name="code"
          render={({ field }) => (
            <FormItem>
              <FormLabel>6-digit code</FormLabel>
              <FormControl>
                <Input
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  maxLength={6}
                  disabled={locked || expiresIn === 0}
                  readOnly={busy}
                  className="sm:max-w-40"
                  {...field}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <div className="flex flex-wrap gap-2">
          <Button type="submit" disabled={busy || locked || expiresIn === 0}>
            {guard.busy ? 'Verifying…' : 'Verify'}
          </Button>
          <Button type="button" variant="outline" onClick={onResend} disabled={busy || cooldown > 0 || throttled}>
            {resendBusy ? 'Sending…' : cooldown > 0 ? `Send a new code (${cooldown} s)` : 'Send a new code'}
          </Button>
        </div>
      </form>
    </Form>
  )
}
