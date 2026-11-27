import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useRef, useState, type Ref } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { FormRootError } from '@/components/states/FormRootError'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { ApiError, applyProblemToForm, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
import { useSubmitGuard } from '@/services/api/idempotency'
import type { ContactChannel, ContactVerification } from '@/types/api'
import {
  attemptsRemaining,
  CONTACT_COPY,
  contactCodeSchema,
  contactStartSchema,
  type ContactCodeInput,
  type ContactStartInput,
} from './contactSchemas'
import { useConfirmContactVerification, useStartContactVerification } from './queries'

/** "Send a new code" cooldown after every code sent (review F22). */
const RESEND_COOLDOWN_MS = 30_000
/** Used only if a 429 arrives without a usable `Retry-After`. */
const DEFAULT_RETRY_AFTER_SEC = 60
const SEND_FAILED_MESSAGE = "We couldn't send the code. Try again later."

type RootProblem = { message: string; error: ApiError | null }

/** What a failed E3 (start or resend) shows (frontend.md §6 Contact start): 429 throttle, 503 copy, field errors or a root alert. */
type StartFailure = { kind: 'throttled'; seconds: number } | { kind: 'fields'; error: ApiError } | ({ kind: 'root' } & RootProblem)

function startFailure(error: unknown): StartFailure | null {
  if (!(error instanceof ApiError)) return { kind: 'root', message: GENERIC_ERROR_MESSAGE, error: null }
  switch (error.status) {
    case 400:
    case 422:
      return { kind: 'fields', error }
    case 403:
      return null // the global mutation handler shows the forbidden toast (01 §7)
    case 429:
      return { kind: 'throttled', seconds: error.retryAfterSec ?? DEFAULT_RETRY_AFTER_SEC }
    case 503:
      return { kind: 'root', message: SEND_FAILED_MESSAGE, error }
    default:
      return { kind: 'root', message: GENERIC_ERROR_MESSAGE, error }
  }
}

const mmss = (seconds: number) => `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
const secondsUntil = (deadline: number, now: number) => Math.max(0, Math.ceil((deadline - now) / 1000))

/** A root alert that takes focus whenever a new problem is shown. */
function useRootFocus() {
  const ref = useRef<HTMLDivElement>(null)
  const [request, setRequest] = useState(0)
  useEffect(() => {
    if (request > 0) ref.current?.focus()
  }, [request])
  return { ref, focus: () => setRequest((n) => n + 1) }
}

/**
 * 429 (frontend.md §6): the alert is announced once with the wait in minutes; the per-second countdown sits outside it in an
 * `aria-live="off"` element so screen readers are not interrupted every second (01 login precedent).
 */
function ThrottledAlert({ seconds, total, rootRef }: { seconds: number; total: number; rootRef: Ref<HTMLDivElement> }) {
  return (
    <div className="flex flex-col gap-1">
      <FormRootError ref={rootRef} message={`Too many codes requested. Try again in ${Math.ceil(total / 60)} min.`} />
      <p aria-live="off" data-testid="contact-retry-countdown" className="text-sm text-muted-foreground">
        Try again in {mmss(seconds)}
      </p>
    </div>
  )
}

type Clock = {
  now: number
  throttledUntil: number
  throttledFor: number
  throttle: (seconds: number) => void
}

/** Step 1: the new value → E3 (frontend.md §3 Profile step 1, §6 Contact start). */
function EnterValueStep({
  channel,
  primaryMsisdn,
  initialValue,
  notice,
  clock,
  onSent,
}: {
  channel: ContactChannel
  primaryMsisdn: string
  initialValue: string
  notice: ApiError | null
  clock: Clock
  onSent: (verification: ContactVerification, value: string) => void
}) {
  const copy = CONTACT_COPY[channel]
  const start = useStartContactVerification()
  const guard = useSubmitGuard()
  const form = useForm<ContactStartInput>({
    resolver: zodResolver(contactStartSchema(channel, primaryMsisdn)),
    defaultValues: { value: initialValue },
  })
  const { control, handleSubmit, setError, setFocus } = form
  const [root, setRoot] = useState<RootProblem | null>(
    notice ? { message: notice.message, error: notice } : null,
  )
  const rootFocus = useRootFocus()
  const throttledSeconds = secondsUntil(clock.throttledUntil, clock.now)

  const onSubmit = handleSubmit((input) =>
    guard.run(async () => {
      setRoot(null)
      try {
        const verification = await start.mutateAsync({ channel, value: input.value })
        onSent(verification, input.value)
      } catch (error) {
        const failure = startFailure(error)
        if (failure === null) return
        if (failure.kind === 'throttled') clock.throttle(failure.seconds)
        else if (failure.kind === 'fields') {
          applyProblemToForm(failure.error, (name, option) => {
            if (name === 'value') setError(name, option)
          }, ['value'] as const)
          if (failure.error.fieldErrors.some((violation) => violation.field === 'value')) {
            setFocus('value')
            return
          }
          setRoot({ message: failure.error.message, error: failure.error })
        } else setRoot(failure)
        rootFocus.focus()
      } finally {
        start.reset() // the contact value leaves the MutationCache (gcTime 0)
      }
    }),
  )

  return (
    <Form {...form}>
      <form noValidate onSubmit={onSubmit} aria-busy={guard.busy} className="flex flex-col gap-4">
        {throttledSeconds > 0 ? (
          <ThrottledAlert seconds={throttledSeconds} total={clock.throttledFor} rootRef={rootFocus.ref} />
        ) : (
          <FormRootError ref={rootFocus.ref} message={root?.message} error={root?.error} />
        )}
        <FormField
          control={control}
          name="value"
          render={({ field }) => (
            <FormItem>
              <FormLabel>{copy.inputLabel}</FormLabel>
              <FormControl>
                {channel === 'EMAIL' ? (
                  <Input type="email" autoComplete="email" inputMode="email" readOnly={guard.busy} {...field} />
                ) : (
                  <Input type="tel" autoComplete="tel" inputMode="tel" readOnly={guard.busy} {...field} />
                )}
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <DialogFooter>
          <Button type="submit" disabled={guard.busy || throttledSeconds > 0}>
            {guard.busy ? 'Sending…' : 'Send code'}
          </Button>
        </DialogFooter>
      </form>
    </Form>
  )
}

/** Step 2: the 6-digit code → E4, with expiry, resend cooldown and attempt handling (frontend.md §3 Profile step 2, §6). */
function EnterCodeStep({
  channel,
  verification,
  value,
  clock,
  onResent,
  onExpiredId,
  onVerified,
}: {
  channel: ContactChannel
  verification: ContactVerification
  value: string
  clock: Clock & { cooldownUntil: number }
  onResent: (verification: ContactVerification) => void
  onExpiredId: (error: ApiError) => void
  onVerified: () => void
}) {
  const copy = CONTACT_COPY[channel]
  const start = useStartContactVerification()
  const confirm = useConfirmContactVerification()
  const confirmGuard = useSubmitGuard()
  const resendGuard = useSubmitGuard()
  const form = useForm<ContactCodeInput>({ resolver: zodResolver(contactCodeSchema), defaultValues: { code: '' } })
  const { control, handleSubmit, setError, setFocus, reset } = form
  const [root, setRoot] = useState<RootProblem | null>(null)
  // Expired or locked (a 422 without attempts left, OQ 5): only "Send a new code" helps.
  const [locked, setLocked] = useState(false)
  const rootFocus = useRootFocus()

  const expiresIn = secondsUntil(Date.parse(verification.expiresAt), clock.now)
  const cooldown = secondsUntil(clock.cooldownUntil, clock.now)
  const throttledSeconds = secondsUntil(clock.throttledUntil, clock.now)
  const busy = confirmGuard.busy || resendGuard.busy

  const onSubmit = handleSubmit((input) =>
    confirmGuard.run(async () => {
      setRoot(null)
      try {
        await confirm.mutateAsync({ verificationId: verification.id, code: input.code })
        toast.success(copy.verifiedToast)
        onVerified()
      } catch (error) {
        if (!(error instanceof ApiError)) {
          setRoot({ message: GENERIC_ERROR_MESSAGE, error: null })
        } else if (error.status === 403) {
          return // forbidden toast (01 §7)
        } else if (error.status === 404) {
          onExpiredId(error)
          return
        } else if (error.status === 422) {
          const left = attemptsRemaining(error)
          if (left !== undefined && left > 0) {
            setError('code', { type: 'server', message: `Incorrect code. ${left} ${left === 1 ? 'attempt' : 'attempts'} left.` })
            setFocus('code')
            return
          }
          setLocked(true)
          setRoot({ message: error.message, error })
        } else if (error.status === 400) {
          applyProblemToForm(error, (name, option) => {
            if (name === 'code') setError(name, option)
          }, ['code'] as const)
          if (error.fieldErrors.some((violation) => violation.field === 'code')) {
            setFocus('code')
            return
          }
          setRoot({ message: error.message, error })
        } else {
          setRoot({ message: GENERIC_ERROR_MESSAGE, error })
        }
        rootFocus.focus()
      } finally {
        confirm.reset() // the code leaves the MutationCache (gcTime 0, NFR-SEC)
      }
    }),
  )

  const resend = () =>
    resendGuard.run(async () => {
      setRoot(null)
      try {
        const next = await start.mutateAsync({ channel, value })
        reset({ code: '' })
        setLocked(false)
        onResent(next)
        setFocus('code')
      } catch (error) {
        const failure = startFailure(error)
        if (failure === null) return
        if (failure.kind === 'throttled') clock.throttle(failure.seconds)
        else if (failure.kind === 'fields') setRoot({ message: failure.error.message, error: failure.error })
        else setRoot(failure)
        rootFocus.focus()
      } finally {
        start.reset()
      }
    })

  // Announced only at 1 min left and at 0 (frontend.md §3 Profile); the visible countdown is not a live region.
  const expiryAnnouncement =
    expiresIn === 0 ? 'The code has expired. Send a new code.' : expiresIn <= 60 ? 'Less than 1 minute left to enter the code.' : ''

  return (
    <Form {...form}>
      <form noValidate onSubmit={onSubmit} aria-busy={busy} className="flex flex-col gap-4">
        <p className="text-sm">
          We sent a 6-digit code to <strong className="break-all">{verification.maskedDestination}</strong>.
        </p>
        {throttledSeconds > 0 ? (
          <ThrottledAlert seconds={throttledSeconds} total={clock.throttledFor} rootRef={rootFocus.ref} />
        ) : (
          <FormRootError ref={rootFocus.ref} message={root?.message} error={root?.error} />
        )}
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
                  disabled={locked}
                  readOnly={busy}
                  className="sm:max-w-40"
                  {...field}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <p className="text-sm text-muted-foreground" data-testid="code-expiry">
          {expiresIn > 0 ? `Code expires in ${mmss(expiresIn)}` : 'The code has expired.'}
        </p>
        <p aria-live="polite" className="sr-only">
          {expiryAnnouncement}
        </p>
        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => void resend()}
            disabled={busy || cooldown > 0 || throttledSeconds > 0}
          >
            {resendGuard.busy ? 'Sending…' : cooldown > 0 ? `Send a new code (${cooldown} s)` : 'Send a new code'}
          </Button>
          <Button type="submit" disabled={busy || locked}>
            {confirmGuard.busy ? 'Verifying…' : 'Verify'}
          </Button>
        </DialogFooter>
      </form>
    </Form>
  )
}

type Step =
  | { kind: 'value'; value: string; notice: ApiError | null }
  | { kind: 'code'; verification: ContactVerification; value: string }

/** The dialog body; mounted only while the dialog is open, so closing it drops every step, code and timer. */
function ContactChangeFlow({
  channel,
  title,
  primaryMsisdn,
  onVerified,
}: {
  channel: ContactChannel
  title: string
  primaryMsisdn: string
  onVerified: () => void
}) {
  const copy = CONTACT_COPY[channel]
  const [step, setStep] = useState<Step>({ kind: 'value', value: '', notice: null })
  const [now, setNow] = useState(() => Date.now())
  const [cooldownUntil, setCooldownUntil] = useState(0)
  const [throttledUntil, setThrottledUntil] = useState(0)
  const [throttledFor, setThrottledFor] = useState(0)

  // One clock for the expiry, resend cooldown and 429 countdowns.
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])

  const clock: Clock = {
    now,
    throttledUntil,
    throttledFor,
    throttle: (seconds) => {
      const at = Date.now()
      setNow(at)
      setThrottledFor(seconds)
      setThrottledUntil(at + seconds * 1000)
    },
  }

  function codeSent(verification: ContactVerification, value: string) {
    const at = Date.now()
    setNow(at)
    setCooldownUntil(at + RESEND_COOLDOWN_MS)
    setStep({ kind: 'code', verification, value })
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle>{title}</DialogTitle>
        <DialogDescription>
          {step.kind === 'value'
            ? `We'll send a 6-digit code to the new ${copy.destination}. It changes only after you enter the code.`
            : `Enter the code to verify your new ${copy.destination}.`}
        </DialogDescription>
      </DialogHeader>
      {step.kind === 'value' ? (
        <EnterValueStep
          channel={channel}
          primaryMsisdn={primaryMsisdn}
          initialValue={step.value}
          notice={step.notice}
          clock={clock}
          onSent={codeSent}
        />
      ) : (
        <EnterCodeStep
          channel={channel}
          verification={step.verification}
          value={step.value}
          clock={{ ...clock, cooldownUntil }}
          onResent={(verification) => codeSent(verification, step.value)}
          onExpiredId={(error) => setStep({ kind: 'value', value: step.value, notice: error })}
          onVerified={onVerified}
        />
      )}
    </>
  )
}

/**
 * "Change" / "Add" for a contact row and its verification dialog (frontend.md §3 Profile, §4, §6; CUS-02.2, NFR-SEC): new value
 * → E3 → 6-digit code → E4. Focus is trapped and `Esc` closes (Radix); after success the dialog closes and focus returns to
 * the row button. The code never goes into toasts, logs, query keys or the MutationCache after settle.
 */
export function ContactChangeDialog({
  channel,
  hasValue,
  primaryMsisdn,
}: {
  channel: ContactChannel
  hasValue: boolean
  primaryMsisdn: string
}) {
  const [open, setOpen] = useState(false)
  const copy = CONTACT_COPY[channel]
  const action = hasValue ? 'Change' : 'Add'
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="outline" size="sm" aria-label={`${action} ${copy.label}`}>
          {action}
        </Button>
      </DialogTrigger>
      <DialogContent>
        <ContactChangeFlow channel={channel} title={`${action} ${copy.label}`} primaryMsisdn={primaryMsisdn} onVerified={() => setOpen(false)} />
      </DialogContent>
    </Dialog>
  )
}
