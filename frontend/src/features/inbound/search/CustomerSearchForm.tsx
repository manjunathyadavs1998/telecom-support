import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { Search } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { FormRootError } from '@/components/states/FormRootError'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { ApiError, applyProblemToForm, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
import { rememberMatch, type CachedMatch } from '../verificationState'
import { useCustomerSearchMutation } from './queries'
import { SEARCH_FIELDS, searchSchema, type SearchInput, type SearchValues } from './searchSchema'

export const NO_MATCH_MESSAGE = 'No active line found for this number'
export const OTHER_CUSTOMER_MESSAGE = 'This number belongs to a different customer'
/** Used only if a 429 arrives without a usable `Retry-After`. */
const DEFAULT_RETRY_AFTER_SEC = 60

type CustomerSearchFormProps = {
  /** A hit (already remembered: match → cache, token → store), or `null` when the number has no live line. */
  onResult: (match: CachedMatch | null) => void
  /** VerifyPanel (§3.2): the hit must be this customer, else an error and nothing is stored. */
  expectCustomerId?: number
}

/**
 * The mobile number search (03-inbound/frontend.md §3.1, §4, §6): Zod validation → I1. 400 → field; 429 → root countdown from
 * `Retry-After` (Search disabled); other errors → root with the correlation id. The number leaves the mutation at once
 * (`gcTime: 0`, `reset`); masking is server-side (NFR-PRIV). 429 a11y as the login form: announced once, countdown not live.
 */
export function CustomerSearchForm({ onResult, expectCustomerId }: CustomerSearchFormProps) {
  const queryClient = useQueryClient()
  const form = useForm<SearchInput, unknown, SearchValues>({
    resolver: zodResolver(searchSchema),
    defaultValues: { msisdn: '' },
  })
  const {
    control,
    handleSubmit,
    setError,
    setFocus,
    formState: { errors, isSubmitting },
  } = form
  const search = useCustomerSearchMutation()
  const [serverError, setServerError] = useState<ApiError | null>(null)
  const [retryIn, setRetryIn] = useState(0)
  const [throttledFor, setThrottledFor] = useState(0)
  const rootRef = useRef<HTMLDivElement>(null)
  const [rootFocusRequest, setRootFocusRequest] = useState(0)

  useEffect(() => {
    if (retryIn <= 0) return
    const timer = setTimeout(() => setRetryIn((seconds) => seconds - 1), 1000)
    return () => clearTimeout(timer)
  }, [retryIn])

  useEffect(() => {
    if (rootFocusRequest > 0) rootRef.current?.focus()
  }, [rootFocusRequest])

  const focusRoot = () => setRootFocusRequest((n) => n + 1)

  function showServerError(error: unknown) {
    if (error instanceof ApiError && error.status === 400) {
      applyProblemToForm(error, (name, option) => setError(name, option), SEARCH_FIELDS)
      if (error.fieldErrors.some((violation) => violation.field === 'msisdn')) setFocus('msisdn')
      else focusRoot()
      return
    }
    if (error instanceof ApiError && error.status === 429) {
      const seconds = error.retryAfterSec ?? DEFAULT_RETRY_AFTER_SEC
      setThrottledFor(seconds)
      setRetryIn(seconds)
      setError('root.server', { type: 'throttled' })
    } else {
      setServerError(error instanceof ApiError ? error : null)
      setError('root.server', {
        type: 'server',
        message: error instanceof ApiError ? error.message : GENERIC_ERROR_MESSAGE,
      })
    }
    focusRoot()
  }

  const onSubmit = handleSubmit(async ({ msisdn }) => {
    setServerError(null)
    try {
      const [match] = await search.mutateAsync(msisdn)
      if (!match) {
        onResult(null)
        return
      }
      if (expectCustomerId !== undefined && match.customerId !== expectCustomerId) {
        setError('root.server', { type: 'server', message: OTHER_CUSTOMER_MESSAGE })
        focusRoot()
        return
      }
      rememberMatch(queryClient, match)
      const { lookupToken: _token, ...cached } = match
      onResult(cached)
    } catch (error) {
      showServerError(error)
    } finally {
      search.reset()
    }
  })

  const throttled = retryIn > 0
  const rootMessage = errors.root?.server?.message || undefined

  return (
    <Form {...form}>
      <form noValidate onSubmit={onSubmit} className="flex flex-col gap-4" aria-busy={isSubmitting}>
        {throttled ? (
          <div className="flex flex-col gap-1">
            <FormRootError
              ref={rootRef}
              message={
                <>
                  Too many searches.<span className="sr-only"> Try again in {throttledFor} s.</span>
                </>
              }
            />
            <p aria-live="off" className="text-sm text-muted-foreground">
              Try again in {retryIn} s.
            </p>
          </div>
        ) : (
          <FormRootError ref={rootRef} message={rootMessage} error={rootMessage ? serverError : null} />
        )}
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
          <FormField
            control={control}
            name="msisdn"
            render={({ field }) => (
              <FormItem className="flex-1">
                <FormLabel>Customer&apos;s mobile number</FormLabel>
                <FormControl>
                  <Input type="tel" inputMode="tel" autoComplete="off" readOnly={isSubmitting} {...field} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />
          <Button type="submit" disabled={isSubmitting || throttled}>
            <Search aria-hidden="true" className="size-4" />
            {isSubmitting ? 'Searching…' : 'Search'}
          </Button>
        </div>
      </form>
    </Form>
  )
}
