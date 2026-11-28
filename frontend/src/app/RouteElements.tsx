import type { ReactNode } from 'react'
import { Navigate, useParams } from 'react-router'
import { useShellContext } from '@/components/layout/shellContext'
import { useMeQuery } from '@/features/authentication/meQuery'
import { ComingSoonPage } from '@/pages/ComingSoonPage'
import { NoAccessPage } from '@/pages/NoAccessPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { landingFor, ROUTE_PARAM_PATTERNS, type NavItem, type RouteLabel } from '@/permissions/navConfig'

/** `/` (frontend.md §2): the user's first visible nav item; none → `NoAccessPage`. */
export function LandingRedirect() {
  const { data: me } = useMeQuery()
  const shell = useShellContext()
  const landing = landingFor(me)
  return landing ? <Navigate to={landing} replace /> : <NoAccessPage onSignOut={shell?.logout} />
}

/** A page id without a registry entry: the slice placeholder, titled with the route's label (frontend.md §2). */
export function ComingSoonRoute({ item, label }: { item: NavItem; label: RouteLabel }) {
  const params = useParams()
  return <ComingSoonPage item={{ ...item, label: typeof label === 'function' ? label(params) : label }} />
}

/**
 * A route param that fails its §2 pattern (e.g. a malformed payment reference) → `NotFoundPage`; the page never mounts.
 * `patterns` are the nav item's (03-inbound/frontend.md §9 FE1: e.g. `SR-…` on the inbound case route), default the global ones.
 */
export function ParamGate({
  children,
  patterns = ROUTE_PARAM_PATTERNS,
}: {
  children: ReactNode
  patterns?: Readonly<Record<string, RegExp>>
}) {
  const params = useParams()
  const bad = Object.entries(params).some(([name, value]) => {
    const pattern = patterns[name]
    return pattern !== undefined && (value === undefined || !pattern.test(value))
  })
  return <>{bad ? <NotFoundPage /> : children}</>
}
