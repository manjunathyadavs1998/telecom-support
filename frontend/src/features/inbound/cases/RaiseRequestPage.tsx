import { zodResolver } from '@hookform/resolvers/zod'
import { Copy } from 'lucide-react'
import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Link } from 'react-router'
import { statusLabel } from '@/components/data/statusLabel'
import { FormRootError } from '@/components/states/FormRootError'
import { LoadingState } from '@/components/states/LoadingState'
import { SectionErrorState } from '@/components/states/SectionErrorState'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectGroup, SelectItem, SelectLabel, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { formatDateTime } from '@/lib/format'
import { ApiError, applyProblemToForm, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
import {
  IDEMPOTENCY_MISMATCH_MESSAGE,
  isIdempotencyMismatch,
  useIdempotencyKey,
  useSubmitGuard,
  type IdempotencyKey,
} from '@/services/api/idempotency'
import type { AgentCaseCreated, AgentCaseCreateRequest, AgentCategory, AgentCustomerProfile, Subscription } from '@/types/api'
import { isVerificationRequired } from '../verificationState'
import { useAgentProfileQuery, useAgentSubscriptionsQuery, useVerifiedCustomer } from '../workspace/queries'
import { caseCreatePath, useAgentCategoriesQuery, useCreateCaseOnBehalf } from './queries'
import {
  derivedSeverity,
  PREFERRED_CONTACTS,
  RAISE_REQUEST_FIELDS,
  raiseRequestDefaults,
  raiseRequestSchema,
  STATEMENT_MAX,
  SUBJECT_MAX,
  NOTES_MAX,
  type RaiseRequestField,
  type RaiseRequestInput,
} from './raiseRequestSchema'
import { SeverityField } from './SeverityField'

const DIRTY_KEY = 'raise-request'
const STILL_SUBMITTING = 'Still being submitted. Please wait a moment and try again.'
const CATEGORY_FORBIDDEN = "You can't use this category or priority"
const LINE_GONE = 'That line is no longer on this account'
const VERIFY_FIRST = 'Verify the customer again, then submit. What you typed is kept.'
const VERIFIED_AGAIN = 'Verified again. Submit when ready.'

type RootProblem = { message: string; error: ApiError | null; kind?: 'verify' }

type FormProps = {
  customerId: number
  profile: AgentCustomerProfile
  lines: Subscription[]
  categories: AgentCategory[]
  idempotency: IdempotencyKey
  onCreated: (created: AgentCaseCreated) => void
  onLinesStale: () => void
}

function RaiseRequestForm({ customerId, profile, lines, categories, idempotency, onCreated, onLinesStale }: FormProps) {
  const { verified, reverifiedCount, setDirty } = useVerifiedCustomer()
  const create = useCreateCaseOnBehalf(customerId)
  const guard = useSubmitGuard()
  const notesHintId = useId()
  const primary = lines.find((line) => line.primary) ?? lines[0]
  const schema = useMemo(
    () => raiseRequestSchema({ categories, lineIds: lines.map((line) => line.id), emailVerified: profile.emailVerified }),
    [categories, lines, profile.emailVerified],
  )
  const form = useForm<RaiseRequestInput, unknown, AgentCaseCreateRequest>({
    resolver: zodResolver(schema),
    defaultValues: raiseRequestDefaults(primary?.id),
  })
  const { control, handleSubmit, setError, setFocus, setValue, formState } = form
  const categoryCode = useWatch({ control, name: 'categoryCode' })
  const subcategoryCode = useWatch({ control, name: 'subcategoryCode' })
  const subscriptionId = useWatch({ control, name: 'subscriptionId' })
  const statement = useWatch({ control, name: 'customerStatement' })
  const category = categories.find((c) => c.code === categoryCode)
  const derived = derivedSeverity(categories, categoryCode, subcategoryCode)
  const contactNumber = lines.find((line) => String(line.id) === subscriptionId)?.msisdn ?? '—'

  const [root, setRoot] = useState<RootProblem | null>(null)
  const rootRef = useRef<HTMLDivElement>(null)
  const [rootFocusRequest, setRootFocusRequest] = useState(0)
  useEffect(() => {
    if (rootFocusRequest > 0) rootRef.current?.focus()
  }, [rootFocusRequest])

  // §3.3: the dialog warns before leaving with unsaved input.
  useEffect(() => {
    setDirty(DIRTY_KEY, formState.isDirty)
    return () => setDirty(DIRTY_KEY, false)
  }, [formState.isDirty, setDirty])

  // After a re-verification the pending form says so (no automatic resubmit).
  const [seenReverify, setSeenReverify] = useState(reverifiedCount)
  if (seenReverify !== reverifiedCount) {
    setSeenReverify(reverifiedCount)
    if (root?.kind === 'verify') setRoot({ message: VERIFIED_AGAIN, error: null })
  }

  const internal = categories.filter((c) => c.audience === 'INTERNAL')
  const customerFacing = categories.filter((c) => c.audience !== 'INTERNAL')

  function showRoot(problem: RootProblem) {
    setRoot(problem)
    setRootFocusRequest((n) => n + 1)
  }

  function showServerError(error: unknown) {
    if (!(error instanceof ApiError)) return showRoot({ message: GENERIC_ERROR_MESSAGE, error: null })
    if (isVerificationRequired(error)) return showRoot({ message: VERIFY_FIRST, error: null, kind: 'verify' })
    if (isIdempotencyMismatch(error)) return showRoot({ message: IDEMPOTENCY_MISMATCH_MESSAGE, error })
    switch (error.status) {
      case 400: {
        const mapped: RaiseRequestField[] = []
        applyProblemToForm<RaiseRequestField>(
          error,
          (name, option) => {
            if (name === 'root.server') showRoot({ message: option.message, error })
            else {
              setError(name, option)
              mapped.push(name)
            }
          },
          RAISE_REQUEST_FIELDS,
        )
        const first = RAISE_REQUEST_FIELDS.find((field) => mapped.includes(field))
        if (first) setFocus(first)
        return
      }
      case 403:
        return showRoot({ message: CATEGORY_FORBIDDEN, error })
      case 404:
        onLinesStale()
        return showRoot({ message: LINE_GONE, error })
      case 409:
        return showRoot({ message: STILL_SUBMITTING, error })
      case 422:
        return showRoot({ message: error.message, error })
      default:
        return showRoot({ message: GENERIC_ERROR_MESSAGE, error })
    }
  }

  const onSubmit = handleSubmit((body) =>
    guard.run(async () => {
      setRoot(null)
      const idempotencyKey = idempotency.keyFor(body)
      try {
        const created = await create.mutateAsync({ idempotencyKey, body })
        idempotency.succeeded()
        setDirty(DIRTY_KEY, false)
        onCreated(created)
      } catch (error) {
        // 403 verification-required is kept by `failed` (only 400/404/422 drop the key): the resubmit replays it (F4).
        idempotency.failed(error)
        showServerError(error)
      }
    }),
  )

  const busy = guard.busy

  return (
    <Form {...form}>
      <form noValidate onSubmit={onSubmit} aria-label="Raise request" aria-busy={busy} className="flex max-w-2xl flex-col gap-5">
        {root?.message === VERIFIED_AGAIN ? (
          <Alert asChild>
            <output className="block">{VERIFIED_AGAIN}</output>
          </Alert>
        ) : (
          <FormRootError ref={rootRef} message={root?.message} error={root?.error ?? null} />
        )}
        {verified ? (
        <dl className="grid gap-3 rounded-xl border border-line bg-surface-2 p-4 sm:grid-cols-3">
          <div className="flex flex-col gap-0.5">
            <dt className="text-sm text-muted-foreground">Customer</dt>
            <dd>{profile.fullName}</dd>
          </div>
          <div className="flex flex-col gap-0.5">
            <dt className="text-sm text-muted-foreground">Account</dt>
            <dd>{profile.accountRef}</dd>
          </div>
          <div className="flex flex-col gap-0.5">
            <dt className="text-sm text-muted-foreground">Contact number</dt>
            <dd>{contactNumber}</dd>
          </div>
        </dl>
        ) : (
          <p className="rounded-xl border border-line bg-surface-2 p-4 text-muted-foreground">
            Customer details are hidden until the caller is verified again.
          </p>
        )}

        <FormField
          control={control}
          name="categoryCode"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Category</FormLabel>
              <Select
                value={field.value}
                disabled={busy}
                onValueChange={(value) => {
                  field.onChange(value)
                  setValue('subcategoryCode', '')
                }}
              >
                <FormControl>
                  <SelectTrigger>
                    <SelectValue placeholder="Choose a category" />
                  </SelectTrigger>
                </FormControl>
                <SelectContent>
                  <SelectGroup>
                    <SelectLabel>Customer categories</SelectLabel>
                    {customerFacing.map((c) => (
                      <SelectItem key={c.code} value={c.code}>
                        {c.name}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                  {internal.length > 0 ? (
                    <SelectGroup>
                      <SelectLabel>Internal categories</SelectLabel>
                      {internal.map((c) => (
                        <SelectItem key={c.code} value={c.code}>
                          {c.name} (Internal)
                        </SelectItem>
                      ))}
                    </SelectGroup>
                  ) : null}
                </SelectContent>
              </Select>
              <FormMessage />
            </FormItem>
          )}
        />
        {category && category.subcategories.length > 0 ? (
          <FormField
            control={control}
            name="subcategoryCode"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Subcategory</FormLabel>
                <Select value={field.value} onValueChange={field.onChange} disabled={busy}>
                  <FormControl>
                    <SelectTrigger>
                      <SelectValue placeholder="Choose a subcategory" />
                    </SelectTrigger>
                  </FormControl>
                  <SelectContent>
                    {category.subcategories.map((s) => (
                      <SelectItem key={s.code} value={s.code}>
                        {s.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )}
          />
        ) : null}

        {lines.length === 1 ? (
          <div className="flex flex-col gap-1">
            <span className="text-sm font-medium">Line</span>
            <span>{lines[0]!.msisdn}</span>
          </div>
        ) : (
          <FormField
            control={control}
            name="subscriptionId"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Line</FormLabel>
                <Select value={field.value} onValueChange={field.onChange} disabled={busy}>
                  <FormControl>
                    <SelectTrigger>
                      <SelectValue placeholder="Choose the line" />
                    </SelectTrigger>
                  </FormControl>
                  <SelectContent>
                    {lines.map((line) => (
                      <SelectItem key={line.id} value={String(line.id)}>
                        {line.msisdn}
                        {line.primary ? ' (Primary)' : ''}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <FormMessage />
              </FormItem>
            )}
          />
        )}

        <FormField
          control={control}
          name="subject"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Issue summary</FormLabel>
              <FormControl>
                <Input maxLength={SUBJECT_MAX} autoComplete="off" disabled={busy} {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={control}
          name="customerStatement"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Customer statement</FormLabel>
              <FormControl>
                <Textarea rows={5} maxLength={STATEMENT_MAX} disabled={busy} {...field} />
              </FormControl>
              <p className="text-sm text-muted-foreground">
                {statement.length} / {STATEMENT_MAX} characters
              </p>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={control}
          name="agentNotes"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Agent notes (optional)</FormLabel>
              <FormControl aria-describedby={notesHintId}>
                <Textarea rows={3} maxLength={NOTES_MAX} disabled={busy} {...field} />
              </FormControl>
              <p id={notesHintId} className="text-sm text-muted-foreground">
                Internal note: never shown to the customer
              </p>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={control}
          name="preferredContact"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Preferred contact</FormLabel>
              <RadioGroup value={field.value} onValueChange={field.onChange} disabled={busy} className="flex flex-wrap gap-4">
                {PREFERRED_CONTACTS.map((option) => {
                  const unavailable = option.value === 'EMAIL' && !profile.emailVerified
                  const id = `${notesHintId}-${option.value}`
                  return (
                    <div key={option.value} className="flex items-center gap-2">
                      <RadioGroupItem id={id} value={option.value} disabled={unavailable} />
                      <Label htmlFor={id}>
                        {option.label}
                        {unavailable ? ' (email not verified)' : ''}
                      </Label>
                    </div>
                  )
                })}
              </RadioGroup>
              <FormMessage />
            </FormItem>
          )}
        />

        <SeverityField control={control} derived={derived} disabled={busy} />

        <div>
          <Button type="submit" disabled={busy}>
            {busy ? 'Submitting…' : 'Raise request'}
          </Button>
        </div>
      </form>
    </Form>
  )
}

function Confirmation({
  customerId,
  created,
  onAnother,
}: {
  customerId: number
  created: AgentCaseCreated
  onAnother: () => void
}) {
  const headingId = useId()
  const headingRef = useRef<HTMLHeadingElement>(null)
  const [copied, setCopied] = useState<'' | 'Copied' | "Couldn't copy. Select the reference instead.">('')
  useEffect(() => headingRef.current?.focus(), [])

  const copy = () => {
    const write = navigator.clipboard?.writeText(created.reference) ?? Promise.reject(new Error('no clipboard'))
    write.then(
      () => setCopied('Copied'),
      () => setCopied("Couldn't copy. Select the reference instead."),
    )
  }

  return (
    <section aria-labelledby={headingId} className="flex max-w-2xl flex-col gap-4 enter-soft rounded-xl border border-line bg-surface shadow-soft-sm p-4 md:p-6">
      {/* §3.8: announced as a status message; the heading takes focus. */}
      <output className="sr-only">Request {created.reference} created.</output>
      <h2 id={headingId} ref={headingRef} tabIndex={-1} className="text-lg font-semibold focus-visible:outline-none">
        Request {created.reference} created. Read this reference to the customer.
      </h2>
      <div className="flex items-center gap-2">
        <Button type="button" variant="outline" onClick={copy}>
          <Copy aria-hidden="true" className="size-4" />
          Copy reference
        </Button>
        <span aria-live="polite" className="text-sm text-muted-foreground">
          {copied}
        </span>
      </div>
      <dl className="grid gap-3 sm:grid-cols-2">
        <div>
          <dt className="text-sm text-muted-foreground">Priority</dt>
          <dd>{statusLabel({ kind: 'severity', value: created.severity })}</dd>
        </div>
        <div>
          <dt className="text-sm text-muted-foreground">Status</dt>
          <dd>{statusLabel({ kind: 'caseInternal', value: created.status })}</dd>
        </div>
        <div>
          <dt className="text-sm text-muted-foreground">Created (IST)</dt>
          <dd>{formatDateTime(created.createdAt)}</dd>
        </div>
        <div>
          <dt className="text-sm text-muted-foreground">Contact number</dt>
          <dd>{created.contactMsisdn}</dd>
        </div>
        <div>
          <dt className="text-sm text-muted-foreground">Account</dt>
          <dd>{created.accountRef}</dd>
        </div>
        <div>
          <dt className="text-sm text-muted-foreground">Channel</dt>
          <dd>Inbound agent</dd>
        </div>
      </dl>
      <div className="flex flex-wrap gap-2">
        <Button asChild>
          <Link to={`/inbound/customers/${customerId}/cases/${created.reference}`}>View request</Link>
        </Button>
        <Button type="button" variant="outline" onClick={onAnother}>
          Raise another
        </Button>
      </div>
    </section>
  )
}

/** Raise request on behalf `…/cases/new` (03-inbound/frontend.md §3.8, §6; INB-03.1–03.5, §14 #4, NFR-REL). */
export function RaiseRequestPage() {
  const { customerId, verified } = useVerifiedCustomer()
  const profile = useAgentProfileQuery(customerId, verified)
  const subscriptions = useAgentSubscriptionsQuery(customerId, verified)
  const categories = useAgentCategoriesQuery(verified)
  const idempotency = useIdempotencyKey('CASE_CREATE_ONBEHALF', caseCreatePath(customerId))
  const [created, setCreated] = useState<AgentCaseCreated | null>(null)
  const [formKey, setFormKey] = useState(0)

  // Review H1: when verification is lost the protected queries are removed (§3.3); the form keeps its last data in component
  // memory so it stays mounted with the agent's input and its idempotency key, and resubmits after re-verification.
  const live =
    profile.data && subscriptions.data && categories.data
      ? { profile: profile.data, subscriptions: subscriptions.data, categories: categories.data }
      : null
  const [snapshot, setSnapshot] = useState(live)
  if (
    live &&
    (snapshot?.profile !== live.profile ||
      snapshot.subscriptions !== live.subscriptions ||
      snapshot.categories !== live.categories)
  ) {
    setSnapshot(live)
  }
  const data = live ?? snapshot

  if (created) {
    return (
      <Confirmation
        customerId={customerId}
        created={created}
        onAnother={() => {
          idempotency.reset()
          setCreated(null)
          setFormKey((n) => n + 1)
        }}
      />
    )
  }


  const failed = profile.error ?? subscriptions.error ?? categories.error
  if (failed && !data) {
    return (
      <SectionErrorState
        title="We couldn't load the request form"
        error={failed}
        onRetry={() => {
          void profile.refetch()
          void subscriptions.refetch()
          void categories.refetch()
        }}
      />
    )
  }
  if (!data) return <LoadingState label="Loading the request form" />

  const lines = data.subscriptions.filter((line) => line.status !== 'TERMINATED')
  return (
    <RaiseRequestForm
      key={formKey}
      customerId={customerId}
      profile={data.profile}
      lines={lines}
      categories={data.categories}
      idempotency={idempotency}
      onCreated={setCreated}
      onLinesStale={() => void subscriptions.refetch()}
    />
  )
}

export { RaiseRequestPage as Component }
