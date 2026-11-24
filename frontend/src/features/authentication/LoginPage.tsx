import { useQueryClient } from '@tanstack/react-query'
import { Info, RadioTower } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Navigate, useNavigate, useSearchParams } from 'react-router'
import { APP_TITLE } from '@/components/layout/PageContainer'
import { landingFor } from '@/permissions/navConfig'
import type { Me } from '@/types/api'
import { LoginForm } from './LoginForm'
import { applySignedIn, useMeQuery } from './meQuery'
import { afterLoginTarget } from './returnTo'

const REASON_BANNERS: Record<string, string> = {
  expired: 'Your session expired. Sign in again.',
  'signed-out': 'You signed out.',
}

/**
 * Public `/login` (frontend.md §3 Login, §7 After login): a centred card on the navy background, the `?reason=` banner and
 * the form. On success: clear the cache, store the user, tell the other tabs, then go to the safe `returnTo` the user may
 * open, else their landing route. A user who is already signed in is sent to their landing route.
 */
export function LoginPage() {
  const { data: me } = useMeQuery()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [signedIn, setSignedIn] = useState(false)
  const banner = REASON_BANNERS[params.get('reason') ?? '']

  useEffect(() => {
    document.title = `Sign in · ${APP_TITLE}`
  }, [])

  // The after-login navigation below owns the redirect once this page signed the user in.
  if (me && !signedIn) return <Navigate to={landingFor(me) ?? '/'} replace />

  function onSignedIn(user: Me) {
    setSignedIn(true)
    applySignedIn(queryClient, user)
    void navigate(afterLoginTarget(params.get('returnTo'), user), { replace: true })
  }

  return (
    <main id="main" tabIndex={-1} className="flex min-h-screen items-center justify-center bg-sidebar p-4">
      <div className="w-full max-w-sm rounded-lg bg-white p-6 shadow-lg sm:p-8">
        <div className="mb-6 flex items-center gap-2 text-foreground">
          <RadioTower aria-hidden="true" className="size-6 text-primary" />
          <span className="text-lg font-semibold">Telecom</span>
        </div>
        <h1 tabIndex={-1} className="mb-4 text-2xl font-semibold text-foreground focus-visible:outline-none">
          Sign in
        </h1>
        {banner ? (
          <output
            aria-live="polite"
            className="mb-4 flex items-start gap-2 rounded-md border border-primary/30 bg-primary/5 p-3 text-sm text-foreground"
          >
            <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-primary" />
            <span>{banner}</span>
          </output>
        ) : null}
        <LoginForm onSignedIn={onSignedIn} />
      </div>
    </main>
  )
}
