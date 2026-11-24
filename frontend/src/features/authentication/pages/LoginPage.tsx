import { zodResolver } from '@hookform/resolvers/zod'
import { Headset, RadioTower, ShieldCheck } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { LoadingState } from '@/components/feedback/states'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { FormField } from '@/components/ui/form-field'
import { Input } from '@/components/ui/input'
import { ApiError } from '@/services/http'
import { homePathFor, useLogin, useMe } from '../api'
import { loginSchema, type LoginForm } from '../schema'

export function LoginPage() {
  const { data: me, isPending } = useMe()
  const login = useLogin()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from

  const form = useForm<LoginForm>({ resolver: zodResolver(loginSchema), defaultValues: { username: '', password: '' } })
  const { errors } = form.formState

  if (isPending) return <LoadingState variant="inline" />
  if (me) return <Navigate to={homePathFor(me)} replace />

  const onSubmit = form.handleSubmit((values) =>
    login.mutate(values, {
      onSuccess: (user) => navigate(from && from !== '/login' ? from : homePathFor(user), { replace: true }),
    }),
  )

  const serverError = login.error instanceof ApiError ? login.error.message : login.error ? 'Sign-in failed. Please try again.' : null

  return (
    <div className="grid min-h-full lg:grid-cols-2">
      <section className="hidden flex-col justify-between bg-sidebar p-10 text-sidebar-foreground lg:flex">
        <div className="flex items-center gap-2 text-lg font-semibold text-white">
          <RadioTower className="h-6 w-6 text-blue-400" aria-hidden /> Telecom Support
        </div>
        <div className="space-y-6">
          <h1 className="text-3xl font-semibold leading-tight text-white">One place for your account, plan, bills and service requests.</h1>
          <ul className="space-y-3 text-sm">
            <li className="flex items-center gap-3">
              <Headset className="h-5 w-5 text-blue-400" aria-hidden /> Raise and track service requests 24×7
            </li>
            <li className="flex items-center gap-3">
              <ShieldCheck className="h-5 w-5 text-blue-400" aria-hidden /> Secure sign-in, audited access for support staff
            </li>
          </ul>
        </div>
        <p className="text-xs text-sidebar-foreground/70">Postpaid customer support portal</p>
      </section>

      <main className="flex items-center justify-center p-6">
        <form onSubmit={onSubmit} noValidate className="w-full max-w-sm space-y-5" aria-labelledby="login-title">
          <div className="space-y-1">
            <h2 id="login-title" className="text-2xl font-semibold text-slate-900">
              Sign in
            </h2>
            <p className="text-sm text-muted">Customers use their registered mobile number. Staff use their employee ID.</p>
          </div>

          {serverError && <Alert tone="danger">{serverError}</Alert>}

          <FormField label="Mobile number or employee ID" error={errors.username?.message} required>
            <Input autoComplete="username" autoFocus placeholder="+91 98765 43210" {...form.register('username')} />
          </FormField>
          <FormField label="Password" error={errors.password?.message} required>
            <Input type="password" autoComplete="current-password" {...form.register('password')} />
          </FormField>

          <Button type="submit" className="w-full" size="lg" loading={login.isPending}>
            Sign in
          </Button>
        </form>
      </main>
    </div>
  )
}
