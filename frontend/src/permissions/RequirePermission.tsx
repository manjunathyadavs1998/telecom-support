import type { ReactNode } from 'react'
import { ForbiddenPage } from '@/pages/ForbiddenPage'
import type { Me, Permission } from '@/types/api'
import { usePermissions } from './usePermissions'

type RequirePermissionProps = {
  allOf: readonly Permission[]
  userType?: Me['userType']
  children: ReactNode
}

/**
 * Route guard (frontend.md §2 guard order, XC-02, BR-10): renders `children` only if the user holds every permission in
 * `allOf` (and the `userType`, when given); otherwise `ForbiddenPage` at the requested URL (no redirect). The page itself
 * never mounts, so none of its queries run. The session check happens before this, in the protected layout's loader.
 */
export function RequirePermission({ allOf, userType, children }: RequirePermissionProps) {
  const { can } = usePermissions()
  return <>{can(allOf, userType) ? children : <ForbiddenPage />}</>
}
