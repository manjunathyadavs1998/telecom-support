import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery } from '@tanstack/react-query'
import { CircleCheck, Inbox } from 'lucide-react'
import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Link } from 'react-router'
import { statusLabel } from '@/components/data/statusLabel'
import { PageContainer } from '@/components/layout/PageContainer'
import { EmptyState } from '@/components/states/EmptyState'
import { ErrorState } from '@/components/states/ErrorState'
import { FormRootError } from '@/components/states/FormRootError'
import { LoadingState } from '@/components/states/LoadingState'
import { SectionErrorState } from '@/components/states/SectionErrorState'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { subscriptionsQuery } from '@/features/customer/plan/queries'
import { useProfileQuery } from '@/features/customer/profile/queries'
import { formatDateTime } from '@/lib/format'
import { ForbiddenPage } from '@/pages/ForbiddenPage'
import { usePermissions } from '@/permissions/usePermissions'
import { ApiError, applyProblemToForm, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
import {
  IDEMPOTENCY_MISMATCH_MESSAGE,
  isIdempotencyMismatch,
  useIdempotencyKey,
  useSubmitGuard,
  type IdempotencyKey,
} from '@/services/api/idempotency'
import type { CaseCreated, CaseCreateRequest, Category, Profile, Subscription } from '@/types/api'
import {
  CREATE_REQUEST_DEFAULTS,
  CREATE_REQUEST_FIELDS,
  createRequestSchema,
  DESCRIPTION_ANNOUNCE_FROM,
  DESCRIPTION_MAX,
  NOT_SPECIFIC,
  PREFERRED_CONTACTS,
  SUBJECT_MAX,
  VERIFY_EMAIL_HINT,
  type CreateRequestField,
  type CreateRequestInput,
} from './createRequestSchema'
import { CASE_CREATE_PATH, useCaseCategoriesQuery, useCreateCase } from './queries'

export const STILL_SUBMITTING_MESSAGE = 'Your request is still being submitted. Please wait a moment and try again.'
export const SEVERITY_NOTE = 'Priority is set automatically from the category'
export const CATEGORIES_UNAVAILABLE = 'Service requests are unavailable right now'

const REQUESTS_PATH = '/customer/requests'

type RootProblem = { message: string; error: ApiError | null }

export const NUMBERS_LOADING_LABEL = 'Loading your numbers'
export const NUMBERS_ERROR_TITLE = "We couldn't load your numbers"

/**
 * The E5 subscriptions feeding the "Which number is it about?" picker (OQ 7). `off` = no `plan.read`, so no picker and
 * no query. While pending, Submit stays disabled so "Not specific" is never sent just because the list wasn't there yet;
 * on an error the section shows its own `ErrorState` (403 rule) so the customer knows the number can't be chosen.
 */
type NumbersSection =
  | { state: 'off' }
  | { state: 'pending' }
  | { state: 'error'; error: Error; retry: () => void }
  | { state: 'ready'; subscriptions: Subscription[] }

type CreateRequestFormProps = {
  profile: Profile
  categories: Category[]
  /** Shown as the "Which number is it about?" picker only when there is more than one (OQ 7). */
  numbers: NumbersSection
  idempotency: IdempotencyKey
  onCreated: (created: CaseCreated) => void
}

/** The description's character count; announced politely only near the limit (F18). */
function DescriptionCounter({ length, counterId }: { length: number; counterId: string }) {
  const left = DESCRIPTION_MAX - length
  return (
    <>
      <p id={counterId} className="text-sm text-muted-foreground">
        {length} / {DESCRIPTION_MAX} characters
      </p>
      <p aria-live="polite" className="sr-only">
        {length >= DESCRIPTION_ANNOUNCE_FROM ? `${Math.max(left, 0)} characters left` : ''}
      </p>
    </>
  )
}

function CreateRequestForm({ profile, categories, numbers, idempotency, onCreated }: CreateRequestFormProps) {
  const create = useCreateCase()
  const guard = useSubmitGuard()
  const counterId = useId()
  const contactLabelId = useId()
  const emailHintId = useId()
  const schema = useMemo(
    () => createRequestSchema({ categories, emailVerified: profile.emailVerified }),
    [categories, profile.emailVerified],
  )
  const form = useForm<CreateRequestInput, unknown, CaseCreateRequest>({
    resolver: zodResolver(schema),
    defaultValues: CREATE_REQUEST_DEFAULTS,
  })
  const { control, handleSubmit, setError, setFocus, setValue } = form
  const categoryCode = useWatch({ control, name: 'categoryCode' })
  const description = useWatch({ control, name: 'description' })
  const subcategories = categories.find((c) => c.code === categoryCode)?.subcategories ?? []

  const [root, setRoot] = useState<RootProblem | null>(null)
  const rootRef = useRef<HTMLDivElement>(null)
  const [rootFocusRequest, setRootFocusRequest] = useState(0)
  useEffect(() => {
    if (rootFocusRequest > 0) rootRef.current?.focus()
  }, [rootFocusRequest])

  function showRoot(message: string, error: ApiError | null) {
    setRoot({ message, error })
    setRootFocusRequest((n) => n + 1)
  }

  function showServerError(error: unknown) {
    if (!(error instanceof ApiError)) {
      showRoot(GENERIC_ERROR_MESSAGE, null)
      return
    }
    if (error.status === 403) return // the global mutation handler shows the forbidden toast (01 §7)
    if (isIdempotencyMismatch(error)) {
      showRoot(IDEMPOTENCY_MISMATCH_MESSAGE, error)
      return
    }
    switch (error.status) {
      case 400: {
        const mapped: CreateRequestField[] = []
        applyProblemToForm<CreateRequestField>(
          error,
          (name, option) => {
            if (name === 'root.server') showRoot(option.message, error)
            else {
              setError(name, option)
              mapped.push(name)
            }
          },
          CREATE_REQUEST_FIELDS,
        )
        const first = CREATE_REQUEST_FIELDS.find((field) => mapped.includes(field))
        if (first) setFocus(first)
        return
      }
      case 409:
        showRoot(STILL_SUBMITTING_MESSAGE, error)
        return
      case 422:
        showRoot(error.message, error)
        return
      default:
        showRoot(GENERIC_ERROR_MESSAGE, error)
    }
  }

  const onSubmit = handleSubmit((body) =>
    guard.run(async () => {
      setRoot(null)
      const idempotencyKey = idempotency.keyFor(body)
      try {
        const created = await create.mutateAsync({ idempotencyKey, body })
        idempotency.succeeded()
        onCreated(created)
      } catch (error) {
        idempotency.failed(error)
        showServerError(error)
      }
    }),
  )

  const busy = guard.busy
  const subscriptions = numbers.state === 'ready' ? numbers.subscriptions : []

  return (
    <Form {...form}>
      <form noValidate onSubmit={onSubmit} aria-label="New request" aria-busy={busy} className="flex max-w-2xl flex-col gap-5">
        <FormRootError ref={rootRef} message={root?.message} error={root?.error ?? null} />
        <dl className="grid gap-3 rounded-lg border bg-slate-50 p-4 sm:grid-cols-2">
          <div className="flex flex-col gap-0.5">
            <dt className="text-sm text-muted-foreground">Contact number</dt>
            <dd className="text-foreground">{profile.primaryMsisdn}</dd>
          </div>
          <div className="flex flex-col gap-0.5">
            <dt className="text-sm text-muted-foreground">Account reference</dt>
            <dd className="text-foreground">{profile.accountRef}</dd>
          </div>
        </dl>
        <FormField
          control={control}
          name="categoryCode"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Category</FormLabel>
              <Select
                value={field.value}
                onValueChange={(value) => {
                  field.onChange(value)
                  setValue('subcategoryCode', '')
                }}
                disabled={busy}
              >
                <FormControl>
                  <SelectTrigger ref={field.ref} onBlur={field.onBlur}>
                    <SelectValue placeholder="Choose a category" />
                  </SelectTrigger>
                </FormControl>
                <SelectContent>
                  {categories.map((category) => (
                    <SelectItem key={category.code} value={category.code}>
                      {category.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <FormMessage />
            </FormItem>
          )}
        />
        {subcategories.length > 0 ? (
          <FormField
            control={control}
            name="subcategoryCode"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Subcategory</FormLabel>
                <Select value={field.value} onValueChange={field.onChange} disabled={busy}>
                  <FormControl>
                    <SelectTrigger ref={field.ref} onBlur={field.onBlur}>
                      <SelectValue placeholder="Choose a subcategory" />
                    </SelectTrigger>
                  </FormControl>
                  <SelectContent>
                    {subcategories.map((subcategory) => (
                      <SelectItem key={subcategory.code} value={subcategory.code}>
                        {subcategory.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )}
          />
        ) : null}
        <p className="text-sm text-muted-foreground">{SEVERITY_NOTE}</p>
        {numbers.state === 'pending' ? (
          <section aria-label="Which number is it about?">
            <LoadingState label={NUMBERS_LOADING_LABEL} />
          </section>
        ) : null}
        {numbers.state === 'error' ? (
          <section aria-label="Which number is it about?" className="rounded-lg border">
            <SectionErrorState title={NUMBERS_ERROR_TITLE} error={numbers.error} onRetry={numbers.retry} />
          </section>
        ) : null}
        {subscriptions.length > 1 ? (
          <FormField
            control={control}
            name="subscriptionId"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Which number is it about?</FormLabel>
                <Select value={field.value} onValueChange={field.onChange} disabled={busy}>
                  <FormControl>
                    <SelectTrigger ref={field.ref} onBlur={field.onBlur}>
                      <SelectValue />
                    </SelectTrigger>
                  </FormControl>
                  <SelectContent>
                    <SelectItem value={NOT_SPECIFIC}>Not specific</SelectItem>
                    {subscriptions.map((subscription) => (
                      <SelectItem key={subscription.id} value={String(subscription.id)}>
                        {subscription.msisdn}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )}
          />
        ) : null}
        <FormField
          control={control}
          name="subject"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Subject</FormLabel>
              <FormControl>
                <Input maxLength={SUBJECT_MAX} autoComplete="off" disabled={busy} {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={control}
          name="description"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Description</FormLabel>
              <FormControl aria-describedby={counterId}>
                <Textarea rows={6} maxLength={DESCRIPTION_MAX} disabled={busy} {...field} />
              </FormControl>
              <DescriptionCounter length={description.length} counterId={counterId} />
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={control}
          name="preferredContact"
          render={({ field }) => (
            <FormItem>
              <p id={contactLabelId} className="text-sm font-medium">
                Preferred contact
              </p>
              <FormControl>
                <RadioGroup
                  aria-labelledby={contactLabelId}
                  value={field.value}
                  onValueChange={field.onChange}
                  disabled={busy}
                  ref={field.ref}
                  className="flex flex-col gap-2 sm:flex-row sm:gap-6"
                >
                  {PREFERRED_CONTACTS.map((option) => {
                    const itemId = `${contactLabelId}-${option.value}`
                    const locked = option.value === 'EMAIL' && !profile.emailVerified
                    return (
                      <div key={option.value} className="flex items-center gap-2">
                        <RadioGroupItem
                          id={itemId}
                          value={option.value}
                          disabled={locked}
                          aria-describedby={locked ? emailHintId : undefined}
                        />
                        <Label htmlFor={itemId}>{option.label}</Label>
                      </div>
                    )
                  })}
                </RadioGroup>
              </FormControl>
              {!profile.emailVerified ? (
                <p id={emailHintId} className="text-sm text-muted-foreground">
                  {VERIFY_EMAIL_HINT}
                </p>
              ) : null}
              <FormMessage />
            </FormItem>
          )}
        />
        <Button type="submit" disabled={busy || numbers.state === 'pending'} className="self-start">
          {busy ? 'Submitting…' : 'Submit request'}
        </Button>
      </form>
    </Form>
  )
}

/** The in-page confirmation (CUS-06.5): its heading is a `status` live region and receives focus. */
function Confirmation({ created, onCreateAnother }: { created: CaseCreated; onCreateAnother: () => void }) {
  const headingId = useId()
  const headingRef = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    headingRef.current?.focus()
  }, [])
  return (
    <section aria-labelledby={headingId} className="flex max-w-2xl flex-col gap-4 rounded-lg border bg-white p-4 md:p-6">
      <h2
        id={headingId}
        ref={headingRef}
        tabIndex={-1}
        className="flex items-center gap-2 rounded-sm text-lg font-semibold text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        <CircleCheck aria-hidden="true" className="size-5 text-success" />
        {/* The heading's text is a polite live region (role status), so it is announced as well as focused (CUS-06.5). */}
        <output>Request {created.reference} submitted</output>
      </h2>
      <dl className="grid gap-3 sm:grid-cols-3">
        <div className="flex flex-col gap-0.5">
          <dt className="text-sm text-muted-foreground">Status</dt>
          <dd>{statusLabel({ kind: 'case', value: created.status })}</dd>
        </div>
        <div className="flex flex-col gap-0.5">
          <dt className="text-sm text-muted-foreground">Priority</dt>
          <dd>{statusLabel({ kind: 'severity', value: created.severity })}</dd>
        </div>
        <div className="flex flex-col gap-0.5">
          <dt className="text-sm text-muted-foreground">Created</dt>
          <dd>{formatDateTime(created.createdAt)}</dd>
        </div>
      </dl>
      <div className="flex flex-wrap gap-2">
        <Button asChild>
          <Link to={REQUESTS_PATH}>View my requests</Link>
        </Button>
        <Button variant="outline" onClick={onCreateAnother}>
          Create another
        </Button>
      </div>
    </section>
  )
}

/**
 * Create request `/customer/requests/new` (frontend.md §3 Create request, §6; CUS-06.1/06.2/06.4–06.6, NFR-REL): the contact
 * number and account reference are shown read-only (never sent); category → subcategory from E10; the number picker only with
 * `plan.read` and more than one subscription (while E5 loads Submit waits; an E5 failure shows in its section, 403 rule); severity is set by the system; no attachments. Submit is guarded against double
 * clicks and carries an idempotency key per filled-in form. A 403 on the profile or the categories (the route's permissions)
 * → `ForbiddenPage`.
 */
export function CreateRequestPage() {
  const profile = useProfileQuery()
  const categories = useCaseCategoriesQuery()
  const { can } = usePermissions()
  const subscriptions = useQuery({ ...subscriptionsQuery, enabled: can(['plan.read']) })
  const idempotency = useIdempotencyKey('CASE_CREATE', CASE_CREATE_PATH)
  const [created, setCreated] = useState<CaseCreated | null>(null)
  const numbers: NumbersSection = !can(['plan.read'])
    ? { state: 'off' }
    : subscriptions.isPending
      ? { state: 'pending' }
      : subscriptions.isError
        ? { state: 'error', error: subscriptions.error, retry: () => void subscriptions.refetch() }
        : { state: 'ready', subscriptions: subscriptions.data }

  const forbidden = [profile.error, categories.error].some((e) => e instanceof ApiError && e.status === 403)
  if (forbidden) return <ForbiddenPage />

  function createAnother() {
    idempotency.reset()
    setCreated(null)
  }

  return (
    <PageContainer title="New request" description="Tell us what you need help with.">
      {created ? (
        <Confirmation created={created} onCreateAnother={createAnother} />
      ) : profile.isPending || categories.isPending ? (
        <LoadingState label="Loading the request form" />
      ) : profile.isError ? (
        <ErrorState title="We couldn't load your details" error={profile.error} onRetry={() => void profile.refetch()} />
      ) : categories.isError ? (
        <ErrorState
          title="We couldn't load request categories"
          error={categories.error}
          onRetry={() => void categories.refetch()}
        />
      ) : categories.data.length === 0 ? (
        <EmptyState
          icon={<Inbox className="size-8" />}
          title={CATEGORIES_UNAVAILABLE}
          description="Please try again later."
        />
      ) : (
        <CreateRequestForm
          profile={profile.data}
          categories={categories.data}
          numbers={numbers}
          idempotency={idempotency}
          onCreated={setCreated}
        />
      )}
    </PageContainer>
  )
}

/** `route.lazy` entry (pageRegistry). */
export { CreateRequestPage as Component }
