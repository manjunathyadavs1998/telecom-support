import { zodResolver } from '@hookform/resolvers/zod'
import { Eye, EyeOff } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { FormRootError } from '@/components/states/FormRootError'
import { ApiError, applyProblemToForm, GENERIC_ERROR_MESSAGE } from '@/services/api/errors'
import type { Me } from '@/types/api'
import { LOGIN_FIELDS, loginSchema, type LoginValues } from './loginSchema'
import { useLoginMutation } from './useLoginMutation'

export const INVALID_CREDENTIALS_MESSAGE = 'Invalid username or password.'
/** Used only if a 429 arrives without a usable `Retry-After`. */
const DEFAULT_RETRY_AFTER_SEC = 60

const throttledMessage = (seconds: number) => `Too many attempts. Try again in ${seconds} s.`

const inputClass =
  'w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-foreground read-only:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring aria-invalid:border-destructive'

/**
 * The sign-in form (frontend.md §3 Login, §6): Zod validation, server error mapping (400 fields, 401 generic, 403 generic
 * after the mutation's single CSRF retry, 429 live countdown from `Retry-After`, 5xx/network generic + correlation id).
 * The password is cleared after every response; the mutation is reset so its variables leave the MutationCache.
 */
export function LoginForm({ onSignedIn }: { onSignedIn: (me: Me) => void }) {
  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    setValue,
    formState: { errors, isSubmitting },
  } = useForm<LoginValues>({ resolver: zodResolver(loginSchema), defaultValues: { username: '', password: '' } })
  const login = useLoginMutation()
  const [serverError, setServerError] = useState<ApiError | null>(null)
  const [retryIn, setRetryIn] = useState(0)
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
      setRetryIn(error.retryAfterSec ?? DEFAULT_RETRY_AFTER_SEC)
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

  const rootMessage = retryIn > 0 ? throttledMessage(retryIn) : errors.root?.server?.message || undefined

  return (
    <form noValidate onSubmit={onSubmit} className="flex flex-col gap-4" aria-busy={isSubmitting}>
      <FormRootError ref={rootRef} message={rootMessage} error={rootMessage ? serverError : null} />
      <div className="flex flex-col gap-1.5">
        <label htmlFor="login-username" className="text-sm font-medium text-foreground">
          Mobile number or employee ID
        </label>
        <input
          id="login-username"
          type="text"
          autoComplete="username"
          readOnly={isSubmitting}
          aria-invalid={errors.username ? true : undefined}
          aria-describedby={errors.username ? 'login-username-error' : undefined}
          className={inputClass}
          {...register('username')}
        />
        {errors.username ? (
          <p id="login-username-error" className="text-sm text-destructive">
            {errors.username.message}
          </p>
        ) : null}
      </div>
      <div className="flex flex-col gap-1.5">
        <label htmlFor="login-password" className="text-sm font-medium text-foreground">
          Password
        </label>
        <div className="relative">
          <input
            id="login-password"
            type={showPassword ? 'text' : 'password'}
            autoComplete="current-password"
            readOnly={isSubmitting}
            aria-invalid={errors.password ? true : undefined}
            aria-describedby={errors.password ? 'login-password-error' : undefined}
            className={`${inputClass} pr-10`}
            {...register('password')}
          />
          <button
            type="button"
            onClick={() => setShowPassword((shown) => !shown)}
            aria-label={showPassword ? 'Hide password' : 'Show password'}
            aria-pressed={showPassword}
            className="absolute inset-y-0 right-0 flex items-center rounded-md px-3 text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            {showPassword ? <EyeOff aria-hidden="true" className="size-4" /> : <Eye aria-hidden="true" className="size-4" />}
          </button>
        </div>
        {errors.password ? (
          <p id="login-password-error" className="text-sm text-destructive">
            {errors.password.message}
          </p>
        ) : null}
      </div>
      <button
        type="submit"
        disabled={isSubmitting || retryIn > 0}
        className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
      >
        {isSubmitting ? 'Signing in…' : 'Sign in'}
      </button>
    </form>
  )
}
