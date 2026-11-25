import { useQueryClient } from '@tanstack/react-query'
import { Info, RadioTower } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Navigate, useLocation, useNavigate, useSearchParams } from 'react-router'
import { APP_TITLE } from '@/components/layout/PageContainer'
import { Alert } from '@/components/ui/alert'
import { Card, CardHeader } from '@/components/ui/card'
import { landingFor } from '@/permissions/navConfig'
import type { Me } from '@/types/api'
import { LoginForm } from './LoginForm'
import { applySignedIn, useMeQuery } from './meQuery'
import { afterLoginTarget } from './returnTo'

const REASON_BANNERS: Readonly<Record<string, string>> = {
  expired: 'Your session expired. Sign in again.',
  'signed-out': 'You signed out.',
}

/** Own keys only: `?reason=__proto__` or `?reason=toString` must not reach `Object.prototype` (XC-03). */
const bannerFor = (reason: string | null): string | undefined =>
  reason !== null && Object.hasOwn(REASON_BANNERS, reason) ? REASON_BANNERS[reason] : undefined

/**
 * Public `/login` (frontend.md §3 Login, §7 After login): a centred card on the navy background, the `?reason=` banner and
 * the form. On success: clear the cache, store the user, tell the other tabs, then go to the safe `returnTo` the user may
 * open, else their landing route. A signed-in user on any login-route variant (`/login/`, `/Login`, …) is sent to their
 * landing route, except on the very location where this page just signed them in (the after-login navigation owns that).
 */
export function LoginPage() {
  const { data: me } = useMeQuery()
  const [params] = useSearchParams()
  const { key: locationKey } = useLocation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [signedInAt, setSignedInAt] = useState<string | null>(null)
  const banner = bannerFor(params.get('reason'))

  useEffect(() => {
    document.title = `Sign in · ${APP_TITLE}`
  }, [])

  if (me && signedInAt !== locationKey) return <Navigate to={landingFor(me) ?? '/'} replace />

  function onSignedIn(user: Me) {
    setSignedInAt(locationKey)
    applySignedIn(queryClient, user)
    void navigate(afterLoginTarget(params.get('returnTo'), user), { replace: true })
  }

  return (
    <main id="main" tabIndex={-1} className="flex min-h-screen items-center justify-center bg-sidebar p-4">
      <Card className="w-full max-w-sm sm:p-8">
        <CardHeader>
          <div className="flex items-center gap-2">
            <RadioTower aria-hidden="true" className="size-6 text-primary" />
            <span className="text-lg font-semibold">Telecom</span>
          </div>
          <h1 tabIndex={-1} className="text-2xl font-semibold text-foreground focus-visible:outline-none">
            Sign in
          </h1>
        </CardHeader>
        {banner ? (
          <Alert variant="info" asChild className="mb-4">
            <output aria-live="polite">
              <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-primary" />
              <span>{banner}</span>
            </output>
          </Alert>
        ) : null}
        <LoginForm onSignedIn={onSignedIn} />
      </Card>
    </main>
  )
}
