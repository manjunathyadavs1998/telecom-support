import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { FormRootError } from '@/components/states/FormRootError'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { ApiError } from '@/services/api/errors'
import { useSubmitGuard } from '@/services/api/idempotency'
import { KBA_FIELDS, kbaSchema, type KbaInput, type KbaValues, useRootFocus, type RootProblem } from './verifySchemas'

type KbaFormProps = {
  throttled: boolean
  /**
   * Sends I2 KBA with the answers; resolves to the problem to show at the root (or `null` when the panel handled it, e.g.
   * moved to another step), or throws nothing. Field errors (400) come back as an `ApiError` in `fields`.
   */
  onSubmit: (values: KbaValues) => Promise<{ root: RootProblem | null; fields?: ApiError }>
}

/**
 * Security questions (03-inbound/frontend.md §3.2, §6): billing postal code + registered email or second mobile, both required.
 * Which answer was wrong is never shown. Answers are `autocomplete="off"` and cleared after every attempt (NFR-SEC).
 */
export function KbaForm({ throttled, onSubmit }: KbaFormProps) {
  const guard = useSubmitGuard()
  const form = useForm<KbaInput, unknown, KbaValues>({
    resolver: zodResolver(kbaSchema),
    defaultValues: { postalCode: '', registeredContact: '' },
  })
  const { control, handleSubmit, setError, setFocus, reset } = form
  const [root, setRoot] = useState<RootProblem | null>(null)
  const rootFocus = useRootFocus()

  const submit = handleSubmit((values) =>
    guard.run(async () => {
      setRoot(null)
      const outcome = await onSubmit(values)
      if (outcome.fields) {
        const known = new Set<string>(KBA_FIELDS)
        const first = outcome.fields.fieldErrors.find((violation) => known.has(violation.field))
        for (const violation of outcome.fields.fieldErrors) {
          if (known.has(violation.field)) {
            setError(violation.field as (typeof KBA_FIELDS)[number], { type: 'server', message: violation.message })
          }
        }
        if (first) {
          setFocus(first.field as (typeof KBA_FIELDS)[number])
          return
        }
        setRoot({ message: outcome.fields.message, error: outcome.fields })
        rootFocus.focus()
        return
      }
      reset({ postalCode: '', registeredContact: '' })
      if (outcome.root) {
        setRoot(outcome.root)
        rootFocus.focus()
      }
    }),
  )

  return (
    <Form {...form}>
      <form noValidate onSubmit={submit} aria-busy={guard.busy} className="flex flex-col gap-4">
        <FormRootError ref={rootFocus.ref} message={root?.message} error={root?.error} />
        <FormField
          control={control}
          name="postalCode"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Billing postal code</FormLabel>
              <FormControl>
                <Input autoComplete="off" readOnly={guard.busy} className="sm:max-w-48" {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={control}
          name="registeredContact"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Registered email or second mobile</FormLabel>
              <FormControl>
                <Input autoComplete="off" readOnly={guard.busy} {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <div>
          <Button type="submit" disabled={guard.busy || throttled}>
            {guard.busy ? 'Verifying…' : 'Verify'}
          </Button>
        </div>
      </form>
    </Form>
  )
}
