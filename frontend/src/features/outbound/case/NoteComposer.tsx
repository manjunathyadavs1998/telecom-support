import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { Eye, Lock } from 'lucide-react'
import { useEffect, useId, useMemo, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { FormRootError } from '@/components/states/FormRootError'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { Textarea } from '@/components/ui/textarea'
import { cn } from '@/lib/utils'
import { ApiError, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
import {
  IDEMPOTENCY_MISMATCH_MESSAGE,
  isIdempotencyMismatch,
  useIdempotencyKey,
  useSubmitGuard,
} from '@/services/api/idempotency'
import type { OutboundCaseDetail } from '@/types/api'
import { outboundKeys } from '../queue/queries'
import { problemReason, useAddNote } from './queries'
import { NOTE_MAX, noteSchema, type NoteInput } from './noteSchema'

/**
 * Notes and customer comments (O9; 04-outbound/frontend.md §3.3; OUT-04.1/04.3, BR-04, NFR-REL): options from `allowedActions`;
 * idempotent (`CASE_NOTE_ADD`, key reset on 201). Mounted once and kept through refetches, so typed text survives (L2).
 */
export function NoteComposer({ detail }: { detail: OutboundCaseDetail }) {
  const queryClient = useQueryClient()
  const id = useId()
  const { internalNote, publicComment } = detail.allowedActions
  const allowed = useMemo(
    () => [...(internalNote ? (['INTERNAL'] as const) : []), ...(publicComment ? (['PUBLIC'] as const) : [])],
    [internalNote, publicComment],
  )
  const schema = useMemo(() => noteSchema(allowed), [allowed])
  const form = useForm<NoteInput>({
    resolver: zodResolver(schema),
    defaultValues: { visibility: internalNote ? 'INTERNAL' : 'PUBLIC', text: '' },
  })
  const visibility = useWatch({ control: form.control, name: 'visibility' })
  const text = useWatch({ control: form.control, name: 'text' })
  const add = useAddNote(detail.reference)
  const guard = useSubmitGuard()
  const idempotency = useIdempotencyKey('CASE_NOTE_ADD', `/outbound/cases/${detail.reference}/notes`)
  const [root, setRoot] = useState<{ message: string; error: ApiError | null } | null>(null)
  const [announce, setAnnounce] = useState('')

  // An option that disappeared after a refetch (e.g. the case closed): fall back to the one still allowed.
  useEffect(() => {
    if (allowed.length > 0 && !allowed.includes(visibility)) form.setValue('visibility', allowed[0]!)
  }, [allowed, visibility, form])

  if (allowed.length === 0) return null

  const submit = form.handleSubmit((body) =>
    guard.run(async () => {
      setRoot(null)
      const idempotencyKey = idempotency.keyFor(body)
      try {
        await add.mutateAsync({ body, idempotencyKey })
        idempotency.succeeded()
        setAnnounce(body.visibility === 'INTERNAL' ? 'Note added' : 'Comment posted to the customer')
        form.reset({ visibility: body.visibility, text: '' })
      } catch (error) {
        idempotency.failed(error)
        const reason = problemReason(error)
        if (isIdempotencyMismatch(error)) {
          setRoot({ message: IDEMPOTENCY_MISMATCH_MESSAGE, error: error as ApiError })
        } else if (error instanceof ApiError && error.status === 403) {
          setRoot({ message: "You can't add this type of note on this case.", error })
          void queryClient.invalidateQueries({ queryKey: outboundKeys.case(detail.reference) })
        } else if (reason === 'case_closed') {
          setRoot({ message: 'The case is closed: customer comments are off. Internal notes still work.', error: error as ApiError })
          void queryClient.invalidateQueries({ queryKey: outboundKeys.case(detail.reference) })
        } else if (error instanceof ApiError && error.status === 409) {
          setRoot({ message: 'Still saving… try again in a moment.', error })
        } else if (error instanceof ApiError && error.status === 400) {
          const v = error.fieldErrors.find((f) => f.field === 'text')
          if (v) form.setError('text', { type: 'server', message: v.message })
          else setRoot({ message: error.message, error })
        } else {
          setRoot({ message: error instanceof ApiError ? error.message : GENERIC_ERROR_MESSAGE, error: error instanceof ApiError ? error : null })
        }
      }
    }),
  )

  const internal = visibility === 'INTERNAL'
  return (
    <Form {...form}>
      <form
        noValidate
        onSubmit={submit}
        aria-label="Add a note"
        className={cn('flex flex-col gap-3 rounded-lg border p-4', internal ? 'border-internal-line bg-internal' : 'bg-surface')}
      >
        <FormRootError message={root?.message} error={root?.error ?? null} />
        {allowed.length > 1 ? (
          <FormField
            control={form.control}
            name="visibility"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Type</FormLabel>
                <RadioGroup value={field.value} onValueChange={field.onChange} className="flex flex-col gap-2 sm:flex-row sm:gap-6">
                  <div className="flex items-center gap-2">
                    <RadioGroupItem id={`${id}-int`} value="INTERNAL" />
                    <Label htmlFor={`${id}-int`} className="flex items-center gap-1">
                      <Lock aria-hidden="true" className="size-3.5" /> Internal note: staff only
                    </Label>
                  </div>
                  <div className="flex items-center gap-2">
                    <RadioGroupItem id={`${id}-pub`} value="PUBLIC" />
                    <Label htmlFor={`${id}-pub`} className="flex items-center gap-1">
                      <Eye aria-hidden="true" className="size-3.5" /> Comment to the customer: shown in their portal
                    </Label>
                  </div>
                </RadioGroup>
              </FormItem>
            )}
          />
        ) : (
          <p className="flex items-center gap-1 text-sm font-medium">
            {internal ? <Lock aria-hidden="true" className="size-3.5" /> : <Eye aria-hidden="true" className="size-3.5" />}
            {internal ? 'Internal note: staff only' : 'Comment to the customer: shown in their portal'}
          </p>
        )}
        <FormField
          control={form.control}
          name="text"
          render={({ field }) => (
            <FormItem>
              <FormLabel>{internal ? 'Note' : 'Comment'}</FormLabel>
              <FormControl>
                <Textarea rows={3} maxLength={NOTE_MAX} readOnly={guard.busy} {...field} />
              </FormControl>
              <p className="text-sm text-muted-foreground">
                {text.length} / {NOTE_MAX} characters
              </p>
              <FormMessage />
            </FormItem>
          )}
        />
        <div>
          <Button type="submit" disabled={guard.busy}>
            {guard.busy ? 'Saving…' : internal ? 'Add internal note' : 'Post to customer'}
          </Button>
        </div>
        <output aria-live="polite" className="sr-only">
          {announce}
        </output>
      </form>
    </Form>
  )
}
