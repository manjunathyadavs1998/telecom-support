import { zodResolver } from '@hookform/resolvers/zod'
import { Eye, EyeOff } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { FormRootError } from '@/components/states/FormRootError'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { ApiError, applyProblemToForm, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
import type { Me } from '@/types/api'
import { LOGIN_FIELDS, loginSchema, type LoginValues } from './loginSchema'
import { useLoginMutation } from './useLoginMutation'

export const INVALID_CREDENTIALS_MESSAGE = 'Invalid username or password.'
/** Used only if a 429 arrives without a usable `Retry-After`. */
const DEFAULT_RETRY_AFTER_SEC = 60

/**
 * The sign-in form (frontend.md §3 Login, §6): Zod validation, server error mapping (400 fields, 401 generic, 403 generic
 * after the mutation's single CSRF retry, 429 countdown from `Retry-After`, 5xx/network generic + correlation id).
 * The password is cleared after every response; the mutation is reset so its variables leave the MutationCache.
 * 429 (NFR-A11Y): the alert is announced once, with the wait at that moment; the visible per-second countdown sits outside
 * the alert in an `aria-live="off"` element, so screen readers are not interrupted every second.
 */
export function LoginForm({ onSignedIn }: { onSignedIn: (me: Me) => void }) {
  const form = useForm<LoginValues>({ resolver: zodResolver(loginSchema), defaultValues: { username: '', password: '' } })
  const {
    control,
    handleSubmit,
    setError,
    setFocus,
    setValue,
    formState: { errors, isSubmitting },
  } = form
  const login = useLoginMutation()
  const [serverError, setServerError] = useState<ApiError | null>(null)
  const [retryIn, setRetryIn] = useState(0)
  const [throttledFor, setThrottledFor] = useState(0)
  const [showPassword, setShowPassword] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)
  const [rootFocusRequest, setRootFocusRequest] = useState(0)

  // 429: count down once a second; submit stays disabled until 0.
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
    if (!(error instanceof ApiError)) {
      setError('root.server', { type: 'server', message: GENERIC_ERROR_MESSAGE })
      focusRoot()
      return
    }
    if (error.status === 400) {
      applyProblemToForm(error, (name, option) => setError(name, option), LOGIN_FIELDS)
      const firstField = LOGIN_FIELDS.find((field) => error.fieldErrors.some((violation) => violation.field === field))
      if (firstField) setFocus(firstField)
      else focusRoot()
      return
    }
    if (error.status === 401) {
      setError('root.server', { type: 'server', message: INVALID_CREDENTIALS_MESSAGE })
    } else if (error.status === 429) {
      const seconds = error.retryAfterSec ?? DEFAULT_RETRY_AFTER_SEC
      setThrottledFor(seconds)
      setRetryIn(seconds)
      setError('root.server', { type: 'throttled' })
    } else {
      // 403 after the single CSRF retry, 5xx, network: generic text plus the correlation id.
      setServerError(error)
      setError('root.server', { type: 'server', message: GENERIC_ERROR_MESSAGE })
    }
    focusRoot()
  }

  const onSubmit = handleSubmit(async (values) => {
    setServerError(null)
    try {
      const me = await login.mutateAsync(values)
      onSignedIn(me)
    } catch (error) {
      showServerError(error)
    } finally {
      setValue('password', '')
      login.reset()
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
                  Too many attempts.<span className="sr-only"> Try again in {throttledFor} s.</span>
                </>
              }
            />
            <p aria-live="off" data-testid="login-retry-countdown" className="text-sm text-muted-foreground">
              Try again in {retryIn} s.
            </p>
          </div>
        ) : (
          <FormRootError ref={rootRef} message={rootMessage} error={rootMessage ? serverError : null} />
        )}
        <FormField
          control={control}
          name="username"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Mobile number or employee ID</FormLabel>
              <FormControl>
                <Input autoComplete="username" readOnly={isSubmitting} {...field} />
              </FormControl>
              <FormMessage />
            </FormItem>
          )}
        />
        <FormField
          control={control}
          name="password"
          render={({ field }) => (
            <FormItem>
              <FormLabel>Password</FormLabel>
              <div className="relative">
                <FormControl>
                  <Input
                    type={showPassword ? 'text' : 'password'}
                    autoComplete="current-password"
                    readOnly={isSubmitting}
                    className="pr-10"
                    {...field}
                  />
                </FormControl>
                <Button
                  variant="ghost"
                  onClick={() => setShowPassword((shown) => !shown)}
                  aria-label={showPassword ? 'Hide password' : 'Show password'}
                  aria-pressed={showPassword}
                  className="absolute inset-y-0 right-0 h-full px-3 text-muted-foreground hover:bg-transparent hover:text-foreground focus-visible:ring-offset-0"
                >
                  {showPassword ? <EyeOff aria-hidden="true" className="size-4" /> : <Eye aria-hidden="true" className="size-4" />}
                </Button>
              </div>
              <FormMessage />
            </FormItem>
          )}
        />
        <Button type="submit" disabled={isSubmitting || throttled}>
          {isSubmitting ? 'Signing in…' : 'Sign in'}
        </Button>
      </form>
    </Form>
  )
}
