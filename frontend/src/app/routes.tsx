import { Outlet, redirect, type LoaderFunctionArgs, type RouteObject } from 'react-router'
import { AppShell } from '@/components/layout/AppShell'
import { FullPageSpinner } from '@/components/states/LoadingState'
import { LoginPage } from '@/features/authentication/LoginPage'
import { meQuery } from '@/features/authentication/meQuery'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { RouteErrorBoundary } from '@/pages/RouteErrorPage'
import { NAV_ITEMS, paramPatternsOf, type NavChild, type NavItem, type RouteLabel } from '@/permissions/navConfig'
import { RequirePermission } from '@/permissions/RequirePermission'
import type { Permission } from '@/types/api'
import { LOGIN_PATH, loginUrl } from './navigation'
import { LAYOUT_REGISTRY, PAGE_REGISTRY, type LazyPage } from './pageRegistry'
import { ME_QUERY_KEY, queryClient } from './queryClient'
import { ComingSoonRoute, LandingRedirect, ParamGate } from './RouteElements'

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

type Registry = Readonly<Partial<Record<string, LazyPage>>>

/** The page and layout registries a route tree is built from (tests pass their own). */
type Registries = { pages: Registry; layouts: Registry }

/** The page of a guarded route: its `index` child, lazy-loaded from the registry (NFR-PERF), or the placeholder. */
function pageRoute(id: string, item: NavItem, label: RouteLabel, registry: Registry): RouteObject {
  const load = registry[id]
  return load ? { index: true, lazy: load } : { index: true, element: <ComingSoonRoute item={item} label={label} /> }
}

const isUnder = (path: string, parent: string) => path.startsWith(`${parent}/`)

/**
 * The child routes directly under `parentPath`: a child nests under the child whose path it extends (receipt under
 * payment status), so it inherits that child's guard and crumb. Each child's guard checks the parent's `requires` ∪ its own.
 * A child with a `layout` (03-inbound/frontend.md §2) wraps its page and nested children in that lazy layout route, inside
 * the guard and param check, so the layout survives navigation between them. Params are checked against the nav item's
 * patterns (§9 FE1).
 */
function childRoutes(
  item: NavItem,
  parentPath: string,
  parentRequires: readonly Permission[],
  children: readonly NavChild[],
  registries: Registries,
): RouteObject[] {
  const below = children.filter((child) => isUnder(child.path, parentPath))
  const direct = below.filter((child) => !below.some((other) => other !== child && isUnder(child.path, other.path)))
  const patterns = paramPatternsOf(item)
  return direct.map((child) => {
    const requires = [...new Set([...parentRequires, ...(child.requires ?? [])])]
    const pages = [
      pageRoute(child.id, item, child.label, registries.pages),
      ...childRoutes(item, child.path, requires, children, registries),
    ]
    const layout = child.layout === undefined ? undefined : registries.layouts[child.layout]
    return {
      path: child.path,
      handle: { crumb: child.label },
      element: (
        <RequirePermission allOf={requires} userType={item.userType}>
          <ParamGate patterns={patterns}>
            <Outlet />
          </ParamGate>
        </RequirePermission>
      ),
      children: layout ? [{ lazy: layout, children: pages }] : pages,
    }
  })
}

/**
 * One guarded parent route per nav registry entry (frontend.md §2 route shape), so a nav entry and its route guard can never
 * disagree. The guard wraps an `<Outlet/>` and renders before any lazy page mounts, so a forbidden user fires no page API call.
 */
export function buildNavRoutes(items: readonly NavItem[], registry: Registry, layouts: Registry = LAYOUT_REGISTRY): RouteObject[] {
  const registries: Registries = { pages: registry, layouts }
  return items.map((item) => ({
    path: item.path,
    handle: { crumb: item.label },
    element: (
      <RequirePermission allOf={item.requires} userType={item.userType}>
        <Outlet />
      </RequirePermission>
    ),
    children: [
      pageRoute(item.id, item, item.label, registry),
      ...childRoutes(item, item.path, item.requires, item.children ?? [], registries),
    ],
  }))
}

/** The app's route tree; tests pass their own page (and layout) registry to check lazy pages under the guard. */
export function buildAppRoutes(registry: Registry = PAGE_REGISTRY, layouts: Registry = LAYOUT_REGISTRY): RouteObject[] {
  return [
    // A render error on the public login page gets the same safe error screen as the protected area (XC-03).
    { path: LOGIN_PATH, element: <LoginPage />, errorElement: <RouteErrorBoundary /> },
    {
      id: 'protected',
      path: '/',
      loader: protectedLoader,
      // Re-check the session on every navigation, not only when this route's params change (§7 Re-validation).
      shouldRevalidate: () => true,
      element: <AppShell />,
      hydrateFallbackElement: <FullPageSpinner />,
      errorElement: <RouteErrorBoundary />,
      children: [
        { index: true, element: <LandingRedirect /> },
        ...buildNavRoutes(NAV_ITEMS, registry, layouts),
        { path: '*', element: <NotFoundPage /> },
      ],
    },
  ]
}

export const appRoutes: RouteObject[] = buildAppRoutes()
