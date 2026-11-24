import { useMe } from '@/features/authentication/api'
import type { Permission } from './permissions'

/** UX-only permission check (XC-01). The backend independently enforces every rule (BR-10). */
export function useHasPermission(permission: Permission | Permission[] | undefined): boolean {
  const { data: me } = useMe()
  if (!permission) return true
  if (!me) return false
  const required = Array.isArray(permission) ? permission : [permission]
  return required.some((p) => me.permissions.includes(p))
}
