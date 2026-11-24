import { Navigate } from 'react-router'
import { useMeQuery } from '@/features/authentication/meQuery'
import { NoAccessPage } from '@/pages/NoAccessPage'
import { landingFor } from '@/permissions/navConfig'

/** `/` (frontend.md §2): the user's first visible nav item; none → `NoAccessPage`. */
export function LandingRedirect() {
  const { data: me } = useMeQuery()
  const landing = landingFor(me)
  return landing ? <Navigate to={landing} replace /> : <NoAccessPage />
}
