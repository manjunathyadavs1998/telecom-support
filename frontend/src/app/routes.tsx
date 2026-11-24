import { redirect, type LoaderFunctionArgs, type RouteObject } from 'react-router'
import { AppShell } from '@/components/layout/AppShell'
import { FullPageSpinner } from '@/components/states/LoadingState'
import { LoginPage } from '@/features/authentication/LoginPage'
import { meQuery } from '@/features/authentication/meQuery'
import { ComingSoonPage } from '@/pages/ComingSoonPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { RouteErrorBoundary } from '@/pages/RouteErrorPage'
import { NAV_ITEMS } from '@/permissions/navConfig'
import { RequirePermission } from '@/permissions/RequirePermission'
import { LOGIN_PATH, loginUrl } from './navigation'
import { ME_QUERY_KEY, queryClient } from './queryClient'
import { LandingRedirect } from './RouteElements'

/**
 * Session re-validation on every navigation into the protected area (frontend.md §7 Re-validation): `fetchQuery(meQuery)`
 * returns the cached `/me` while < 60 s old, else refetches it. No session → `/login?returnTo=<target>`; a `null` while a
 * user was cached → expiry. A `/me` 401 with a user cached is first seen by the API client's 401 handler, which navigates to
 * `/login?reason=expired&returnTo=<target>` (the navigator reports this navigation's target) and so aborts this navigation:
 * then the loader adds no second redirect.
 */
export async function protectedLoader({ request }: LoaderFunctionArgs): Promise<null> {
  const url = new URL(request.url)
  const target = url.pathname + url.search + url.hash
  const hadUser = queryClient.getQueryData(ME_QUERY_KEY) != null
  const me = await queryClient.fetchQuery(meQuery)
  if (me) return null
  if (request.signal.aborted) return null
  if (hadUser) {
    queryClient.clear()
    throw redirect(loginUrl(target, 'expired'))
  }
  throw redirect(loginUrl(target))
}

/** One guarded route per nav registry entry (frontend.md §2), so a nav entry and its route guard can never disagree. */
const navRoutes: RouteObject[] = NAV_ITEMS.map((item) => ({
  path: item.path,
  handle: { crumb: item.label },
  element: (
    <RequirePermission allOf={item.requires} userType={item.userType}>
      <ComingSoonPage item={item} />
    </RequirePermission>
  ),
}))

export const appRoutes: RouteObject[] = [
  { path: LOGIN_PATH, element: <LoginPage /> },
  {
    id: 'protected',
    path: '/',
    loader: protectedLoader,
    // Re-check the session on every navigation, not only when this route's params change (§7 Re-validation).
    shouldRevalidate: () => true,
    element: <AppShell />,
    hydrateFallbackElement: <FullPageSpinner />,
    errorElement: <RouteErrorBoundary />,
    children: [{ index: true, element: <LandingRedirect /> }, ...navRoutes, { path: '*', element: <NotFoundPage /> }],
  },
]
