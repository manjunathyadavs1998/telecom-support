import { useMemo } from 'react'
import { useMeQuery } from '@/features/authentication/meQuery'
import type { Me, Permission } from '@/types/api'

export type Permissions = {
  /** `null` while bootstrapping or signed out. */
  userType: Me['userType'] | null
  permissions: ReadonlySet<Permission>
  /** True only if a user is signed in, holds every permission in `allOf` and, when given, has that user type. */
  can: (allOf: readonly Permission[], userType?: Me['userType']) => boolean
}

/**
 * The current user's permissions, set from the `/me` query (frontend.md §2, BR-10). UI gating only: the server enforces
 * every API. Empty while `/me` is pending, failed or anonymous.
 */
export function usePermissions(): Permissions {
  const { data: me } = useMeQuery()
  return useMemo(() => {
    const permissions: ReadonlySet<Permission> = new Set(me?.permissions ?? [])
    const userType = me?.userType ?? null
    return {
      userType,
      permissions,
      can: (allOf, requiredUserType) =>
        me != null &&
        (requiredUserType === undefined || requiredUserType === userType) &&
        allOf.every((permission) => permissions.has(permission)),
    }
  }, [me])
}
