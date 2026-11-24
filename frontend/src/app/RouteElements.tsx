import { Navigate, Outlet, useLocation } from 'react-router'
import { PageContainer } from '@/components/layout/PageContainer'
import { FullPageSpinner } from '@/components/states/LoadingState'
import { useMeQuery } from '@/features/authentication/meQuery'
import { NoAccessPage } from '@/pages/NoAccessPage'
import { landingFor } from '@/permissions/navConfig'
import { loginUrl } from './navigation'

/**
 * The protected layout. F16 replaces this placeholder with `AppShell`. The loader has resolved `/me` before it renders; if
 * the session is dropped while it is shown (another tab signed out), it sends the user to login with this page as `returnTo`.
 */
export function ProtectedLayout() {
  const { data: me } = useMeQuery()
  const { pathname, search, hash } = useLocation()
  if (me === undefined) return <FullPageSpinner />
  if (me === null) return <Navigate to={loginUrl(pathname + search + hash)} replace />
  return (
    <main id="main" tabIndex={-1}>
      <Outlet />
    </main>
  )
}

/** `/` (frontend.md §2): the user's first visible nav item; none → `NoAccessPage`. */
export function LandingRedirect() {
  const { data: me } = useMeQuery()
  const landing = landingFor(me)
  return landing ? <Navigate to={landing} replace /> : <NoAccessPage />
}

/** Public `/login`. F17 replaces this placeholder with `LoginPage`. */
export function LoginPlaceholder() {
  return (
    <main id="main" tabIndex={-1}>
      <PageContainer title="Sign in" />
    </main>
  )
}
