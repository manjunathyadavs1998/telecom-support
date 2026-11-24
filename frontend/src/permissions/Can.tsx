import type { ReactNode } from 'react'
import { Navigate } from 'react-router'
import type { Permission } from './permissions'
import { useHasPermission } from './usePermissions'

/** Render children only when the user holds (any of) the permission(s). */
export function Can({
  permission,
  children,
  fallback = null,
}: {
  permission: Permission | Permission[]
  children: ReactNode
  fallback?: ReactNode
}) {
  return useHasPermission(permission) ? <>{children}</> : <>{fallback}</>
}

/** Route guard: direct navigation to a restricted route is denied (XC-02). */
export function RequirePermission({ permission, children }: { permission: Permission | Permission[]; children: ReactNode }) {
  return useHasPermission(permission) ? <>{children}</> : <Navigate to="/forbidden" replace />
}
