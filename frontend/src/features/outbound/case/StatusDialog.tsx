import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useId, useMemo, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { toast } from 'sonner'
import { statusLabel } from '@/components/data/statusLabel'
import { FormRootError } from '@/components/states/FormRootError'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { Textarea } from '@/components/ui/textarea'
import { caseProblemText, closureTypeText } from '@/lib/outboundText'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
import { useSubmitGuard } from '@/services/api/idempotency'
import type { CaseStatus, OutboundCaseDetail } from '@/types/api'
import { outboundKeys } from '../queue/queries'
import { problemReason, useChangeStatus } from './queries'
import { highRisk, remarksRequired, REMARKS_MAX, statusSchema, VERIFICATION_WINDOW_MS, type StatusInput } from './statusSchema'

const CONSEQUENCE: Partial<Record<CaseStatus, string>> = {
  RESOLVED: 'The customer will see Resolved. It closes automatically after the verification window.',
  CLOSED: 'The customer will see Closed. It can be reopened within 7 days.',
  REOPENED: 'The customer will see In progress again.',
}

const label = (value: string) => statusLabel({ kind: 'caseInternal', value: value as CaseStatus })

/**
 * Status change (O8; 04-outbound/frontend.md §3.3; OUT-05, XC-06, XC-07): targets come only from `allowedActions.transitions`;
 * Resolved, Closed and Reopened get a review step. The form stays mounted through refetches (L2); on 409 the dialog returns to
 * step 1 with the input kept.
 */
