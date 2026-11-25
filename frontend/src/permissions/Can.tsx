import type { ReactNode } from 'react'
import type { Me, Permission } from '@/types/api'
import { usePermissions } from './usePermissions'

type CanProps = {
  allOf: readonly Permission[]
  userType?: Me['userType']
  fallback?: ReactNode
  children: ReactNode
}

/**
 * Renders `children` only if the user holds every permission in `allOf` (and the `userType`, when given); otherwise
 * `fallback` (default nothing). For actions, not pages (frontend.md §2, OQ 1). UI gating only: the server enforces (BR-10).
 */
export function Can({ allOf, userType, fallback = null, children }: CanProps) {
  const { can } = usePermissions()
  return <>{can(allOf, userType) ? children : fallback}</>
}
