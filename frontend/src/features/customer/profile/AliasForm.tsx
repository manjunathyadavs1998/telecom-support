import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { FormRootError } from '@/components/states/FormRootError'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { ApiError, applyProblemToForm, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
import { useSubmitGuard } from '@/services/api/idempotency'
import type { Profile } from '@/types/api'
import { PROFILE_QUERY_KEY, useUpdateProfileMutation } from './queries'

const ALIAS_MAX = 60
const ALIAS_PATTERN = /^[\p{L}\p{N} .'-]+$/u

// frontend.md §6 Alias: trim; '' → null (clears the alias); else 1–60 characters of letters, digits, space . ' -
const aliasSchema = z.object({
  aliasName: z
    .string()
    .trim()
    .max(ALIAS_MAX, `Use at most ${ALIAS_MAX} characters.`)
    .refine((value) => value === '' || ALIAS_PATTERN.test(value), "Use only letters, numbers, spaces and . ' -")
    .transform((value) => (value === '' ? null : value)),
})

type AliasInput = z.input<typeof aliasSchema>
type AliasOutput = z.output<typeof aliasSchema>

const ALIAS_FIELDS = ['aliasName'] as const

/** Shown on a 409 (stale `version`); the profile is refetched so the next save uses the latest version. */
const STALE_MESSAGE = 'Your profile was changed elsewhere. We loaded the latest version.'

/**
 * Alias edit (frontend.md §3 Profile, §6; CUS-02.1/02.4/02.5, XC-07). The form follows the cached profile (RHF `values`):
 * when its `version` changes and the field isn't dirty, the field is reset to the new alias; a dirty field is kept. At submit
 * `version` is read from the **current** `['customer','profile']` cache, not from the profile the form started with. Nothing
 * changes on screen before the 200.
 */
export function AliasForm({ profile }: { profile: Profile }) {
  const client = useQueryClient()
  const update = useUpdateProfileMutation()
  const guard = useSubmitGuard()
  const form = useForm<AliasInput, unknown, AliasOutput>({
    resolver: zodResolver(aliasSchema),
    defaultValues: { aliasName: profile.aliasName ?? '' },
  })
  const {
    control,
    handleSubmit,
    reset,
    setError,
    setFocus,
    clearErrors,
    getFieldState,
    formState: { errors, isDirty },
  } = form

  // A newer cached profile (another save, a confirmed contact change, a 409 refetch) re-baselines a clean form (F6). A dirty
  // field keeps the user's input; the submit still reads the latest `version` from the cache.
  const savedAlias = profile.aliasName ?? ''
  useEffect(() => {
    if (!getFieldState('aliasName').isDirty) reset({ aliasName: savedAlias })
  }, [profile.version, savedAlias, getFieldState, reset])
  const [serverError, setServerError] = useState<ApiError | null>(null)
  const rootRef = useRef<HTMLDivElement>(null)
  const [rootFocusRequest, setRootFocusRequest] = useState(0)

  useEffect(() => {
    if (rootFocusRequest > 0) rootRef.current?.focus()
  }, [rootFocusRequest])

  function showRootError(message: string, error: ApiError | null) {
    setServerError(error)
    setError('root.server', { type: 'server', message })
    setRootFocusRequest((n) => n + 1)
  }

  function showServerError(error: unknown) {
    if (!(error instanceof ApiError)) {
      showRootError(GENERIC_ERROR_MESSAGE, null)
      return
    }
    switch (error.status) {
      case 400:
        applyProblemToForm(error, (name, option) => setError(name, option), ALIAS_FIELDS)
        if (error.fieldErrors.some((violation) => violation.field === 'aliasName')) setFocus('aliasName')
        else setRootFocusRequest((n) => n + 1)
        return
      case 403:
        return // the global mutation handler shows the forbidden toast (01 §7)
      case 409:
        showRootError(STALE_MESSAGE, null)
        void client.refetchQueries({ queryKey: PROFILE_QUERY_KEY, exact: true })
        return
      case 422:
        showRootError(error.message, error)
        return
      default:
        showRootError(GENERIC_ERROR_MESSAGE, error)
    }
  }

  const onSubmit = handleSubmit((input) =>
    guard.run(async () => {
      setServerError(null)
      const version = client.getQueryData<Profile>(PROFILE_QUERY_KEY)?.version ?? profile.version
      try {
        const saved = await update.mutateAsync({ aliasName: input.aliasName, version })
        reset({ aliasName: saved.aliasName ?? '' })
        toast.success('Profile saved')
      } catch (error) {
        showServerError(error)
      }
    }),
  )

  function cancel() {
    clearErrors()
    setServerError(null)
    reset({ aliasName: savedAlias })
  }

  const rootMessage = errors.root?.server?.message || undefined
  const busy = guard.busy

  return (
    <Form {...form}>
      <form noValidate onSubmit={onSubmit} aria-label="Alias" aria-busy={busy} className="flex flex-col gap-3">
        <FormRootError ref={rootRef} message={rootMessage} error={rootMessage ? serverError : null} />
        <FormField
          control={control}
          name="aliasName"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Alias (optional)</FormLabel>
              <FormControl>
                <Input autoComplete="nickname" readOnly={busy} className="sm:max-w-sm" {...field} />
              </FormControl>
              <p className="text-sm text-muted-foreground">How we greet you. Leave it empty to remove it.</p>
              <FormMessage />
            </FormItem>
          )}
        />
        <div className="flex gap-2">
          <Button type="submit" disabled={busy}>
            {busy ? 'Saving…' : 'Save'}
          </Button>
          <Button type="button" variant="outline" onClick={cancel} disabled={busy || (!isDirty && !rootMessage)}>
            Cancel
          </Button>
        </div>
      </form>
    </Form>
  )
}