export function StatusDialog({ detail }: { detail: OutboundCaseDetail }) {
  const queryClient = useQueryClient()
  const id = useId()
  const [open, setOpen] = useState(false)
  /** When the dialog opened: the verification-window hint is computed against it (not `Date.now()` during render). */
  const [openedAt, setOpenedAt] = useState(0)
  const [step, setStep] = useState<'edit' | 'review'>('edit')
  const [root, setRoot] = useState<{ message: string; error: ApiError | null } | null>(null)
  const change = useChangeStatus(detail.reference)
  const guard = useSubmitGuard()
  const allowed = detail.allowedActions.transitions
  const schema = useMemo(() => statusSchema(allowed), [allowed])
  const form = useForm<StatusInput, unknown, { toStatus: CaseStatus; closureType: 'CUSTOMER_CONFIRMED' | 'WINDOW_ELAPSED' | null; remarks: string | null }>({
    resolver: zodResolver(schema),
    defaultValues: { toStatus: '', closureType: '', remarks: '' },
  })
  const toStatus = useWatch({ control: form.control, name: 'toStatus' })
  const closureType = useWatch({ control: form.control, name: 'closureType' })
  const remarks = useWatch({ control: form.control, name: 'remarks' })
  const windowElapsed =
    detail.resolvedAt != null && Date.parse(detail.resolvedAt) + VERIFICATION_WINDOW_MS <= openedAt
  const notAllowed = toStatus !== '' && !allowed.includes(toStatus as CaseStatus)

  const submit = form.handleSubmit((values) =>
    guard.run(async () => {
      setRoot(null)
      try {
        const version = queryClient.getQueryData<OutboundCaseDetail>(outboundKeys.case(detail.reference))?.version ?? detail.version
        await change.mutateAsync({ ...values, version })
        toast.success(`Status changed to ${label(values.toStatus)}`)
        setOpen(false)
        setStep('edit')
        form.reset({ toStatus: '', closureType: '', remarks: '' })
      } catch (error) {
        const reason = problemReason(error)
        setStep('edit')
        if (reason === 'closure_condition_unmet') {
          form.setError('closureType', { type: 'server', message: caseProblemText(reason) ?? '' })
        } else if (error instanceof ApiError && error.status === 400) {
          for (const v of error.fieldErrors) {
            if (v.field === 'remarks' || v.field === 'closureType' || v.field === 'toStatus') {
              form.setError(v.field, { type: 'server', message: v.message })
            }
          }
          if (error.fieldErrors.length === 0) setRoot({ message: error.message, error })
        } else if (error instanceof ApiError && (error.status === 403 || reason === 'unassigned')) {
          setRoot({ message: caseProblemText('unassigned') ?? GENERIC_ERROR_MESSAGE, error })
        } else if (reason) {
          setRoot({ message: caseProblemText(reason) ?? (error as ApiError).message, error: error as ApiError })
        } else {
          setRoot({ message: error instanceof ApiError ? error.message : GENERIC_ERROR_MESSAGE, error: error instanceof ApiError ? error : null })
        }
        if (reason === 'stale_version' || reason === 'invalid_transition' || reason === 'unassigned' || reason === 'reopen_window_expired') {
          void queryClient.invalidateQueries({ queryKey: outboundKeys.case(detail.reference) })
        }
      }
    }),
  )

  const next = form.handleSubmit(() => setStep('review'))

  return (
    <Dialog
      open={open}
      onOpenChange={(value) => {
        setOpen(value)
        if (value) setOpenedAt(Date.now())
        else setStep('edit')
      }}
    >
      <DialogTrigger asChild>
        <Button type="button" variant="outline">
          Change status
        </Button>
      </DialogTrigger>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{step === 'review' ? `Move ${detail.reference} to ${label(toStatus)}?` : 'Change status'}</DialogTitle>
          <DialogDescription>Current status: {label(detail.status)}</DialogDescription>
        </DialogHeader>
        <Form {...form}>
          <form noValidate onSubmit={step === 'review' || !highRisk(toStatus) ? submit : next} className="flex flex-col gap-4">
            <FormRootError message={root?.message} error={root?.error ?? null} />
            {step === 'edit' ? (
              <>
                <FormField
                  control={form.control}
                  name="toStatus"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>New status</FormLabel>
                      <RadioGroup value={field.value} onValueChange={field.onChange} className="flex flex-col gap-2">
                        {allowed.map((value) => (
                          <div key={value} className="flex items-center gap-2">
                            <RadioGroupItem id={`${id}-${value}`} value={value} />
                            <Label htmlFor={`${id}-${value}`}>{label(value)}</Label>
                          </div>
                        ))}
                      </RadioGroup>
                      {notAllowed ? <p className="text-sm text-destructive">Not allowed from {label(detail.status)}</p> : null}
                      <FormMessage />
                    </FormItem>
                  )}
                />
                {toStatus === 'CLOSED' ? (
                  <FormField
                    control={form.control}
                    name="closureType"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>How was closure confirmed?</FormLabel>
                        <RadioGroup value={field.value} onValueChange={field.onChange} className="flex flex-col gap-2">
                          <div className="flex items-center gap-2">
                            <RadioGroupItem id={`${id}-cc`} value="CUSTOMER_CONFIRMED" />
                            <Label htmlFor={`${id}-cc`}>Customer confirmed</Label>
                          </div>
                          {windowElapsed ? (
                            <div className="flex items-center gap-2">
                              <RadioGroupItem id={`${id}-we`} value="WINDOW_ELAPSED" />
                              <Label htmlFor={`${id}-we`}>Verification window elapsed</Label>
                            </div>
                          ) : null}
                        </RadioGroup>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                ) : null}
                {toStatus !== '' ? (
                  <FormField
                    control={form.control}
                    name="remarks"
                    render={({ field }) => (
                      <FormItem>
                        <FormLabel>
                          {toStatus === 'RESOLVED' ? 'Resolution remarks' : toStatus === 'REOPENED' ? 'Reason' : toStatus === 'CLOSED' && closureType === 'CUSTOMER_CONFIRMED' ? 'How did the customer confirm?' : 'Remarks (optional)'}
                        </FormLabel>
                        <FormControl>
                          <Textarea rows={4} maxLength={REMARKS_MAX} {...field} />
                        </FormControl>
                        <p className="text-sm text-muted-foreground">
                          {remarksRequired(toStatus, closureType) ? `Required, ${remarks.length} / ${REMARKS_MAX}. ` : `${remarks.length} / ${REMARKS_MAX}. `}
                          {toStatus === 'RESOLVED' ? 'Internal. To tell the customer, add a customer comment.' : 'Internal note.'}
                        </p>
                        <FormMessage />
                      </FormItem>
                    )}
                  />
                ) : null}
              </>
            ) : (
              <div className="flex flex-col gap-2 text-sm">
                <p>
                  From {label(detail.status)} to <strong>{label(toStatus)}</strong>
                  {toStatus === 'CLOSED' && closureType ? ` · ${closureTypeText(closureType)}` : ''}
                </p>
                {remarks.trim() ? <p className="whitespace-pre-wrap break-words rounded border bg-slate-50 p-2">{remarks.trim()}</p> : null}
                <p className="text-muted-foreground">{CONSEQUENCE[toStatus as CaseStatus]}</p>
              </div>
            )}
            <DialogFooter>
              {step === 'review' ? (
                <Button type="button" variant="outline" onClick={() => setStep('edit')}>
                  Back
                </Button>
              ) : null}
              <Button type="submit" disabled={guard.busy || notAllowed}>
                {guard.busy ? 'Saving…' : step === 'review' ? `Confirm ${label(toStatus)}` : highRisk(toStatus) ? 'Review' : 'Change status'}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  )
}
